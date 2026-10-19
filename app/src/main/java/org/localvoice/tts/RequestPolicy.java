package org.localvoice.tts;

import android.speech.tts.TextToSpeech;
import java.io.IOException;
import org.localvoice.tts.backend.EdgeRetry;
import org.localvoice.tts.backend.SpeechBackend;

/** Pure per-request rules: backend options from the client's request and the error code reported to it. */
final class RequestPolicy {
    /** Retry budget per online server turn when the client sets no KEY_FEATURE_NETWORK_TIMEOUT_MS. */
    static final long DEFAULT_NETWORK_TIMEOUT_MS = 120_000;
    private RequestPolicy() {}

    /** Installed model files failed verification before synthesis. */
    static final class NotInstalledException extends IOException {
        NotInstalledException(Throwable cause) { super("Model files are not installed", cause); }
    }

    /**
     * {@code timeoutMs}/{@code retries}: the client's KEY_FEATURE_NETWORK_TIMEOUT_MS / KEY_FEATURE_NETWORK_RETRIES_COUNT
     * string values (null when unset). Pitch is passed natively only to network adapters; local PCM is shaped by the service.
     */
    static SpeechBackend.Options options(int pitchPercent, boolean network, String timeoutMs, String retries) {
        long timeout = parse(timeoutMs);
        long count = parse(retries);
        return new SpeechBackend.Options(network ? SpeechRatePolicy.pitch(pitchPercent) : 1f,
            timeout > 0 ? timeout : DEFAULT_NETWORK_TIMEOUT_MS, count >= 0 ? (int) Math.min(Integer.MAX_VALUE, count) : -1);
    }

    static int errorCode(Throwable failure, boolean network) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof EdgeRetry.ExhaustedException)
                return ((EdgeRetry.ExhaustedException) cause).timedOut ? TextToSpeech.ERROR_NETWORK_TIMEOUT : TextToSpeech.ERROR_NETWORK;
            // Authorization rejection, incompatible protocol or invalid voice metadata: retrying cannot help.
            if (cause instanceof EdgeRetry.FatalException) return TextToSpeech.ERROR_SERVICE;
            if (cause instanceof NotInstalledException) return TextToSpeech.ERROR_NOT_INSTALLED_YET;
        }
        return network ? TextToSpeech.ERROR_NETWORK : TextToSpeech.ERROR_SYNTHESIS;
    }

    private static long parse(String value) {
        if (value == null) return -1;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException invalid) { return -1; }
    }
}
