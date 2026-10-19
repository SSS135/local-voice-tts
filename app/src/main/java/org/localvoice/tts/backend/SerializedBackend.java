package org.localvoice.tts.backend;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
/** Owns one model; close/load/synthesis share this monitor. Cancellation never waits for it. */
public final class SerializedBackend implements AutoCloseable {
    private final Function<String,SpeechBackend> factory;
    private final AtomicLong generation = new AtomicLong();
    private volatile SpeechBackend backend;
    private String modelId;
    private String backendKind;
    private File modelDirectory;
    private Map<String,String> loadedConfig;
    public SerializedBackend() { this(BackendRegistry::create); }
    public SerializedBackend(Function<String,SpeechBackend> factory) { this.factory = java.util.Objects.requireNonNull(factory); }
    public long begin() { return generation.incrementAndGet(); }
    public boolean cancelled(long token) { return generation.get() != token; }
    public void cancel() { generation.incrementAndGet(); SpeechBackend b = backend; if (b != null) b.cancel(); }
    public synchronized SpeechBackend load(String id,String kind,File directory,Map<String,String> config) throws Exception {
        if (!id.equals(modelId) || !kind.equals(backendKind) || !directory.equals(modelDirectory) || !config.equals(loadedConfig)) {
            closeLoaded();
            SpeechBackend next = factory.apply(kind);
            try { next.load(directory,config); } catch (Exception|LinkageError e) { next.close(); throw e; }
            backend = next; modelId = id; backendKind = kind; modelDirectory = directory;
            loadedConfig = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(config));
        }
        return backend;
    }
    public void execute(long token,String id,String kind,File directory,Map<String,String> config,
            String text,String language,int sid,float speed,Ready ready,SpeechBackend.PcmSink sink) throws Exception {
        execute(token,id,kind,directory,config,text,language,sid,speed,SpeechBackend.Options.DEFAULT,ready,sink);
    }
    public synchronized void execute(long token,String id,String kind,File directory,Map<String,String> config,
            String text,String language,int sid,float speed,SpeechBackend.Options options,Ready ready,SpeechBackend.PcmSink sink) throws Exception {
        if (cancelled(token)) return;
        SpeechBackend loaded = load(id,kind,directory,config);
        if (cancelled(token)) return;
        if (cancelled(token)) return; ready.onReady(loaded.sampleRate());
        loaded.options(options);
        boolean[] accepting = {true};
        loaded.synthesize(text,language,sid,speed,()->cancelled(token),new SpeechBackend.PcmSink() {
            @Override public boolean write(float[] samples) {
                if (!accepting[0] || cancelled(token)) return false;
                accepting[0] = sink.write(samples); return accepting[0];
            }
            @Override public void word(long sample,int start,int end) { if (accepting[0] && !cancelled(token)) sink.word(sample,start,end); }
        });
    }
    /** Whether the loaded adapter streams; call from Ready.onReady on the executing thread. */
    public synchronized boolean streams() { return backend != null && backend.streams(); }
    public interface Ready { void onReady(int sampleRate) throws Exception; }
    private void closeLoaded() { if (backend != null) backend.close(); backend = null; modelId = null; backendKind = null; modelDirectory = null; loadedConfig = null; }
    @Override public void close() { cancel(); synchronized(this) { closeLoaded(); } }
}
