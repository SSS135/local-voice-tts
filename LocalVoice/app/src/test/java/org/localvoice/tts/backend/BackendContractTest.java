package org.localvoice.tts.backend;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone JVM checks: no Android runtime, network, model files or native inference. */
public final class BackendContractTest {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    private static final File DIRECTORY = new File("fake-model");
    private static final Map<String,String> CONFIG = Map.of("model", "fake.onnx");
    private static int passed;
    interface Checked { void run() throws Exception; }
    interface Generate { void run(SpeechBackend.Cancellation cancellation, SpeechBackend.PcmSink sink) throws Exception; }

    static final class FakeBackend implements SpeechBackend {
        int loads, closes, generates, cancels;
        volatile boolean inInference;
        boolean failLoad;
        Map<String,String> expectedConfig = CONFIG;
        CountDownLatch loadEntered, loadRelease;
        Generate generate = (c, sink) -> sink.write(new float[]{0, .25f, -.25f});
        String text, language;
        int speaker;
        float speed;
        @Override public void load(File directory, Map<String,String> config) throws Exception {
            loads++;
            check(directory.equals(DIRECTORY) && config.equals(expectedConfig), "load arguments changed");
            if (loadEntered != null) { loadEntered.countDown(); await(loadRelease); }
            if (failLoad) throw new IOException("fake load failure");
        }
        @Override public int sampleRate() { return 24000; }
        @Override public void synthesize(String text, String language, int speaker, float speed,
                Cancellation cancellation, PcmSink sink) throws Exception {
            this.text=text; this.language=language; this.speaker=speaker; this.speed=speed;
            check(!inInference, "concurrent synthesis on same adapter");
            inInference=true; generates++;
            try { generate.run(cancellation, sink); } finally { inInference=false; }
        }
        @Override public void cancel() { cancels++; }
        @Override public void close() {
            check(!inInference, "adapter freed during inference");
            closes++;
        }
    }

    public static void main(String[] args) throws Exception {
        test("PCM parameters, sample rate and synchronous callback", BackendContractTest::pcmContract);
        test("same model reused and switched model closed", BackendContractTest::reuseAndSwitch);
        test("same model with changed voice config reloads", BackendContractTest::changedConfig);
        test("failed load closes partial adapter and recovers", BackendContractTest::failedLoad);
        test("inference failure propagates and next call recovers", BackendContractTest::failedInference);
        test("ready failure prevents inference and recovers", BackendContractTest::failedReady);
        test("cancel before execute avoids loading", BackendContractTest::cancelBeforeExecute);
        test("cancel while loading suppresses ready and PCM", BackendContractTest::cancelDuringLoad);
        test("stubborn native callbacks after cancel are discarded", BackendContractTest::stubbornCancellation);
        test("sink stop stays closed for stubborn native callbacks", BackendContractTest::sinkStop);
        test("cancel returns while output sink is blocked", BackendContractTest::cancelWhileSinkBlocked);
        test("fresh generation survives previous cancellation", BackendContractTest::freshGeneration);
        test("model switch waits for inference; no parallel inference", BackendContractTest::serializedSwitch);
        test("close cancels output and waits before freeing", BackendContractTest::serializedClose);
        System.out.println("PASS: " + passed + " backend contract tests");
    }

    private static void test(String name, Checked action) throws Exception {
        action.run(); passed++;
        System.out.println("PASS " + name);
    }
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "timed out waiting for fake backend");
    }
    private static void execute(SerializedBackend serial, long token, String id,
            SerializedBackend.Ready ready, SpeechBackend.PcmSink sink) throws Exception {
        serial.execute(token, id, "fake", DIRECTORY, CONFIG, "Test speech", "ru-RU", 3, 1.25f, ready, sink);
    }
    private static void execute(SerializedBackend serial, long token, String id) throws Exception {
        execute(serial, token, id, rate -> {}, samples -> true);
    }
    private static void expectIOException(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { /* failure reaches the service boundary */ }
    }
    static final class Call {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        Call(Checked action) {
            thread = new Thread(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } }, "contract-worker");
            thread.setDaemon(true); thread.start();
        }
        void blocked() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline && thread.isAlive()) Thread.yield();
            check(thread.getState() == Thread.State.BLOCKED, "operation did not wait on inference monitor: " + thread.getState());
        }
        void join() throws Exception {
            thread.join(5000);
            check(!thread.isAlive(), "worker did not finish");
            if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        }
    }

    private static void pcmContract() throws Exception {
        FakeBackend fake = new FakeBackend();
        List<String> events = new ArrayList<>();
        Thread caller = Thread.currentThread();
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            execute(serial, serial.begin(), "a", rate -> {
                check(rate == 24000, "incorrect sample rate"); events.add("ready");
            }, samples -> {
                check(Thread.currentThread() == caller, "asynchronous PCM callback");
                check(samples.length == 3 && samples[1] == .25f && samples[2] == -.25f, "PCM altered");
                events.add("pcm"); return true;
            });
        }
        check(events.equals(List.of("ready", "pcm")), "ready must precede PCM");
        check(fake.text.equals("Test speech") && fake.language.equals("ru-RU") && fake.speaker == 3 && fake.speed == 1.25f, "synthesis arguments changed");
        check(fake.closes == 1, "close did not release adapter exactly once");
    }
    private static void reuseAndSwitch() throws Exception {
        List<FakeBackend> fakes = new ArrayList<>();
        SerializedBackend serial = new SerializedBackend(kind -> { FakeBackend f = new FakeBackend(); fakes.add(f); return f; });
        execute(serial, serial.begin(), "a"); execute(serial, serial.begin(), "a");
        check(fakes.size() == 1 && fakes.get(0).loads == 1 && fakes.get(0).generates == 2, "same model reloaded");
        execute(serial, serial.begin(), "b");
        check(fakes.size() == 2 && fakes.get(0).closes == 1, "switch did not release prior model");
        serial.close(); serial.close();
        check(fakes.get(1).closes == 1, "close is not idempotent");
    }
    private static void failedLoad() throws Exception {
        FakeBackend broken = new FakeBackend(); broken.failLoad=true;
        FakeBackend healthy = new FakeBackend(); AtomicInteger created = new AtomicInteger();
        try (SerializedBackend serial = new SerializedBackend(kind -> created.getAndIncrement() == 0 ? broken : healthy)) {
            expectIOException(() -> execute(serial, serial.begin(), "a"));
            check(broken.closes == 1 && broken.generates == 0, "partial load leaked or inferred");
            execute(serial, serial.begin(), "a");
            check(healthy.loads == 1 && healthy.generates == 1, "failed model cached as loaded");
        }
    }
    private static void changedConfig() throws Exception {
        FakeBackend first = new FakeBackend(), second = new FakeBackend();
        Map<String,String> config = new HashMap<>(CONFIG); config.put("voice", "Aria");
        first.expectedConfig = Map.copyOf(config);
        AtomicInteger created = new AtomicInteger();
        try (SerializedBackend serial = new SerializedBackend(kind -> created.getAndIncrement() == 0 ? first : second)) {
            serial.execute(serial.begin(), "edge", "fake", DIRECTORY, config, "Hello", "en-US", 0, 1, rate -> {}, samples -> true);
            config.put("voice", "Guy"); second.expectedConfig = Map.copyOf(config);
            serial.execute(serial.begin(), "edge", "fake", DIRECTORY, config, "Hello", "en-US", 0, 1, rate -> {}, samples -> true);
            check(created.get() == 2 && first.closes == 1 && second.generates == 1, "stale per-voice config reused");
        }
    }
    private static void failedInference() throws Exception {
        FakeBackend fake = new FakeBackend();
        fake.generate = (cancel, sink) -> { sink.write(new float[]{.1f}); throw new IOException("fake inference failure"); };
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            expectIOException(() -> execute(serial, serial.begin(), "a"));
            fake.generate = (cancel, sink) -> sink.write(new float[]{.2f});
            execute(serial, serial.begin(), "a");
            check(fake.generates == 2, "inference failure wedged serializer");
        }
    }
    private static void failedReady() throws Exception {
        FakeBackend fake = new FakeBackend();
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            expectIOException(() -> execute(serial, serial.begin(), "a", rate -> { throw new IOException("output start failed"); }, samples -> true));
            check(fake.generates == 0, "inferred after output start failed");
            execute(serial, serial.begin(), "a");
            check(fake.generates == 1, "ready failure wedged serializer");
        }
    }
    private static void cancelBeforeExecute() throws Exception {
        AtomicInteger created = new AtomicInteger();
        try (SerializedBackend serial = new SerializedBackend(kind -> { created.incrementAndGet(); return new FakeBackend(); })) {
            long token=serial.begin(); serial.cancel();
            execute(serial, token, "a", rate -> { throw new AssertionError("ready after cancellation"); }, samples -> { throw new AssertionError("PCM after cancellation"); });
            check(created.get() == 0, "cancelled request loaded adapter");
        }
    }
    private static void cancelDuringLoad() throws Exception {
        FakeBackend fake = new FakeBackend(); fake.loadEntered = new CountDownLatch(1); fake.loadRelease = new CountDownLatch(1);
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            long token=serial.begin();
            Call call = new Call(() -> execute(serial, token, "a", rate -> { throw new AssertionError("ready after cancelled load"); }, samples -> { throw new AssertionError("PCM after cancelled load"); }));
            await(fake.loadEntered); serial.cancel(); fake.loadRelease.countDown(); call.join();
            check(fake.generates == 0, "inference started after cancelled load");
        } finally { fake.loadRelease.countDown(); }
    }
    private static void stubbornCancellation() throws Exception {
        FakeBackend fake = new FakeBackend(); AtomicInteger attempts = new AtomicInteger(), accepted = new AtomicInteger();
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            fake.generate = (cancel, sink) -> {
                for (int i=0; i<4; i++) {
                    attempts.incrementAndGet(); boolean more=sink.write(new float[]{.1f});
                    if (i > 0) check(!more && cancel.isCancelled(), "cancel not visible to stubborn backend");
                }
            };
            execute(serial, serial.begin(), "a", rate -> {}, samples -> { accepted.incrementAndGet(); serial.cancel(); return true; });
            check(attempts.get() == 4 && accepted.get() == 1 && fake.cancels > 0, "late PCM escaped cancellation");
        }
    }
    private static void sinkStop() throws Exception {
        FakeBackend fake = new FakeBackend(); AtomicInteger accepted = new AtomicInteger();
        fake.generate = (cancel, sink) -> { for (int i=0; i<4; i++) sink.write(new float[]{.1f}); };
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            execute(serial, serial.begin(), "a", rate -> {}, samples -> { accepted.incrementAndGet(); return false; });
            check(accepted.get() == 1, "native ignored sink stop and output was reopened");
        }
    }
    private static void freshGeneration() throws Exception {
        FakeBackend fake = new FakeBackend(); AtomicInteger pcm = new AtomicInteger();
        try (SerializedBackend serial = new SerializedBackend(kind -> fake)) {
            long stale=serial.begin(); serial.cancel(); long fresh=serial.begin();
            execute(serial, stale, "a");
            execute(serial, fresh, "a", rate -> {}, samples -> { pcm.incrementAndGet(); return true; });
            check(serial.cancelled(stale) && !serial.cancelled(fresh) && fake.generates == 1 && pcm.get() == 1, "old stop poisoned new request");
        }
    }
    private static void cancelWhileSinkBlocked() throws Exception {
        FakeBackend fake = new FakeBackend(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SerializedBackend serial = new SerializedBackend(kind -> fake);
        try {
            long token=serial.begin();
            Call inference = new Call(() -> execute(serial, token, "a", rate -> {}, samples -> {
                entered.countDown();
                try { await(release); } catch (InterruptedException e) { throw new AssertionError(e); }
                return true;
            }));
            await(entered); Call cancellation = new Call(serial::cancel);
            cancellation.join();
            check(serial.cancelled(token), "cancel blocked behind output backpressure");
            release.countDown(); inference.join();
        } finally { release.countDown(); serial.close(); }
    }
    private static void serializedSwitch() throws Exception {
        FakeBackend first = new FakeBackend(), second = new FakeBackend();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), created = new AtomicInteger();
        first.generate = (cancel, sink) -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            try { await(release); sink.write(new float[]{.1f}); } finally { active.decrementAndGet(); }
        };
        second.generate = (cancel, sink) -> { maximum.accumulateAndGet(active.incrementAndGet(), Math::max); active.decrementAndGet(); };
        try (SerializedBackend serial = new SerializedBackend(kind -> created.getAndIncrement() == 0 ? first : second)) {
            long token=serial.begin(); AtomicInteger stalePcm = new AtomicInteger();
            Call a = new Call(() -> execute(serial, token, "a", rate -> {}, samples -> { stalePcm.incrementAndGet(); return true; }));
            await(entered); long replacement=serial.begin();
            Call b = new Call(() -> execute(serial, replacement, "b")); b.blocked();
            check(first.closes == 0 && second.loads == 0, "model switched during active inference");
            release.countDown(); a.join(); b.join();
            check(first.closes == 1 && second.generates == 1 && maximum.get() == 1 && stalePcm.get() == 0, "switch overlap or stale PCM");
        } finally { release.countDown(); }
    }
    private static void serializedClose() throws Exception {
        FakeBackend fake = new FakeBackend(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger pcm = new AtomicInteger();
        fake.generate = (cancel, sink) -> { entered.countDown(); await(release); sink.write(new float[]{.1f}); };
        SerializedBackend serial = new SerializedBackend(kind -> fake);
        try {
            long token=serial.begin();
            Call inference = new Call(() -> execute(serial, token, "a", rate -> {}, samples -> { pcm.incrementAndGet(); return true; }));
            await(entered); Call close = new Call(serial::close); close.blocked();
            check(fake.closes == 0 && serial.cancelled(token), "close did not cancel before waiting");
            release.countDown(); inference.join(); close.join();
            check(fake.closes == 1 && pcm.get() == 0, "close freed early or delivered stale PCM");
        } finally { release.countDown(); serial.close(); }
    }
}
