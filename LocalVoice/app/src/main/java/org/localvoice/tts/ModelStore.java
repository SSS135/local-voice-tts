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
    public interface Listener { void changed(); }
    public static final class Progress {
        public final String state, detail;
        public final long bytes, total;
        public final boolean running;
        Progress(String state, String detail, long bytes, long total, boolean running) {
            this.state=state; this.detail=detail; this.bytes=bytes; this.total=total; this.running=running;
        }
    }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<String, Progress> PROGRESS = new ConcurrentHashMap<>();
    private static final Map<String, String> VERIFIED = new HashMap<>();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Set<String> REMOVING = new HashSet<>();
    private static final Object OPERATIONS = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long RESERVE = 32L * 1024 * 1024;
    private final File root;
    private final VoicePreferences prefs;
    private final Context context;

    private static final class Job {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        boolean activated;
    }

    public ModelStore(Context context) {
        this.context=context.getApplicationContext();
        root = new File(context.getApplicationContext().getNoBackupFilesDir(), "models");
        prefs = new VoicePreferences(context.getApplicationContext());
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
        Catalog.Model model = Catalog.findModel(id);
        if (model == null) return false;
        if (model.network) return prefs.onlineEnabled(id);
        File dir = directory(model, false);
        if (!ModelFiles.receiptMatches(dir, model.revision)) return false;
        try {
            for (Catalog.ModelFile file : model.files) {
                File local = ModelFiles.child(dir, file.path);
                if (!local.isFile() || local.length() != file.bytes) return false;
            }
            return true;
        } catch (IOException e) { return false; }
    }
    /** Call on the inference worker before opening a model. Never on a binder/UI thread. */
    public void verifyInstalled(String id) throws IOException {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null || !isInstalled(id)) throw new IOException("Download this model in LocalVoice first");
        if (model.network) return;
        synchronized(VERIFIED) {
            String key=directory(model,false).getAbsolutePath();
            StringBuilder stamp=new StringBuilder(model.revision);
            for(Catalog.ModelFile file:model.files) {
                File local=ModelFiles.child(directory(model,false),file.path);
                stamp.append('|').append(local.length()).append(':').append(local.lastModified());
            }
            if(stamp.toString().equals(VERIFIED.get(key))) return;
            for (Catalog.ModelFile file : model.files) {
                if (!ModelFiles.verified(ModelFiles.child(directory(model, false), file.path), file.bytes, file.sha256)) {
                    new File(directory(model, false), "installed.manifest").delete();
                    VERIFIED.remove(key); changed();
                    throw new IOException("Model is damaged. Download it again in LocalVoice");
                }
            }
            VERIFIED.put(key,stamp.toString());
        }
    }
    public List<Catalog.VoiceSpec> installedVoices() {
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
        synchronized(OPERATIONS) {
            if(!REMOVING.add(id)) return;
            cancel(id);
            WORK.execute(() -> {
                try {
                    ModelFiles.remove(directory(model,true));
                    if(!cacheOnly) {
                        if(model.network) prefs.enableOnline(id,false);
                        else ModelFiles.remove(directory(model,false));
                    }
                    PROGRESS.remove(id); broadcastVoices();
                } catch(IOException error) { update(model,"Storage action failed",safeError(error),0,false); }
                finally { synchronized(OPERATIONS) { REMOVING.remove(id); } changed(); }
            });
        }
    }
    public void addListener(Listener listener) { LISTENERS.add(listener); }
    public void removeListener(Listener listener) { LISTENERS.remove(listener); }
    private static void changed() { MAIN.post(() -> { for (Listener listener : LISTENERS) listener.changed(); }); }
    public Progress progress(String id) {
        Catalog.Model model = Catalog.findModel(id);
        Progress value = PROGRESS.get(id);
        if (value != null && (!value.state.equals("Installed") || isInstalled(id))) return value;
        return new Progress(isInstalled(id) ? "Installed" : "Not downloaded", "", 0, model == null ? 0 : model.bytes(), false);
    }
    private static void update(Catalog.Model model, String state, String detail, long bytes, boolean running) {
        PROGRESS.put(model.id, new Progress(state, detail, bytes, model.bytes(), running));
        changed();
    }
    public void cancel(String id) {
        Job job = JOBS.get(id);
        if (job == null) return;
        synchronized (job) {
            if (job.activated) return;
            job.cancelled = true;
            HttpURLConnection connection = job.connection;
            if (connection != null) connection.disconnect();
        }
    }
    /** Explicit user action. Verified staging files are reused after a retry or process restart. */
    public void download(String id) {
        Catalog.Model model = Catalog.findModel(id);
        if (model == null || model.network || !model.available || isInstalled(id)) return;
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
        long completed = 0;
        File staging = directory(model, true);
        try {
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private model storage");
            if (!staging.isDirectory() && !staging.mkdirs()) throw new IOException("Cannot create download storage");
            for (Catalog.ModelFile file : model.files) {
                checkCancelled(job);
                File local = ModelFiles.child(staging, file.path);
                if (ModelFiles.verified(local, file.bytes, file.sha256)) completed += file.bytes;
            }
            DownloadFile.requireSpace(new StatFs(root.getPath()).getAvailableBytes(),model.bytes()-completed,RESERVE);
            for (Catalog.ModelFile file : model.files) {
                checkCancelled(job);
                File local = ModelFiles.child(staging, file.path);
                if (ModelFiles.verified(local, file.bytes, file.sha256)) continue;
                if (!local.getParentFile().isDirectory() && !local.getParentFile().mkdirs()) throw new IOException("Cannot create model folder");
                File part = ModelFiles.child(staging, file.path + ".part");
                transfer(model, file, part, completed, job);
                checkCancelled(job);
                update(model, "Verifying", file.path, completed + file.bytes, true);
                if (!ModelFiles.verified(part, file.bytes, file.sha256)) {
                    part.delete();
                    throw new IOException("Checksum mismatch for " + file.path + ". Retry the download");
                }
                if (local.exists() && !local.delete()) throw new IOException("Cannot replace damaged cached file");
                if (!part.renameTo(local)) throw new IOException("Cannot save verified file");
                completed += file.bytes;
            }
            synchronized (job) {
                checkCancelled(job);
                ModelFiles.writeReceipt(staging, model.revision);
                File target = directory(model, false);
                if (target.exists()) ModelFiles.remove(target);
                ModelFiles.activate(staging, target);
                job.activated = true;
            }
            update(model, "Installed", "Ready for offline speech", completed, false);
            broadcastVoices();
        } catch (Exception error) {
            update(model, job.cancelled ? "Cancelled" : "Download failed", job.cancelled ? "Verified files kept for retry" : safeError(error), completed, false);
        } finally {
            HttpURLConnection connection = job.connection;
            if (connection != null) connection.disconnect();
            JOBS.remove(model.id, job);
            changed();
        }
    }
    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
    private void transfer(Catalog.Model model, Catalog.ModelFile file, File part, long completed, Job job) throws IOException {
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
            long[] lastUpdate = {0};
            try (InputStream input = connection.getInputStream()) {
                DownloadFile.copy(input,part,file.bytes,() -> job.cancelled,count -> {
                    if(System.currentTimeMillis()-lastUpdate[0]>200) {
                        update(model,"Downloading",file.path,completed+count,true); lastUpdate[0]=System.currentTimeMillis();
                    }
                });
            }
        } finally {
            if (connection != null) connection.disconnect();
            job.connection = null;
            if (job.cancelled) part.delete();
        }
    }
}
