package org.localvoice.tts.backend;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/** Retries only a pending, uncommitted turn. A validated result is committed by its caller. */
public final class EdgeRetry {
    public static final long INITIAL_DELAY_MS = 250;
    public static final long MAX_DELAY_MS = 15000;
    public interface Attempt<T> { T fetchAndValidate() throws Exception; }
    public interface Network {
        boolean isAvailable();
        default long nowMillis() { return System.nanoTime() / 1000000L; }
        /** Must wake promptly for cancellation; a connectivity change should wake it early. */
        void await(long milliseconds, SpeechBackend.Cancellation cancellation) throws InterruptedException;
    }
    public interface Observer { void waiting(boolean waiting, int attempts); }
    public static final class FatalException extends IOException {
        public FatalException(String message) { super(message); }
    }
    public static final class CancelledException extends IOException {
        public CancelledException() { super("Edge request cancelled"); }
    }
    public static final class TransientException extends IOException {
        public final long retryAfterMillis;
        public TransientException(String message, long retryAfterMillis, Throwable cause) {
            super(message, cause); this.retryAfterMillis = Math.max(0, retryAfterMillis);
        }
    }
    public static final Network DEFAULT_NETWORK = new Network() {
        @Override public boolean isAvailable() { return true; }
        @Override public void await(long milliseconds, SpeechBackend.Cancellation cancellation) throws InterruptedException {
            long end = System.nanoTime() + milliseconds * 1000000L;
            while (!cancellation.isCancelled()) {
                long left = (end - System.nanoTime()) / 1000000L;
                if (left <= 0) return;
                Thread.sleep(Math.min(50, left));
            }
        }
    };
    public static final Observer NO_OBSERVER = (waiting, attempts) -> {};
    /** The caller's retry budget ran out: {@code timedOut} for the time budget, otherwise the retry count. */
    public static final class ExhaustedException extends IOException {
        public final boolean timedOut;
        public ExhaustedException(boolean timedOut, Throwable cause) {
            super(timedOut ? "Edge retry time budget exhausted" : "Edge retry count exhausted", cause); this.timedOut = timedOut;
        }
    }

    public static <T> T run(Attempt<T> attempt, SpeechBackend.Cancellation cancellation,
                            Network network, Observer observer) throws Exception {
        return run(attempt, cancellation, network, observer, 0, -1);
    }
    /** {@code budgetMillis} 0 = no time budget; {@code retries} -1 = unlimited retries after the first attempt. */
    public static <T> T run(Attempt<T> attempt, SpeechBackend.Cancellation cancellation,
                            Network network, Observer observer, long budgetMillis, int retries) throws Exception {
        int attempts = 0;
        long delay = INITIAL_DELAY_MS;
        long deadline = budgetMillis > 0 ? network.nowMillis() + budgetMillis : Long.MAX_VALUE;
        try {
            while (true) {
                check(cancellation);
                if (!network.isAvailable()) {
                    long left = deadline - network.nowMillis();
                    if (left <= 0) throw new ExhaustedException(true, null);
                    observer.waiting(true, attempts);
                    network.await(Math.min(MAX_DELAY_MS, left), cancellation);
                    continue;
                }
                try {
                    attempts++;
                    T result = attempt.fetchAndValidate();
                    check(cancellation);
                    if (result == null) throw new IOException("Edge returned no validated audio");
                    return result;
                } catch (FatalException | CancelledException fatal) {
                    throw fatal;
                } catch (IOException transientFailure) {
                    check(cancellation);
                    if (retries >= 0 && attempts > retries) throw new ExhaustedException(false, transientFailure);
                    long left = deadline - network.nowMillis();
                    if (left <= 0) throw new ExhaustedException(true, transientFailure);
                    observer.waiting(true, attempts);
                    long floor = Math.max(INITIAL_DELAY_MS, delay * 3 / 4);
                    long jitter = ThreadLocalRandom.current().nextLong(delay - floor + 1);
                    long wait = Math.min(left, floor + jitter);
                    if (transientFailure instanceof TransientException && ((TransientException)transientFailure).retryAfterMillis > wait) {
                        long remaining = ((TransientException)transientFailure).retryAfterMillis;
                        if (remaining > left) throw new ExhaustedException(true, transientFailure);
                        while (remaining > 0) {
                            check(cancellation);
                            long before = network.nowMillis();
                            network.await(Math.min(MAX_DELAY_MS, remaining), cancellation);
                            remaining -= Math.max(0, network.nowMillis() - before);
                        }
                    } else network.await(wait, cancellation);
                    delay = Math.min(MAX_DELAY_MS, delay * 2);
                }
            }
        } finally { observer.waiting(false, attempts); }
    }

    private static void check(SpeechBackend.Cancellation cancellation) throws CancelledException {
        if (cancellation.isCancelled()) throw new CancelledException();
    }
    private EdgeRetry() {}
}
