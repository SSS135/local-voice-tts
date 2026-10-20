package org.localvoice.tts.backend;

import java.util.Arrays;

/**
 * NeuTTS streaming decode ({@code NeuTTS._infer_stream_ggml}, neutts ac69851f): every chunk of new codes (plus 5
 * lookforward) decodes a window with lookback codes before it, keeps chunk + 2 overlap frames and linearly
 * cross-fades them ({@code _linear_overlap_add}). Upstream uses 25-code chunks with 50 lookback; here the first chunk
 * is 25 codes (first audio) and later chunks may be longer, which decodes fewer frames per second of audio. The
 * overlap-add is incremental but sums the same weighted frames in the same order.
 */
final class NeuTtsStream {

    static final int HOP = 480, FIRST_CHUNK = 25, LOOKFORWARD = 5, OVERLAP = 1;
    /** Upstream's constants: chunk 25, lookback 50. */
    static final int UPSTREAM_CHUNK = 25, UPSTREAM_LOOKBACK = 50;

    private int[] tokens;
    private final int referenceLength, chunk, lookback;
    private int size, decodedTokens;
    private float[] tailAudio = new float[0], tailWeight = new float[0];  // pending cross-fade from decodedSamples on

    NeuTtsStream(int[] referenceCodes) { this(referenceCodes, UPSTREAM_CHUNK, UPSTREAM_LOOKBACK); }

    /** Reference codes come first: they are decode context (lookback) for the first chunk. */
    NeuTtsStream(int[] referenceCodes, int chunk, int lookback) {
        if (chunk < FIRST_CHUNK || lookback < 0) throw new IllegalArgumentException("chunk " + chunk + ", lookback " + lookback);
        this.chunk = chunk;
        this.lookback = lookback;
        tokens = Arrays.copyOf(referenceCodes, referenceCodes.length + 1024);
        size = decodedTokens = referenceLength = referenceCodes.length;
    }

    /** Codes generated so far (excluding the reference). */
    int generated() { return size - referenceLength; }

    /** A decode job: codes for NeuCodec and which decoded samples to keep. Mix results in creation order. */
    static final class Window {
        final int[] codes; final int from, to, emit;
        Window(int[] codes, int from, int to, int emit) { this.codes = codes; this.from = from; this.to = to; this.emit = emit; }
    }

    /** Appends one generated code; returns the window to decode when a chunk completes, else null. */
    Window add(int code) {
        if (size == tokens.length) tokens = Arrays.copyOf(tokens, size * 2);
        tokens[size++] = code;
        int length = decodedTokens == referenceLength ? FIRST_CHUNK : chunk;
        if (size - decodedTokens < length + LOOKFORWARD) return null;
        int start = Math.max(decodedTokens - lookback - OVERLAP, 0);
        int end = Math.min(decodedTokens + length + LOOKFORWARD + OVERLAP, size);
        int sampleStart = (decodedTokens - start) * HOP;
        decodedTokens += length;
        return new Window(Arrays.copyOfRange(tokens, start, end), sampleStart, sampleStart + (length + 2 * OVERLAP) * HOP, length * HOP);
    }

    /** Upstream's final, non-constant chunk over the remaining codes, or null. */
    Window finish() {
        int remaining = size - decodedTokens;
        if (remaining <= 0) return null;
        int start = Math.max(size - (lookback + OVERLAP + remaining), 0);
        int sampleStart = Math.max((size - start - remaining - OVERLAP) * HOP, 0);
        decodedTokens = size;
        return new Window(Arrays.copyOfRange(tokens, start, size), sampleStart, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** Cross-fades a decoded window into the output; returns the audio that is now final. */
    float[] mix(Window window, float[] decoded) {
        return overlapAdd(slice(decoded, window.from, window.to), window.emit);
    }

    /** Adds a frame at the current output position; emits [0, emit) of the cross-faded result and keeps the rest. */
    private float[] overlapAdd(float[] frame, int emit) {
        int length = Math.max(frame.length, tailAudio.length);
        float[] audio = Arrays.copyOf(tailAudio, length), weight = Arrays.copyOf(tailWeight, length);
        for (int i = 0; i < frame.length; i++) {
            float t = (i + 1) / (float) (frame.length + 1);
            float w = 0.5f - Math.abs(t - 0.5f);
            audio[i] += w * frame[i];
            weight[i] += w;
        }
        int n = Math.min(emit, length);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = weight[i] > 0 ? audio[i] / weight[i] : 0f;
        tailAudio = Arrays.copyOfRange(audio, n, length);
        tailWeight = Arrays.copyOfRange(weight, n, length);
        return out;
    }

    /** Python slice semantics for non-negative bounds. */
    private static float[] slice(float[] audio, int from, int to) {
        int a = Math.min(from, audio.length), b = Math.min(Math.max(to, a), audio.length);
        return Arrays.copyOfRange(audio, a, b);
    }
}
