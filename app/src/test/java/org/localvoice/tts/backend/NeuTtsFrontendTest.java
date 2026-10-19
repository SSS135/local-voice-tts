package org.localvoice.tts.backend;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class NeuTtsFrontendTest {
    private static final File FIXTURE = new File("../tools/models/neutts/frontend-parity.json");

    /** Upstream phonemizer output (fixture from phoneme_parity.py) with eSpeak replaced by its recorded raw output. */
    @Test public void phonesMatchUpstreamPhonemizer() throws Exception {
        JSONObject fixture = new JSONObject(new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8));
        JSONObject raw = fixture.getJSONObject("raw");
        JSONArray cases = fixture.getJSONArray("cases");
        int deviations = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String phones = NeuTtsFrontend.phones(c.getString("text"), chunk -> {
                assertTrue("unrecorded eSpeak chunk: " + chunk, raw.has(chunk));
                return raw.optString(chunk);
            });
            assertEquals(c.getString("text"), c.getString("phones"), phones);
            if (!phones.equals(c.getString("upstreamPhones"))) deviations++;
        }
        assertEquals("only the decimal-split fix may differ from upstream", 1, deviations);
    }

    @Test public void promptFollowsGgmlTemplate() {
        assertEquals("user: Convert the text to speech:<|TEXT_PROMPT_START|>ref ɹˈɛf hˈɛloʊ<|TEXT_PROMPT_END|>\n"
            + "assistant:<|SPEECH_GENERATION_START|><|speech_0|><|speech_65535|><|speech_42|>",
            NeuTtsFrontend.prompt("ref ɹˈɛf", "hˈɛloʊ", new int[]{0, 65535, 42}));
    }

    @Test public void referenceCodesParseAndRejectOutOfRange() throws Exception {
        File file = File.createTempFile("voice", ".codes");
        try {
            Files.write(file.toPath(), "1 2\n65535 \n".getBytes(StandardCharsets.US_ASCII));
            assertArrayEquals(new int[]{1, 2, 65535}, NeuTtsBackend.readCodes(file));
            Files.write(file.toPath(), "1 65536".getBytes(StandardCharsets.US_ASCII));
            assertThrows(java.io.IOException.class, () -> NeuTtsBackend.readCodes(file));
        } finally {
            assertTrue(file.delete());
        }
    }

    /** Incremental overlap-add equals upstream _infer_stream_ggml, which re-runs _linear_overlap_add over all frames. */
    @Test public void streamMatchesUpstreamChunking() throws Exception {
        for (int reference : new int[]{1, 10, 51, 372})
            for (int generated : new int[]{0, 1, 29, 30, 31, 54, 55, 56, 100, 333}) {
                int[] ref = codes(reference, 7);
                int[] gen = codes(generated, 11);
                NeuTtsStream stream = new NeuTtsStream(ref);
                List<float[]> blocks = new ArrayList<>();
                // Decode out of band (as the backend's decoder thread does); mixing stays in window order.
                List<NeuTtsStream.Window> windows = new ArrayList<>();
                for (int code : gen) { NeuTtsStream.Window w = stream.add(code); if (w != null) windows.add(w); }
                NeuTtsStream.Window last = stream.finish();
                if (last != null) windows.add(last);
                for (NeuTtsStream.Window w : windows) blocks.add(stream.mix(w, fakeDecode(w.codes)));
                float[] expected = upstream(ref, gen);
                float[] actual = concat(blocks);
                assertEquals(reference + "/" + generated, expected.length, actual.length);
                for (int i = 0; i < expected.length; i++) assertEquals(reference + "/" + generated + "@" + i, expected[i], actual[i], 1e-5f);
                assertEquals(generated, stream.generated());
            }
    }

    private static int[] codes(int n, int seed) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = (i * 7919 + seed * 104729) % 65536;
        return out;
    }

    /** NeuCodec shape: F codes -> 480*(F-1) samples, each depending on its window position and code. */
    private static float[] fakeDecode(int[] codes) {
        float[] out = new float[Math.max(0, codes.length - 1) * 480];
        for (int i = 0; i < out.length; i++) out[i] = (float) Math.sin(codes[i / 480] * 0.001 + i * 0.01);
        return out;
    }

    /** Literal port of neutts.py's streaming loop (token_cache, audio_cache, full re-overlap-add each chunk). */
    private static float[] upstream(int[] ref, int[] gen) throws Exception {
        final int hop = 480, chunk = 25, look = 5, back = 50, ov = 1, stride = chunk * hop;
        List<Integer> cache = new ArrayList<>();
        for (int c : ref) cache.add(c);
        List<float[]> audio = new ArrayList<>(), out = new ArrayList<>();
        int samples = 0, decoded = ref.length;
        for (int g : gen) {
            cache.add(g);
            if (cache.size() - decoded >= chunk + look) {
                int start = Math.max(decoded - back - ov, 0), end = decoded + chunk + look + ov;
                int s0 = (decoded - start) * hop, s1 = s0 + (chunk + 2 * ov) * hop;
                float[] recon = fakeDecode(sub(cache, start, Math.min(end, cache.size())));
                audio.add(Arrays.copyOfRange(recon, Math.min(s0, recon.length), Math.min(s1, recon.length)));
                float[] all = overlapAdd(audio, stride);
                int newEnd = audio.size() * stride;
                out.add(Arrays.copyOfRange(all, samples, newEnd));
                samples = newEnd;
                decoded += chunk;
            }
        }
        int remaining = cache.size() - decoded;
        if (remaining > 0) {
            int start = Math.max(cache.size() - (back + ov + remaining), 0);
            int s0 = (cache.size() - start - remaining - ov) * hop;
            float[] recon = fakeDecode(sub(cache, start, cache.size()));
            audio.add(Arrays.copyOfRange(recon, Math.min(s0, recon.length), recon.length));
            float[] all = overlapAdd(audio, stride);
            out.add(Arrays.copyOfRange(all, Math.min(samples, all.length), all.length));
        }
        return concat(out);
    }

    private static float[] overlapAdd(List<float[]> frames, int stride) {
        int total = 0;
        for (int i = 0; i < frames.size(); i++) total = Math.max(total, stride * i + frames.get(i).length);
        double[] sum = new double[total], weight = new double[total];
        for (int f = 0; f < frames.size(); f++) {
            float[] frame = frames.get(f);
            for (int i = 0; i < frame.length; i++) {
                double t = (i + 1) / (double) (frame.length + 1), w = 0.5 - Math.abs(t - 0.5);
                sum[f * stride + i] += w * frame[i];
                weight[f * stride + i] += w;
            }
        }
        float[] out = new float[total];
        for (int i = 0; i < total; i++) out[i] = (float) (sum[i] / weight[i]);
        return out;
    }

    private static int[] sub(List<Integer> list, int from, int to) {
        return list.subList(from, to).stream().mapToInt(Integer::intValue).toArray();
    }

    private static float[] concat(List<float[]> blocks) {
        int n = 0;
        for (float[] b : blocks) n += b.length;
        float[] out = new float[n];
        int at = 0;
        for (float[] b : blocks) { System.arraycopy(b, 0, out, at, b.length); at += b.length; }
        return out;
    }
}
