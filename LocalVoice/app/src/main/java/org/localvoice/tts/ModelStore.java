package org.localvoice.tts;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Facade over model storage: job queue, progress, installed snapshot and voice selection.
 * Only download() accesses the network (via ModelInstaller/ModelDownloader); construction and voice discovery are offline.
 */
public final class ModelStore {
    public static void startProfile() {StorageMetrics.start();}
    public static String profileJson() {return StorageMetrics.json();}
    public static String finishProfile() {return StorageMetrics.finish();}
    public interface Listener { void changed(); }
    public static final class Progress {
        public final String state, detail;
        public final long bytes, total;
        public final long phaseBytes,phaseTotal;
        public final int filesDone,filesTotal;
        public final boolean running;
        Progress(String state, String detail, long bytes, long total, boolean running) {
            this(state,detail,bytes,total,running,0,0,0,0);
        }
        Progress(String state,String detail,long bytes,long total,boolean running,long phaseBytes,long phaseTotal,int filesDone,int filesTotal) {
            this.state=state; this.detail=detail; this.bytes=bytes; this.total=total; this.running=running;
            this.phaseBytes=phaseBytes;this.phaseTotal=phaseTotal;this.filesDone=filesDone;this.filesTotal=filesTotal;
        }
        boolean same(Progress other){return other!=null&&state.equals(other.state)&&detail.equals(other.detail)&&bytes==other.bytes&&total==other.total&&running==other.running&&phaseBytes==other.phaseBytes&&phaseTotal==other.phaseTotal&&filesDone==other.filesDone&&filesTotal==other.filesTotal;}
    }
    /** One worker serialises every install, removal and snapshot refresh. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService CANCEL_IO=Executors.newSingleThreadExecutor();
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<String, Progress> PROGRESS = new ConcurrentHashMap<>();
    /** Installed-directory path -> last inspected state; served to bursty TTS metadata calls. */
    static final Map<String,Boolean> INSTALLED = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean CHANGE_PENDING=new java.util.concurrent.atomic.AtomicBoolean();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Set<String> REMOVING = new HashSet<>();
    private static final Object OPERATIONS = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final VoicePreferences prefs;
    private final Context context;
    private final ModelInstaller installer;
    private boolean refreshPending;
    private long refreshedAt;

    static final class Job {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        boolean activated;
        long lastProgress;
    }

    public ModelStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = new VoicePreferences(this.context);
        installer = new ModelInstaller(this, storageRoot(this.context));
        EdgeCatalogRepository.initialize(this.context).thenRun(ModelStore::changed);
        refreshInstalled();
    }
    private static File storageRoot(Context context) {
        return new File(context.getApplicationContext().getNoBackupFilesDir(), "models");
    }
    /** Pure path lookup: no storage work, worker or catalog bootstrap. */
    public static File modelDirectory(Context context, String id) {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null) throw new IllegalArgumentException("Unknown model");
        return ModelInstaller.directory(storageRoot(context), model, false);
    }
    public File modelDirectory(String id) {
        return modelDirectory(context, id);
    }
    public boolean isInstalled(String id) {
        long before=StorageMetrics.now();StorageMetrics.INSTALLED_CALLS.increment();
        if(Looper.myLooper()==Looper.getMainLooper())StorageMetrics.INSTALLED_MAIN.increment();
        try {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null) return false;
        if (model.network) return prefs.onlineEnabled(id);
        String path=installer.directory(model,false).getAbsolutePath();
        if(Looper.myLooper()==Looper.getMainLooper()) {
            refreshInstalled();
            return Boolean.TRUE.equals(INSTALLED.get(path));
        }
        // TTS metadata calls arrive in bursts (Chromium probes ~900 locales per engine bind); re-statting every
        // model file per call took minutes. Serve the snapshot, refresh it in the background; synthesis still verifies.
        Boolean cached=INSTALLED.get(path);
        if(cached!=null){refreshInstalled();return cached;}
        boolean result=installer.inspectInstalled(model);
        INSTALLED.put(path,result);
        return result;
        } finally {StorageMetrics.elapsed(StorageMetrics.INSTALLED_NANOS,before);}
    }
    private synchronized void refreshInstalled() {
        if(refreshPending||System.nanoTime()-refreshedAt<1_000_000_000L)return;
        refreshPending=true;
        WORK.execute(()->{
            boolean different=false;
            try {
                for(Catalog.Model model:Catalog.MODELS)if(!model.network){
                    boolean result=installer.inspectInstalled(model);
                    Boolean previous=INSTALLED.put(installer.directory(model,false).getAbsolutePath(),result);
                    different|=previous==null||previous!=result;
                }
            }finally{synchronized(this){refreshPending=false;refreshedAt=System.nanoTime();}if(different)changed();}
        });
    }
    /** Call on the inference worker before opening a model. Never on a binder/UI thread. */
    public void verifyInstalled(String id) throws IOException {
        verifyInstalled(id,()->false);
    }
    void verifyInstalled(String id,java.util.function.BooleanSupplier cancelled) throws IOException {
        long before=StorageMetrics.now();
        try {
            if(cancelled.getAsBoolean())throw new InterruptedIOException("Verification cancelled");
            Catalog.Model model = Catalog.findModel(id);
            if (model == null || !isInstalled(id)) throw new IOException("Download this model in LocalVoice first");
            if (model.network) return;
            installer.verify(model,cancelled);
        } finally {StorageMetrics.elapsed(StorageMetrics.VERIFY_INSTALLED_NANOS,before);}
    }
    public List<Catalog.VoiceSpec> installedVoices() {
        if(Looper.myLooper()!=Looper.getMainLooper())EdgeCatalogRepository.get(context);
        List<Catalog.VoiceSpec> voices = new ArrayList<>();
        for (Catalog.Model model : Catalog.MODELS) if (isInstalled(model.id)) voices.addAll(model.voices);
        return voices;
    }
    public String selectedVoice() {
        String id = prefs.selected();
        Catalog.VoiceSpec selected = Catalog.findVoice(id);
        if (selected != null && isInstalled(selected.modelId)) return id;
        for (Catalog.VoiceSpec voice : installedVoices())
            if (!Catalog.findModel(voice.modelId).network) return voice.id;
        return "";
    }
    public void selectVoice(String id) {
        Catalog.VoiceSpec voice = Catalog.findVoice(id);
        if (voice == null || !isInstalled(voice.modelId)) throw new IllegalArgumentException("Voice is not installed");
        prefs.select(id);
    }
    public void enableOnline(String id) {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null || !model.network) throw new IllegalArgumentException("Not an online backend");
        prefs.enableOnline(id,true);
        changed();
        broadcastVoices();
    }
    void broadcastVoices() { context.sendBroadcast(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)); }
    /** Serialized behind cancellation so a queued/running install cannot reappear after deletion. */
    public void remove(String id, boolean cacheOnly) {
        Catalog.Model model=Catalog.findModel(id);
        if(model==null) return;
        if(model.network) {
            // Consent revocation must never wait behind a model download.
            if(!cacheOnly)prefs.enableOnline(id,false);
            PROGRESS.remove(id);changed();broadcastVoices();return;
        }
        synchronized(OPERATIONS) {
            if(!REMOVING.add(id)) return;
            cancel(id);
            WORK.execute(() -> {
                try {
                    installer.delete(model,cacheOnly);
                    PROGRESS.remove(id);
                    broadcastVoices();
                } catch(IOException error) {
                    android.util.Log.w("LocalVoiceDownload","Remove failed model="+id,error);
                    update(model,"Storage action failed",safeError(error),0,false);
                } finally {
                    synchronized(OPERATIONS) { REMOVING.remove(id); }
                    changed();
                }
            });
        }
    }
    public void addListener(Listener listener) { LISTENERS.add(listener); }
    public void removeListener(Listener listener) { LISTENERS.remove(listener); }
    static void changed() {if(!CHANGE_PENDING.compareAndSet(false,true))return;StorageMetrics.CHANGE_POSTS.increment(); MAIN.post(() -> {CHANGE_PENDING.set(false); for (Listener listener : LISTENERS) {StorageMetrics.LISTENER_CALLS.increment();listener.changed();} }); }
    public Progress progress(String id) {
        StorageMetrics.PROGRESS_CALLS.increment();
        Catalog.Model model = Catalog.findModel(id);
        Progress value = PROGRESS.get(id);
        if (value != null && (!value.state.equals("Installed") || isInstalled(id))) return value;
        return new Progress(isInstalled(id) ? "Installed" : "Not downloaded", "", 0, model == null ? 0 : model.bytes(), false);
    }
    static void update(Catalog.Model model, String state, String detail, long bytes, boolean running) {
        publish(model.id,new Progress(state, detail, bytes, model.bytes(), running));
    }
    private static void publish(String id,Progress next){
        if(next.same(PROGRESS.get(id))){StorageMetrics.PROGRESS_SKIPS.increment();return;}
        PROGRESS.put(id,next);StorageMetrics.PROGRESS_WRITES.increment();changed();
    }
    /** Running-phase progress, throttled to five updates per second unless forced. */
    static void phase(Catalog.Model model,Job job,String state,String detail,long completed,long bytes,long total,int files,boolean force) {
        long now=System.nanoTime();
        if(!force&&now-job.lastProgress<200_000_000L){StorageMetrics.PROGRESS_SKIPS.increment();return;}
        job.lastProgress=now;
        publish(model.id,new Progress(state,detail,completed,model.bytes(),true,bytes,total,files,model.files.size()));
    }
    public void cancel(String id) {
        Job job = JOBS.get(id);
        if (job == null) return;
        HttpURLConnection connection;
        synchronized (job) {
            if (job.activated||job.cancelled) return;
            job.cancelled = true;
            connection = job.connection;
        }
        if(connection!=null)CANCEL_IO.execute(()->{
            try{connection.disconnect();}catch(RuntimeException ignored){/* Already closed by the worker. */}
        });
    }
    /** Explicit user action. */
    public void download(String id) {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null || model.network || !model.available || isInstalled(id)) return;
        synchronized(OPERATIONS) {
            if(REMOVING.contains(id)) return;
            Job job = new Job();
            if (JOBS.putIfAbsent(id, job) != null) return;
            update(model, "Queued", "", 0, true);
            WORK.execute(() -> {
                try {
                    installer.install(model, job);
                } finally {
                    JOBS.remove(model.id, job);
                    changed();
                }
            });
        }
    }
    static void checkCancelled(Job job) throws IOException {
        if (job.cancelled) throw new InterruptedIOException("Download cancelled");
    }
    static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
