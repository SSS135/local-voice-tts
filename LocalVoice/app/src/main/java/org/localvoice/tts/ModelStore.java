package org.localvoice.tts;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Only download() accesses the network; construction and voice discovery are offline. */
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
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService CANCEL_IO=Executors.newSingleThreadExecutor();
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<String, Progress> PROGRESS = new ConcurrentHashMap<>();
    private static final ModelFiles.VerificationCache VERIFIED = new ModelFiles.VerificationCache(AndroidFileStamp::read);
    private static final Map<String,Boolean> INSTALLED = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean CHANGE_PENDING=new java.util.concurrent.atomic.AtomicBoolean();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Set<String> REMOVING = new HashSet<>();
    private static final Map<android.net.Uri,Integer> URI_USERS=new HashMap<>();
    private static final Set<android.net.Uri> OWNED_URIS=new HashSet<>();
    private static final Object OPERATIONS = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long RESERVE = 32L * 1024 * 1024;
    private static final String TRANSFER_PHASE="Downloading and checking";
    private final File root;
    private final VoicePreferences prefs;
    private final Context context;
    private boolean refreshPending;
    private long refreshedAt;

    private static final class Job {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile InputStream input;
        boolean activated;
        long lastProgress;
    }

    public ModelStore(Context context) {
        this.context=context.getApplicationContext();
        root = new File(context.getApplicationContext().getNoBackupFilesDir(), "models");
        prefs = new VoicePreferences(context.getApplicationContext());
        WORK.execute(()->{synchronized(OPERATIONS){
            android.content.SharedPreferences grants=this.context.getSharedPreferences("model-import-grants",Context.MODE_PRIVATE);
            Set<String> retained=new HashSet<>(grants.getStringSet("owned",Collections.emptySet()));
            for(String encoded:new HashSet<>(retained)){
                android.net.Uri uri=android.net.Uri.parse(encoded);
                if(URI_USERS.containsKey(uri))continue;
                try{this.context.getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
                retained.remove(encoded);
            }
            grants.edit().putStringSet("owned",retained).apply();
        }});
        EdgeCatalogRepository.initialize(this.context).thenRun(ModelStore::changed);
        refreshInstalled();
    }
    private File directory(Catalog.Model model, boolean staging) {
        return new File(root, model.id + "-" + model.revision + (staging ? ".download" : ""));
    }
    public File modelDirectory(String id) {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null) throw new IllegalArgumentException("Unknown model");
        return directory(model, false);
    }
    public boolean isInstalled(String id) {
        long before=StorageMetrics.now();StorageMetrics.INSTALLED_CALLS.increment();
        if(Looper.myLooper()==Looper.getMainLooper())StorageMetrics.INSTALLED_MAIN.increment();
        try {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null) return false;
        if (model.network) return prefs.onlineEnabled(id);
        if(Looper.myLooper()==Looper.getMainLooper()) {
            refreshInstalled();
            return Boolean.TRUE.equals(INSTALLED.get(directory(model,false).getAbsolutePath()));
        }
        boolean result=inspectInstalled(model);
        INSTALLED.put(directory(model,false).getAbsolutePath(),result);
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
                    boolean result=inspectInstalled(model);
                    Boolean previous=INSTALLED.put(directory(model,false).getAbsolutePath(),result);
                    different|=previous==null||previous!=result;
                }
            }finally{synchronized(this){refreshPending=false;refreshedAt=System.nanoTime();}if(different)changed();}
        });
    }
    private boolean inspectInstalled(Catalog.Model model) {
        File dir = directory(model, false);
        if (!ModelFiles.receiptMatches(dir, model.revision)) return false;
        try {
            for (Catalog.ModelFile file : model.files) {
                File local = ModelFiles.child(dir, file.path);
                ModelFiles.Stamp stamp=AndroidFileStamp.read(local);
                if(!stamp.regular||stamp.size!=file.bytes)return false;
            }
            return true;
        } catch (IOException e) { return false; }
    }
    /** Call on the inference worker before opening a model. Never on a binder/UI thread. */
    public void verifyInstalled(String id) throws IOException {
        verifyInstalled(id,()->false);
    }
    private void verifyInstalled(String id,java.util.function.BooleanSupplier cancelled) throws IOException {
        long before=StorageMetrics.now();
        try {
        if(cancelled.getAsBoolean())throw new InterruptedIOException("Verification cancelled");
        Catalog.Model model = Catalog.findModel(id);
        if (model == null || !isInstalled(id)) throw new IOException("Download this model in LocalVoice first");
        if (model.network) return;
            for (Catalog.ModelFile file : model.files) {
                if (!VERIFIED.verified(ModelFiles.child(directory(model, false), file.path), file.bytes, file.sha256,model.revision,cancelled,n->{})) {
                    new File(directory(model, false), "installed.manifest").delete();
                    VERIFIED.forget(directory(model,false));INSTALLED.put(directory(model,false).getAbsolutePath(),false); changed();
                    throw new IOException("Model is damaged. Download it again in LocalVoice");
                }
            }
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
    private void broadcastVoices() { context.sendBroadcast(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)); }
    /** Serialized behind cancellation so a queued/running install cannot reappear after deletion. */
    public void remove(String id, boolean cacheOnly) {
        Catalog.Model model=Catalog.findModel(id);
        if(model==null) return;
        if(model.network) {
            // Consent revocation must never wait behind a model download/import.
            if(!cacheOnly)prefs.enableOnline(id,false);
            PROGRESS.remove(id);changed();broadcastVoices();return;
        }
        synchronized(OPERATIONS) {
            if(!REMOVING.add(id)) return;
            cancel(id);
            WORK.execute(() -> {
                try {
                    ModelFiles.remove(directory(model,true));
                    VERIFIED.forget(directory(model,true));
                    if(!cacheOnly) {
                        if(model.network) prefs.enableOnline(id,false);
                        else {ModelFiles.remove(directory(model,false));VERIFIED.forget(directory(model,false));INSTALLED.put(directory(model,false).getAbsolutePath(),false);}
                    }
                    PROGRESS.remove(id); broadcastVoices();
                } catch(IOException error) { update(model,"Storage action failed",safeError(error),0,false); }
                finally { synchronized(OPERATIONS) { REMOVING.remove(id); } changed(); }
            });
        }
    }
    public void addListener(Listener listener) { LISTENERS.add(listener); }
    public void removeListener(Listener listener) { LISTENERS.remove(listener); }
    private static void changed() {if(!CHANGE_PENDING.compareAndSet(false,true))return;StorageMetrics.CHANGE_POSTS.increment(); MAIN.post(() -> {CHANGE_PENDING.set(false); for (Listener listener : LISTENERS) {StorageMetrics.LISTENER_CALLS.increment();listener.changed();} }); }
    public Progress progress(String id) {
        StorageMetrics.PROGRESS_CALLS.increment();
        Catalog.Model model = Catalog.findModel(id);
        Progress value = PROGRESS.get(id);
        if (value != null && (!value.state.equals("Installed") || isInstalled(id))) return value;
        return new Progress(isInstalled(id) ? "Installed" : "Not downloaded", "", 0, model == null ? 0 : model.bytes(), false);
    }
    private static void update(Catalog.Model model, String state, String detail, long bytes, boolean running) {
        publish(model.id,new Progress(state, detail, bytes, model.bytes(), running));
    }
    private static void publish(String id,Progress next){
        if(next.same(PROGRESS.get(id))){StorageMetrics.PROGRESS_SKIPS.increment();return;}
        PROGRESS.put(id,next);StorageMetrics.PROGRESS_WRITES.increment();changed();
    }
    private static void phase(Catalog.Model model,Job job,String state,String detail,long completed,long bytes,long total,int files,boolean force) {
        long now=System.nanoTime();
        if(!force&&now-job.lastProgress<200_000_000L){StorageMetrics.PROGRESS_SKIPS.increment();return;}
        job.lastProgress=now;
        publish(model.id,new Progress(state,detail,completed,model.bytes(),true,bytes,total,files,model.files.size()));
    }
    public void cancel(String id) {
        Job job = JOBS.get(id);
        if (job == null) return;
        HttpURLConnection connection;InputStream input;
        synchronized (job) {
            if (job.activated||job.cancelled) return;
            job.cancelled = true;
            connection = job.connection;input=job.input;
        }
        if(connection!=null||input!=null)CANCEL_IO.execute(()->{
            if(connection!=null)try{connection.disconnect();}catch(RuntimeException ignored){}
            if(input!=null)try{input.close();}catch(IOException ignored){}
        });
    }
    /** Explicit user action. Verified staging files are reused after a retry or process restart. */
    public void download(String id) {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null || model.network || !model.available || "import".equals(model.config.get("installMode")) || isInstalled(id)) return;
        synchronized(OPERATIONS) {
            if(REMOVING.contains(id)) return;
            Job job = new Job();
            if (JOBS.putIfAbsent(id, job) != null) return;
            update(model, "Queued", "", 0, true);
            WORK.execute(() -> install(model, job));
        }
    }
    private static void checkCancelled(Job job) throws IOException {
        if (job.cancelled) throw new InterruptedIOException("Download cancelled");
    }
    private void install(Catalog.Model model, Job job) {
        long before=StorageMetrics.now();
        long completed = 0;
        File staging = directory(model, true);
        ModelFiles.FreshVerification fresh=new ModelFiles.FreshVerification(staging,model.revision,AndroidFileStamp::read,AndroidFileStamp::open);
        try {
            checkCancelled(job);
            if(reuseInstalled(model,job))return;
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private model storage");
            File damaged=directory(model,false);
            checkCancelled(job);
            if(damaged.isDirectory()&&!staging.exists()){
                    // A rejected installation is never advertised. Rehash its files for explicit repair.
                    File from=ModelFiles.child(root,damaged.getName()),to=ModelFiles.child(root,staging.getName());
                    File receipt=ModelFiles.child(from,"installed.manifest");
                    checkCancelled(job);
                    if(receipt.exists()&&!receipt.delete())throw new IOException("Cannot invalidate damaged model receipt");
                    VERIFIED.forget(damaged);
                    checkCancelled(job);
                    if(!from.renameTo(to))throw new IOException("Cannot prepare damaged model for repair");
                    INSTALLED.put(damaged.getAbsolutePath(),false);
            }
            boolean freshStaging=ModelFiles.prepareStaging(root,staging);
            ModelFiles.DownloadPaths paths=new ModelFiles.DownloadPaths(root,staging);
            checkCancelled(job);
            Set<String> reusable=new HashSet<>();
            long[] checked={0};int filesDone=0;
            if(!freshStaging){
            phase(model,job,"Checking files","",0,0,model.bytes(),0,true);
            for (Catalog.ModelFile file : model.files) {
                checkCancelled(job);
                File local = ModelFiles.child(staging, file.path);
                final int index=filesDone;final long done=completed;
                if (VERIFIED.verified(local,file.bytes,file.sha256,model.revision,()->job.cancelled,n->{checked[0]+=n;phase(model,job,"Checking files",new File(file.path).getName(),done,checked[0],model.bytes(),index,false);})) {
                    completed+=file.bytes;reusable.add(file.path);
                }
                filesDone++;
                phase(model,job,"Checking files",new File(file.path).getName(),completed,checked[0],model.bytes(),filesDone,false);
            }
            }
            DownloadFile.requireSpace(new StatFs(root.getPath()).getAvailableBytes(),model.bytes()-completed,RESERVE);
            phase(model,job,TRANSFER_PHASE,"",completed,completed,model.bytes(),0,true);
            for (Catalog.ModelFile file : model.files) {
                checkCancelled(job);
                if (reusable.contains(file.path)) continue;
                File local = paths.plan(file.path);
                // Staging is never advertised. An interrupted file is fully rechecked and overwritten on retry.
                transfer(model, file, local, completed, job,!freshStaging,paths);
                checkCancelled(job);
                final long downloaded=completed+file.bytes;
                fresh.verifyFresh(local,file.bytes,file.sha256,()->job.cancelled,n->phase(model,job,TRANSFER_PHASE,new File(file.path).getName(),downloaded,downloaded,model.bytes(),0,false));
                completed += file.bytes;
            }
            int finalFiles=0;long accepted=0;
            phase(model,job,"Verifying","",completed,0,model.bytes(),0,true);
            for(Catalog.ModelFile file:model.files){
                final int index=finalFiles;final long done=completed,base=accepted;final long[] hashed={0};
                checkCancelled(job);File local=paths.checked(file.path);
                if(!fresh.accepts(local,file.bytes,file.sha256)&&!VERIFIED.verified(local,file.bytes,file.sha256,model.revision,()->job.cancelled,n->{hashed[0]+=n;phase(model,job,"Verifying",new File(file.path).getName(),done,base+hashed[0],model.bytes(),index,false);}))
                    throw new IOException("Model changed before activation. Retry the download");
                finalFiles++;
                // Phase completion counts bytes whose identities/checksums have been accepted, including cache hits.
                accepted+=file.bytes;
                phase(model,job,"Verifying",new File(file.path).getName(),completed,accepted,model.bytes(),finalFiles,false);
            }
            phase(model,job,"Finalizing","",completed,model.bytes(),model.bytes(),model.files.size(),true);
            checkCancelled(job);
                // A later file's verification must not hide a change to any earlier verified file.
                for(Catalog.ModelFile file:model.files){File local=paths.checked(file.path);if(!fresh.accepts(local,file.bytes,file.sha256))VERIFIED.requireUnchanged(local,file.bytes,file.sha256,model.revision);}
                checkCancelled(job);
                fresh.close();
                checkCancelled(job);
            if(!publishInstallation(model,job,staging))return;
            update(model, "Installed", "Ready for offline speech", completed, false);
            broadcastVoices();
        } catch (Exception error) {
            update(model, job.cancelled ? "Cancelled" : "Download failed", job.cancelled ? "Verified files kept for retry" : safeError(error), completed, false);
        } finally {
            try{fresh.close();}catch(IOException ignored){/* All read-only closes were attempted; a pre-publication failure was already reported. */}
            StorageMetrics.elapsed(StorageMetrics.INSTALL_NANOS,before);
            HttpURLConnection connection = job.connection;
            if (connection != null) connection.disconnect();
            JOBS.remove(model.id, job);
            changed();
        }
    }
    private boolean reuseInstalled(Catalog.Model model,Job job)throws IOException {
        if(!inspectInstalled(model))return false;
        try{
            verifyInstalled(model.id,()->job.cancelled);
            synchronized(job){checkCancelled(job);job.activated=true;}
            update(model,"Installed","",model.bytes(),false);return true;
        }
        catch(InterruptedIOException cancelled){throw cancelled;}
        catch(IOException damaged){checkCancelled(job);return false;}
    }
    /** WORK alone owns payload writes; only the cancellation/rename decision needs the job monitor. */
    private boolean publishInstallation(Catalog.Model model,Job job,File staging)throws IOException {
        checkCancelled(job);ModelFiles.writeReceipt(staging,model.revision);checkCancelled(job);
        File target=directory(model,false);
        if(target.exists()){
            // A valid existing model wins even if it appeared while this job was queued.
            if(reuseInstalled(model,job))return false;
            checkCancelled(job);ModelFiles.remove(target,()->job.cancelled);
        }
        checkCancelled(job);
        synchronized(job){checkCancelled(job);ModelFiles.activate(staging,target);job.activated=true;}
        try{VERIFIED.relocated(staging,target);}catch(IOException cacheMiss){VERIFIED.forget(staging);VERIFIED.forget(target);}
        INSTALLED.put(target.getAbsolutePath(),true);return true;
    }
    /** Explicit SAF action; only this app's immutable package plan can be installed. */
    public void importModel(String id,android.net.Uri uri) {
        Catalog.Model model=Catalog.findModel(id);
        if(model==null||!model.available||model.network||!"import".equals(model.config.get("installMode"))||uri==null)return;
        synchronized(OPERATIONS){
            if(REMOVING.contains(id)||JOBS.containsKey(id))return;
            Job job=new Job();JOBS.put(id,job);update(model,"Queued","",0,true);
            WORK.execute(()->{
                try{
                    checkCancelled(job);
                    synchronized(OPERATIONS){retainUri(uri);}
                    importPackage(model,uri,job);
                }catch(IOException|SecurityException denied){JOBS.remove(model.id,job);update(model,job.cancelled?"Cancelled":"Import failed","Choose the package again to grant file access",0,false);changed();}
            });
        }
    }
    private void retainUri(android.net.Uri uri) {
        Integer users=URI_USERS.get(uri);
        if(users!=null){URI_USERS.put(uri,users+1);return;}
        boolean existing=false;
        for(android.content.UriPermission permission:context.getContentResolver().getPersistedUriPermissions())
            if(permission.getUri().equals(uri)&&permission.isReadPermission()){existing=true;break;}
        if(!existing){
            recordOwnedUri(uri,true);
            try{context.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(SecurityException denied){recordOwnedUri(uri,false);throw denied;}
            OWNED_URIS.add(uri);
        }
        URI_USERS.put(uri,1);
    }
    private void releaseUri(android.net.Uri uri){
        synchronized(OPERATIONS){
            Integer users=URI_USERS.get(uri);if(users==null)return;
            if(users>1){URI_USERS.put(uri,users-1);return;}
            URI_USERS.remove(uri);
            if(OWNED_URIS.remove(uri)){
                try{context.getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
                try{recordOwnedUri(uri,false);}catch(SecurityException unavailableJournal){/* Released grant; stale journal is harmless and reconciled later. */}
            }
        }
    }
    private void recordOwnedUri(android.net.Uri uri,boolean add){
        android.content.SharedPreferences grants=context.getSharedPreferences("model-import-grants",Context.MODE_PRIVATE);
        Set<String> owned=new HashSet<>(grants.getStringSet("owned",Collections.emptySet()));
        if(add)owned.add(uri.toString());else owned.remove(uri.toString());
        if(!grants.edit().putStringSet("owned",owned).commit())throw new SecurityException("Cannot retain model file access");
    }
    private void importPackage(Catalog.Model model,android.net.Uri uri,Job job){
        File staging=directory(model,true);long before=StorageMetrics.now();
        try{
            checkCancelled(job);if(reuseInstalled(model,job))return;
            if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create private model storage");
            ModelFiles.remove(staging);VERIFIED.forget(staging);
            if(!staging.mkdirs())throw new IOException("Cannot create import storage");
            long archive=Long.parseLong(model.config.get("importArchiveBytes"));
            DownloadFile.requireSpace(new StatFs(root.getPath()).getAvailableBytes(),Math.addExact(model.bytes(),archive),RESERVE);
            phase(model,job,"Importing","",0,0,model.bytes(),0,true);
            try(InputStream input=context.getContentResolver().openInputStream(uri)){
                if(input==null)throw new IOException("Cannot open selected package");
                synchronized(job){checkCancelled(job);job.input=input;}
                SecureModelPackImporter.importPack(input,staging,model,()->job.cancelled,n->phase(model,job,"Reading package","",0,n,archive,0,false),n->phase(model,job,"Extracting","",Math.min(n,model.bytes()),Math.min(n,model.bytes()),model.bytes(),0,false));
            }finally{job.input=null;}
            long[] checked={0};int files=0;
            for(Catalog.ModelFile file:model.files){
                final int index=files;
                if(!VERIFIED.verified(ModelFiles.child(staging,file.path),file.bytes,file.sha256,model.revision,()->job.cancelled,n->{checked[0]+=n;phase(model,job,"Verifying","",model.bytes(),checked[0],model.bytes(),index,false);}))throw new IOException("Imported model checksum mismatch");
                files++;
            }
            phase(model,job,"Finalizing","",model.bytes(),model.bytes(),model.bytes(),files,true);
            checkCancelled(job);
                for(Catalog.ModelFile file:model.files)VERIFIED.requireUnchanged(ModelFiles.child(staging,file.path),file.bytes,file.sha256,model.revision);
            checkCancelled(job);if(!publishInstallation(model,job,staging))return;
            update(model,"Installed","",model.bytes(),false);broadcastVoices();
        }catch(Exception failure){update(model,job.cancelled?"Cancelled":"Import failed",job.cancelled?"Choose the package again to retry":"Package could not be installed. Check the file and available storage",0,false);}
        finally{
            StorageMetrics.elapsed(StorageMetrics.INSTALL_NANOS,before);
            job.input=null;JOBS.remove(model.id,job);releaseUri(uri);changed();
        }
    }
    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
    private void transfer(Catalog.Model model, Catalog.ModelFile file, File part, long completed, Job job,boolean mayReplaceIncomplete,ModelFiles.DownloadPaths paths) throws IOException {
        phase(model,job,TRANSFER_PHASE,new File(file.path).getName(),completed,completed,model.bytes(),0,false);
        long requestStarted=android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("LocalVoiceDownload","connect model="+model.id+" file="+file.path);
        URL url = new URL(file.url);
        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; ; redirects++) {
                checkCancelled(job);
                if (!url.getProtocol().equals("https") || url.getUserInfo() != null) throw new IOException("Unsafe download URL");
                connection = (HttpURLConnection) url.openConnection();
                synchronized (job) {
                    checkCancelled(job);
                    job.connection = connection;
                }
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20000); connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                android.util.Log.i("LocalVoiceDownload","response model="+model.id+" status="+code+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-requestStarted));
                if (code >= 300 && code < 400) {
                    if (redirects >= 5) throw new IOException("Too many download redirects");
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid download redirect");
                    url = new URL(url, location); connection.disconnect(); continue;
                }
                if (code != 200) throw new IOException("Download server returned HTTP " + code + ". Retry later");
                long length = connection.getContentLengthLong();
                if (length >= 0 && length != file.bytes) throw new IOException("Unexpected size for " + file.path);
                break;
            }
            try (InputStream input = connection.getInputStream()) {
                checkCancelled(job);
                paths.prepareWrite(file.path);
                if(part.exists()){
                    if(!mayReplaceIncomplete)throw new IOException("Unexpected file in fresh download storage");
                    if(!java.nio.file.Files.isRegularFile(part.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid incomplete model file");
                    if(!part.delete())throw new IOException("Cannot replace incomplete model file");
                }
                DownloadFile.copyStored(input,part,file.bytes,() -> job.cancelled,count -> phase(model,job,TRANSFER_PHASE,new File(file.path).getName(),completed+count,completed+count,model.bytes(),0,false));
            }
        } catch(IOException error){
            android.util.Log.i("LocalVoiceDownload","failure model="+model.id+" type="+error.getClass().getSimpleName()+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-requestStarted));throw error;
        } finally {
            if (connection != null) connection.disconnect();
            job.connection = null;
            if (job.cancelled) try{paths.checked(file.path).delete();}catch(IOException invalidPath){/* Never clean up through a changed parent. */}
        }
    }
}
