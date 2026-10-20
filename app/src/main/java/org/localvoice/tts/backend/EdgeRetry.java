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
        public FatalException(String message, Throwable cause) { super(message, cause); }
    }
    /** The service completed or refused this fragment without audio; retrying the same text cannot help. */
    public static final class DeclinedException extends IOException {
        public DeclinedException(String message) { super(message); }
    }
    /** A decline while {@link DeclineGuard} suspects a voice-wide outage: retried; exhausting the budget reports ERROR_NETWORK. */
    public static final class OutageException extends IOException {
        public OutageException(DeclinedException cause) { super("Edge voice outage suspected: " + cause.getMessage(), cause); }
    }
    /**
     * Per-voice run of consecutive declined fragments, across requests. Empty turns also come from voice-wide
     * outages/throttling (edge-tts issues 473, 481), so after {@link #LIMIT} declines in a row further declines are
     * retried as transient until a fragment returns audio.
     */
    public static final class DeclineGuard {
        public static final int LIMIT = 3;
        private final java.util.Map<String,Integer> streaks = new java.util.concurrent.ConcurrentHashMap<>();
        /** Records a declined fragment; true = skip it as silence, false = treat it as transient. */
        public boolean skip(String voice) { return streaks.merge(voice, 1, (a, b) -> Math.min(a + b, LIMIT + 1)) <= LIMIT; }
        public void audio(String voice) { streaks.remove(voice); }
    }
    /** Token/authorization rejection; retried once immediately (after a clock-skew correction), then fatal. */
    public static final class AuthException extends IOException {
        public AuthException(String message) { super(message); }
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
        return run(attempt, cancellation, network, observer, 0, -1, true);
    }
    /**
     * {@code budgetMillis} 0 = no time budget; {@code retries} -1 = unlimited retries after the first attempt.
     * {@code boundOffline} false: offline waits are unlimited and do not spend the budget, which then bounds only
     * time spent failing while the device reports a network.
     */
    public static <T> T run(Attempt<T> attempt, SpeechBackend.Cancellation cancellation, Network network,
                            Observer observer, long budgetMillis, int retries, boolean boundOffline) throws Exception {
        int attempts = 0;
        boolean authRetried = false;
        long delay = INITIAL_DELAY_MS;
        long deadline = budgetMillis > 0 ? network.nowMillis() + budgetMillis : Long.MAX_VALUE;
        try {
            while (true) {
                check(cancellation);
                if (!network.isAvailable()) {
                    long left = deadline - network.nowMillis();
                    if (boundOffline && left <= 0) throw new ExhaustedException(true, null);
                    observer.waiting(true, attempts);
                    long before = network.nowMillis();
                    network.await(boundOffline ? Math.min(MAX_DELAY_MS, left) : MAX_DELAY_MS, cancellation);
                    if (!boundOffline && deadline != Long.MAX_VALUE) deadline += Math.max(0, network.nowMillis() - before);
                    continue;
                }
                try {
                    attempts++;
                    T result = attempt.fetchAndValidate();
                    check(cancellation);
                    if (result == null) throw new IOException("Edge returned no validated audio");
                    return result;
                } catch (FatalException | CancelledException | DeclinedException fatal) {
                    throw fatal;
                } catch (AuthException auth) {
                    // edge-tts 7.3.1 Communicate.stream: one retry after a 403 with corrected clock skew, then raise.
                    if (authRetried) throw new FatalException(auth.getMessage(), auth);
                    authRetried = true;
                } catch (IOException transientFailure) {
                    check(cancellation);
                    if (retries >= 0 && attempts > retries) throw new ExhaustedException(false, transientFailure);
                    long left = deadline - network.nowMillis();
                    if (left <= 0) throw new ExhaustedException(!(transientFailure instanceof OutageException), transientFailure);
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
