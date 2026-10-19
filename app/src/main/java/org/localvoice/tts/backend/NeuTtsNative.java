package org.localvoice.tts.backend;

/** JNI surface of libneutts_jni.so (app/src/main/cpp/neutts_jni.cpp). Text crosses as UTF-8 bytes. */
final class NeuTtsNative {
    static { System.loadLibrary("neutts_jni"); }
    private NeuTtsNative() {}

    static native long open(String gguf, String decoder, String espeakData, String espeakVoice, int threads, int batchThreads,
                            int decoderThreads, int contextSize);
    /** Id of a piece that tokenizes to exactly one token, else -1. */
    static native int token(long handle, String piece);
    static native int contextSize(long handle);
    /** Raw espeak_TextToPhonemes clauses joined by spaces (phonemizer EspeakWrapper.text_to_phonemes). */
    static native byte[] phonemes(byte[] utf8);
    /** {prompt tokens, tokens reused from the KV cache}; null when cancelled. */
    static native int[] prefill(long handle, byte[] utf8Prompt, int seed);
    /** Next sampled token; {@code stop} is returned without evaluation; -1 context full, -2 cancelled. */
    static native int next(long handle, int stop);
    /** Aborts an in-flight prefill/next; safe while another thread is inside them. */
    static native void cancel(long handle);
    /** NeuCodec decode of int32 codes [1,1,F] to 24 kHz audio. */
    static native float[] decode(long handle, int[] codes);
    static native void close(long handle);
}
