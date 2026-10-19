package org.localvoice.tts;

import java.util.function.IntSupplier;
import org.localvoice.tts.backend.PcmTimeStretch;
import org.localvoice.tts.backend.SpeechBackend;

/**
 * Applies the speed beyond an adapter's native range and the caller pitch to one segment's PCM (Sonic).
 * Word sample offsets are rescaled to the stretched timeline. Identity settings pass PCM through untouched.
 */
final class ProsodySink implements SpeechBackend.PcmSink {
    private final SpeechBackend.PcmSink target;
    private final float speed, pitch;
    private final IntSupplier sampleRate;
    private final SpeechBackend.Cancellation cancellation;
    private PcmTimeStretch stretch;

    /** {@code sampleRate} is read at the first write, after the adapter reported its rate. */
    ProsodySink(SpeechBackend.PcmSink target, float speed, float pitch, IntSupplier sampleRate, SpeechBackend.Cancellation cancellation) {
        this.target = target; this.speed = speed; this.pitch = pitch; this.sampleRate = sampleRate; this.cancellation = cancellation;
    }
    @Override public boolean write(float[] samples) {
        if (speed == 1f && pitch == 1f) return target.write(samples);
        if (stretch == null) stretch = new PcmTimeStretch(sampleRate.getAsInt(), speed, pitch);
        return stretch.write(samples, cancellation, target);
    }
    @Override public void word(long sample, int start, int end) { target.word(Math.round(sample / (double) speed), start, end); }
    /** Emits Sonic's buffered tail; call once after the adapter's synthesize() returned. */
    boolean finish() { return stretch == null || stretch.finish(cancellation, target); }
}
