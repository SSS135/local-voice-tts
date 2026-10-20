package org.localvoice.tts.backend;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Edge SSML pitch, Sonic pitch with unchanged duration, and caller-bounded Edge retries on a virtual clock. */
public final class ProsodyLimitCheck {
    @org.junit.Test public void check() throws Exception {
        check(EdgeProtocol.pitch(1f).equals("+0Hz") && EdgeProtocol.pitch(2f).equals("+150Hz") && EdgeProtocol.pitch(.5f).equals("-75Hz") && EdgeProtocol.pitch(9f).equals("+150Hz"),"SSML pitch mapping");
        check(EdgeProtocol.ssml("Hi","en-US-AriaNeural",1f,1.5f,"id").contains("pitch='+75Hz'"),"SSML pitch not emitted");
        check(EdgeProtocol.ssml("Hi","en-US-AriaNeural",1f,"id").contains("pitch='+0Hz'"),"default SSML pitch changed");

        int rate = 24000;
        float[] tone = new float[rate];
        for (int i = 0; i < tone.length; i++) tone[i] = (float) (0.5 * Math.sin(2 * Math.PI * 200 * i / rate));
        float[] out = shift(tone, rate, 1f, 1.5f);
        check(Math.abs(out.length - tone.length) < rate / 20, "pitch changed duration: " + out.length);
        double ratio = crossings(out) / (double) crossings(tone);
        check(ratio > 1.35 && ratio < 1.65, "pitch 1.5 frequency ratio " + ratio);
        float[] fast = shift(tone, rate, 2f, 1f);
        check(Math.abs(fast.length - tone.length / 2) < rate / 20, "speed 2 length " + fast.length);

        Clock clock = new Clock();
        AtomicInteger attempts = new AtomicInteger();
        EdgeRetry.Attempt<String> failing = () -> { attempts.incrementAndGet(); throw new IOException("down"); };
        try { EdgeRetry.run(failing, () -> false, clock, EdgeRetry.NO_OBSERVER, 0, 2, true); throw new AssertionError("retry count ignored"); }
        catch (EdgeRetry.ExhaustedException expected) { check(!expected.timedOut && attempts.get() == 3, "retries=2 must make 3 attempts, made " + attempts.get()); }
        long start = clock.now;
        try { EdgeRetry.run(failing, () -> false, clock, EdgeRetry.NO_OBSERVER, 5000, -1, true); throw new AssertionError("time budget ignored"); }
        catch (EdgeRetry.ExhaustedException expected) { check(expected.timedOut && clock.now - start <= 5000, "time budget overrun " + (clock.now - start)); }
        clock.online = false; start = clock.now;
        try { EdgeRetry.run(() -> "never", () -> false, clock, EdgeRetry.NO_OBSERVER, 3000, -1, true); throw new AssertionError("offline wait unbounded with client network keys"); }
        catch (EdgeRetry.ExhaustedException expected) { check(expected.timedOut && clock.now - start <= 3000, "offline budget overrun"); }
        clock.online = true; attempts.set(0);
        check("ok".equals(EdgeRetry.run(() -> attempts.incrementAndGet() < 3 ? fail() : "ok", () -> false, clock, EdgeRetry.NO_OBSERVER, 60000, 5, true)), "recovery within budget");
        System.out.println("PASS SSML/Sonic pitch, speed residual and bounded Edge retries");
    }
    private static String fail() throws IOException { throw new IOException("transient"); }
    private static float[] shift(float[] pcm, int rate, float speed, float pitch) {
        PcmTimeStretch stretch = new PcmTimeStretch(rate, speed, pitch);
        java.util.List<float[]> blocks = new java.util.ArrayList<>();
        SpeechBackend.PcmSink sink = samples -> blocks.add(samples.clone());
        stretch.write(pcm, () -> false, sink); stretch.finish(() -> false, sink);
        int n = 0; for (float[] b : blocks) n += b.length;
        float[] out = new float[n]; int at = 0; for (float[] b : blocks) { System.arraycopy(b, 0, out, at, b.length); at += b.length; }
        return out;
    }
    private static int crossings(float[] pcm) { int n = 0; for (int i = 1; i < pcm.length; i++) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) n++; return n; }
    private static final class Clock implements EdgeRetry.Network {
        long now = 1_000_000; boolean online = true;
        @Override public boolean isAvailable() { return online; }
        @Override public long nowMillis() { return now; }
        @Override public void await(long milliseconds, SpeechBackend.Cancellation cancellation) { now += Math.max(1, milliseconds); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
