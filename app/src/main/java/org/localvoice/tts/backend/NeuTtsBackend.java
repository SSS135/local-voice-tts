package org.localvoice.tts.backend;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * NeuTTS Nano (Neuphonic): Q4_0 GGUF backbone via llama.cpp, NeuCodec int8 ONNX decoder, eSpeak NG en-us phonemes.
 * Each voice is a pre-encoded reference (codes + transcript) from the neutts repository samples.
 * Audio streams per 25-code chunk; NeuCodec decodes each chunk on its own thread while the backbone keeps generating,
 * and PCM is written in order on the synthesis thread. Speed uses Sonic time stretching (no native rate control).
 */
public final class NeuTtsBackend implements SpeechBackend {
    public static final int SAMPLE_RATE = 24000;
    static final int CODES = 65536;
    private static final class Voice {
        final int[] codes; final String text; String phones;
        Voice(int[] codes, String text) { this.codes = codes; this.text = text; }
    }

    private final Object handleLock = new Object();
    private long handle;
    private int speechBase, endToken;
    private final List<Voice> voices = new ArrayList<>();
    private ExecutorService decoderThread;

    @Override public void load(File directory, Map<String,String> config) throws Exception {
        // Pixel 7 Pro, decode overlapped with generation: 2/4/3 gave RTF 1.05 vs 1.18 (1/4/3) and 1.25 (4/4/4 sequential).
        int threads = threads(config, "numThreads", 2), batchThreads = threads(config, "batchThreads", 4);
        int decoderThreads = threads(config, "decoderThreads", 3);
        int context = Integer.parseInt(config.getOrDefault("contextSize", "2048"));
        for (String name : required(config, "voices").split(",")) {
            String prefix = required(config, "voiceDir") + "/" + name.strip();
            voices.add(new Voice(readCodes(file(directory, prefix + ".codes")),
                new String(Files.readAllBytes(file(directory, prefix + ".txt").toPath()), StandardCharsets.UTF_8).strip()));
        }
        long started = System.nanoTime();
        long opened = NeuTtsNative.open(file(directory, required(config, "model")).getPath(),
            file(directory, required(config, "decoder")).getPath(), file(directory, required(config, "espeakData")).getPath(),
            config.getOrDefault("espeakVoice", "en-us"), threads, batchThreads, decoderThreads, context);
        synchronized (handleLock) { handle = opened; }
        decoderThread = Executors.newSingleThreadExecutor(task -> new Thread(task, "NeuCodec"));
        speechBase = NeuTtsNative.token(opened, "<|speech_0|>");
        endToken = NeuTtsNative.token(opened, "<|SPEECH_GENERATION_END|>");
        if (speechBase < 0 || endToken < 0 || NeuTtsNative.token(opened, "<|speech_" + (CODES - 1) + "|>") != speechBase + CODES - 1)
            throw new IllegalArgumentException("GGUF vocabulary lacks NeuTTS speech tokens");
        for (Voice voice : voices) voice.phones = NeuTtsFrontend.phones(voice.text, NeuTtsBackend::espeak);
        android.util.Log.i("LocalVoiceTiming", "neuttsLoad ms=" + (System.nanoTime() - started) / 1_000_000 + " threads=" + threads
            + "/" + batchThreads + "/" + decoderThreads);
    }

    @Override public int sampleRate() { return SAMPLE_RATE; }

    @Override public boolean streams() { return true; }

    @Override public void synthesize(String text, String languageTag, int speakerId, float speed,
                                     Cancellation cancellation, PcmSink sink) throws Exception {
        if (speakerId < 0 || speakerId >= voices.size()) throw new IllegalArgumentException("Unknown NeuTTS voice " + speakerId);
        Voice voice = voices.get(speakerId);
        String phones = NeuTtsFrontend.phones(text, NeuTtsBackend::espeak);
        if (cancellation.isCancelled() || phones.codePoints().noneMatch(Character::isLetter)) return;
        long started = System.nanoTime();
        int[] prompt = NeuTtsNative.prefill(handle, NeuTtsFrontend.prompt(voice.phones, phones, voice.codes)
            .getBytes(StandardCharsets.UTF_8), ThreadLocalRandom.current().nextInt());
        if (prompt == null) return;
        long prefilled = System.nanoTime(), generating = 0;
        long[] firstAudio = {0}, samples = {0}, decoding = {0};
        NeuTtsStream stream = new NeuTtsStream(voice.codes);
        PcmTimeStretch stretch = new PcmTimeStretch(SAMPLE_RATE, speed);
        ArrayDeque<NeuTtsStream.Window> windows = new ArrayDeque<>();
        ArrayDeque<Future<float[]>> decoded = new ArrayDeque<>();
        try {
            boolean ended = false;
            while (!cancellation.isCancelled()) {
                if (!ended) {
                    long t = System.nanoTime();
                    int token = NeuTtsNative.next(handle, endToken);
                    generating += System.nanoTime() - t;
                    if (token == -2) return;
                    ended = token == endToken || token == -1;
                    int code = token - speechBase;
                    // A non-speech token decodes nothing (upstream would still count it as a frame position).
                    NeuTtsStream.Window window = ended ? stream.finish() : code >= 0 && code < CODES ? stream.add(code) : null;
                    if (window != null) {
                        windows.add(window);
                        decoded.add(decoderThread.submit(() -> {
                            long t0 = System.nanoTime();
                            float[] audio = NeuTtsNative.decode(handle, window.codes);
                            decoding[0] += System.nanoTime() - t0;
                            return audio;
                        }));
                    }
                }
                // Emit finished decodes in order; once generation ended, wait for the rest.
                while (!decoded.isEmpty() && (ended || decoded.peek().isDone())) {
                    float[] audio = stream.mix(windows.poll(), result(decoded.poll()));
                    if (firstAudio[0] == 0) firstAudio[0] = System.nanoTime();
                    samples[0] += audio.length;
                    if (!stretch.write(audio, cancellation, sink)) return;
                }
                if (ended && decoded.isEmpty()) break;
            }
            if (cancellation.isCancelled()) return;
            stretch.finish(cancellation, sink);
        } finally {
            for (Future<float[]> pending : decoded) pending.cancel(false);
            if (!decoded.isEmpty()) result(decoderThread.submit(() -> new float[0]));  // the running decode has returned
        }
        long done = System.nanoTime();
        android.util.Log.i("LocalVoiceTiming", "neutts promptTokens=" + prompt[0] + " reused=" + prompt[1]
            + " prefillMs=" + (prefilled - started) / 1_000_000 + " firstAudioMs=" + (firstAudio[0] - started) / 1_000_000
            + " codes=" + stream.generated() + " audioMs=" + samples[0] * 1000 / SAMPLE_RATE + " genMs=" + generating / 1_000_000
            + " decodeMs=" + decoding[0] / 1_000_000 + " wallMs=" + (done - started) / 1_000_000);
    }

    private static float[] result(Future<float[]> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException failure) {
            throw failure.getCause() instanceof Exception ? (Exception) failure.getCause() : failure;
        }
    }

    @Override public void cancel() {
        synchronized (handleLock) { if (handle != 0) NeuTtsNative.cancel(handle); }
    }

    @Override public void close() {
        if (decoderThread != null) {
            decoderThread.shutdownNow();
            try {
                decoderThread.awaitTermination(1, TimeUnit.MINUTES);  // a native decode must not outlive its session
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            decoderThread = null;
        }
        synchronized (handleLock) {
            if (handle != 0) NeuTtsNative.close(handle);
            handle = 0;
        }
        voices.clear();
    }

    private static String espeak(String text) {
        return new String(NeuTtsNative.phonemes(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    /** Reference codes: whitespace-separated integers in [0, 65536). */
    static int[] readCodes(File file) throws IOException {
        String[] parts = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).strip().split("\\s+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            codes[i] = Integer.parseInt(parts[i]);
            if (codes[i] < 0 || codes[i] >= CODES) throw new IOException("Invalid NeuTTS reference code in " + file.getName());
        }
        return codes;
    }

    private static int threads(Map<String,String> config, String key, int fallback) {
        return Math.max(1, Math.min(8, Integer.parseInt(config.getOrDefault(key, Integer.toString(fallback)))));
    }

    private static String required(Map<String,String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing NeuTTS config: " + key);
        return value;
    }

    private static File file(File directory, String relative) {
        File file = new File(directory, relative);
        if (!file.isFile() && !file.isDirectory()) throw new IllegalArgumentException("Missing NeuTTS file: " + relative);
        return file;
    }
}
