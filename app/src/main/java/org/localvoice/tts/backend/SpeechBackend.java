package org.localvoice.tts.backend;

import java.io.File;
import java.util.Map;

/** A loaded backend emits mono float PCM synchronously on its synthesis caller thread. */
public interface SpeechBackend extends AutoCloseable {
    interface Host {
        boolean isNetworkAvailable();
        void awaitNetworkOrDelay(long millis,Cancellation cancellation) throws InterruptedException;
        void reportStatus(String phase,int attempts);
    }
    default void setHost(Host host) {}
    interface Cancellation { boolean isCancelled(); }
    interface PcmSink {
        boolean write(float[] samples);
        /**
         * Exact word timing, for adapters whose engine reports it: text[start,end) of the synthesize() text begins
         * at this output sample (counted from the call's first written sample). Call before writing that sample.
         */
        default void word(long sample, int start, int end) {}
    }
    /** Per-request settings beyond synthesize()'s arguments. */
    final class Options {
        public static final Options DEFAULT = new Options(1f, 0, -1, true);
        /** Pitch factor (1 = unchanged), for adapters that render pitch natively; the service shapes the rest. */
        public final float pitch;
        /** Network adapters: retry budget per server turn in ms (0 = unbounded) and retries after the first attempt (-1 = unbounded). */
        public final long networkTimeoutMs;
        public final int networkRetries;
        /** False: waiting for the device network is unlimited and does not spend networkTimeoutMs. */
        public final boolean boundOffline;
        public Options(float pitch, long networkTimeoutMs, int networkRetries, boolean boundOffline) {
            this.pitch = pitch; this.networkTimeoutMs = networkTimeoutMs; this.networkRetries = networkRetries;
            this.boundOffline = boundOffline;
        }
    }
    /** Called on the synthesis thread before each synthesize() with that request's options. */
    default void options(Options options) {}
    void load(File directory, Map<String,String> config) throws Exception;
    int sampleRate();
    void synthesize(String text, String languageTag, int speakerId, float speed,
                    Cancellation cancellation, PcmSink sink) throws Exception;
    /** True when one synthesize() call writes PCM progressively during its compute, not once at its end. */
    default boolean streams() { return false; }
    /** True when a streaming adapter reports {@link PcmSink#word} for every word before writing its first sample. */
    default boolean timesWords() { return false; }
    /** May be called concurrently; must not free inference resources. */
    default void cancel() {}
    @Override void close();
}
