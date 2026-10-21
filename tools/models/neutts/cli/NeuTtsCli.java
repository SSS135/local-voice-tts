package org.localvoice.tts;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.localvoice.tts.backend.NeuTtsBackend;

/**
 * Device CLI over the app's own NeuTTS code (no APK install): run with app_process, see tools/models/neutts/REPRODUCE.md.
 *   phon <modelDir> <textFile>                      raw eSpeak phonemes per line (parity with phoneme_parity.py)
 *   say  <modelDir> <voice> <textFile> <out.wav> [gen/batch/decoder threads, e.g. 2/4/2] [key=value config, e.g. streamChunk=50]
 *   gen  <modelDir> <tokens> [threads]                backbone tokens/s without decoding
 *   head <modelDir> <steps> [seeds]                  speech-only head logits vs the full head (exactness check)
 * "say" splits text like the service (SpeechPlan.segments) and reports first-audio latency, RTF and the playback
 * stall a real-time consumer would hear (audio arriving after the previous audio finished playing).
 */
public final class NeuTtsCli {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[1]);
        Map<String,String> config = new HashMap<>();
        config.put("model", "neutts-nano-Q4_0.gguf"); config.put("decoder", "neucodec-decoder-int8-conv.onnx");
        config.put("espeakData", "espeak-ng-data"); config.put("voiceDir", "voices");
        config.put("voices", "dave,jo,emily,paul,sophie,steven");
        if (args[0].equals("phon")) {
            NeuTtsBackend backend = new NeuTtsBackend();
            backend.load(dir, config);
            java.lang.reflect.Method phonemes = Class.forName("org.localvoice.tts.backend.NeuTtsNative").getDeclaredMethod("phonemes", byte[].class);
            phonemes.setAccessible(true);
            for (String line : Files.readAllLines(new File(args[2]).toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                System.out.println(new String((byte[]) phonemes.invoke(null, (Object) line.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            }
            backend.close();
            return;
        }
        if (args[0].equals("gen")) {  // gen <modelDir> <tokens> [threads]: backbone tokens/s alone (no decode)
            if (args.length > 3) threads(config, args[3]);
            NeuTtsBackend backend = new NeuTtsBackend();
            backend.load(dir, config);
            Class<?> nat = Class.forName("org.localvoice.tts.backend.NeuTtsNative");
            java.lang.reflect.Field handleField = NeuTtsBackend.class.getDeclaredField("handle");
            handleField.setAccessible(true);
            long handle = handleField.getLong(backend);
            java.lang.reflect.Method prefill = nat.getDeclaredMethod("prefill", long.class, byte[].class, int.class);
            java.lang.reflect.Method next = nat.getDeclaredMethod("next", long.class, int.class);
            prefill.setAccessible(true); next.setAccessible(true);
            StringBuilder prompt = new StringBuilder("user: Convert the text to speech:<|TEXT_PROMPT_START|>hˈɛloʊ<|TEXT_PROMPT_END|>\nassistant:<|SPEECH_GENERATION_START|>");
            for (int i = 0; i < 600; i++) prompt.append("<|speech_").append(i * 97 % 65536).append("|>");
            long t = System.nanoTime();
            prefill.invoke(null, handle, prompt.toString().getBytes(StandardCharsets.UTF_8), 1);
            long p = System.nanoTime();
            int n = Integer.parseInt(args[2]);
            for (int i = 0; i < n; i++) next.invoke(null, handle, -100);
            long g = System.nanoTime();
            System.out.printf("prefill_ms %.0f gen_tok_s %.1f%n", (p - t) / 1e6, n / ((g - p) / 1e9));
            backend.close();
            return;
        }
        if (args[0].equals("head")) {  // head <modelDir> <steps> [seeds]: speech-only head vs full head (dave, fixed text)
            NeuTtsBackend backend = new NeuTtsBackend();
            backend.load(dir, config);
            Class<?> nat = Class.forName("org.localvoice.tts.backend.NeuTtsNative"), front = Class.forName("org.localvoice.tts.backend.NeuTtsFrontend");
            java.lang.reflect.Field handleField = NeuTtsBackend.class.getDeclaredField("handle"), voicesField = NeuTtsBackend.class.getDeclaredField("voices");
            handleField.setAccessible(true); voicesField.setAccessible(true);
            Object dave = ((List<?>) voicesField.get(backend)).get(0);
            java.lang.reflect.Field codes = dave.getClass().getDeclaredField("codes"), phones = dave.getClass().getDeclaredField("phones");
            codes.setAccessible(true); phones.setAccessible(true);
            java.lang.reflect.Method espeak = NeuTtsBackend.class.getDeclaredMethod("espeak", String.class);
            java.lang.reflect.Method phonesOf = front.getDeclaredMethod("phones", String.class, java.util.function.UnaryOperator.class);
            java.lang.reflect.Method prompt = front.getDeclaredMethod("prompt", String.class, String.class, int[].class);
            java.lang.reflect.Method check = nat.getDeclaredMethod("checkHead", long.class, byte[].class, int.class, int.class);
            espeak.setAccessible(true); phonesOf.setAccessible(true); prompt.setAccessible(true); check.setAccessible(true);
            java.util.function.UnaryOperator<String> g2p = t -> { try { return (String) espeak.invoke(null, t); } catch (Exception x) { throw new RuntimeException(x); } };
            String target = (String) phonesOf.invoke(null, "The quick brown fox jumps over the lazy dog, then rests by the river.", g2p);
            byte[] text = ((String) prompt.invoke(null, phones.get(dave), target, codes.get(dave))).getBytes(StandardCharsets.UTF_8);
            int seeds = args.length > 3 ? Integer.parseInt(args[3]) : 3;
            for (int seed = 1; seed <= seeds; seed++) {
                float[] r = (float[]) check.invoke(null, handleField.getLong(backend), text, Integer.parseInt(args[2]), seed);
                System.out.printf("seed %d max_abs_logit_diff %.3g full_argmax_outside %d full_top50_outside %d steps %d%n", seed, r[0], (int) r[1], (int) r[2], (int) r[3]);
            }
            backend.close();
            return;
        }
        int voice = Integer.parseInt(args[2]);
        String text = new String(Files.readAllBytes(new File(args[3]).toPath()), StandardCharsets.UTF_8).strip();
        if (args.length > 5) threads(config, args[5]);
        for (int i = 6; i < args.length; i++) config.put(args[i].substring(0, args[i].indexOf('=')), args[i].substring(args[i].indexOf('=') + 1));
        long t0 = System.nanoTime();
        NeuTtsBackend backend = new NeuTtsBackend();
        backend.load(dir, config);
        long loaded = System.nanoTime();
        System.out.println("load_ms " + (loaded - t0) / 1_000_000);
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        long[] first = {0}, samples = {0};
        double[] playedUntil = {0}, stall = {0};
        long start = System.nanoTime();
        List<SpeechPlan.Segment> segments = SpeechPlan.segments(text, true, Integer.parseInt(config.getOrDefault("segmentLimit", Integer.toString(SpeechPlan.LIMIT))));
        for (SpeechPlan.Segment segment : segments) {
            backend.synthesize(text.substring(segment.start, segment.end), "en-US", voice, 1f, () -> false, block -> {
                double now = (System.nanoTime() - start) / 1e6;
                if (first[0] == 0) { first[0] = System.nanoTime(); playedUntil[0] = now; }
                if (now > playedUntil[0]) { stall[0] += now - playedUntil[0]; playedUntil[0] = now; }
                playedUntil[0] += block.length * 1000.0 / NeuTtsBackend.SAMPLE_RATE;
                for (float f : block) {
                    int s = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
                    pcm.write(s & 0xff); pcm.write((s >> 8) & 0xff);
                }
                samples[0] += block.length;
                return true;
            });
        }
        long end = System.nanoTime();
        double audioMs = samples[0] * 1000.0 / NeuTtsBackend.SAMPLE_RATE, wallMs = (end - start) / 1e6;
        System.out.printf("segments %d first_audio_ms %.0f audio_ms %.0f wall_ms %.0f rtf %.3f stall_ms %.0f%n", segments.size(),
            (first[0] - start) / 1e6, audioMs, wallMs, wallMs / audioMs, stall[0]);
        writeWav(new File(args[4]), pcm.toByteArray());
        backend.close();
    }

    private static void threads(Map<String,String> config, String spec) {
        String[] t = spec.split("/");
        config.put("numThreads", t[0]);
        if (t.length > 1) config.put("batchThreads", t[1]);
        if (t.length > 2) config.put("decoderThreads", t[2]);
    }

    private static void writeWav(File file, byte[] pcm) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            int rate = NeuTtsBackend.SAMPLE_RATE;
            out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes(36 + pcm.length)); out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16)); out.writeShort(Short.reverseBytes((short) 1)); out.writeShort(Short.reverseBytes((short) 1));
            out.writeInt(Integer.reverseBytes(rate)); out.writeInt(Integer.reverseBytes(rate * 2));
            out.writeShort(Short.reverseBytes((short) 2)); out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data"); out.writeInt(Integer.reverseBytes(pcm.length)); out.write(pcm);
        }
    }
}
