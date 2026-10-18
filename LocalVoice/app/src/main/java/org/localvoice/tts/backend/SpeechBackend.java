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
    interface PcmSink { boolean write(float[] samples); }
    void load(File directory, Map<String,String> config) throws Exception;
    int sampleRate();
    void synthesize(String text, String languageTag, int speakerId, float speed,
                    Cancellation cancellation, PcmSink sink) throws Exception;
    /** May be called concurrently; must not free inference resources. */
    default void cancel() {}
    @Override void close();
}
