package org.localvoice.tts;

/** Compose the caller rate with the persisted user multiplier exactly once; map caller pitch. */
public final class SpeechRatePolicy {
    public static final String PREFERENCE="speed_multiplier";
    /** Overall speed range: Chromium passes 0.1..10 and Android Settings goes above 2x. */
    static final float MIN_SPEED=.25f,MAX_SPEED=4f;
    /** Adapter-native speed range (model speed/length scale, Edge SSML); Sonic time-stretch covers the rest. */
    static final float MIN_NATIVE=.5f,MAX_NATIVE=2f;
    private SpeechRatePolicy() {}
    public static float effective(int callerPercent,float multiplier) {
        if(!Float.isFinite(multiplier)) multiplier=1f;
        multiplier=Math.max(.5f,Math.min(2f,multiplier));
        float caller=callerPercent>0?callerPercent/100f:1f;
        return Math.max(MIN_SPEED,Math.min(MAX_SPEED,caller*multiplier));
    }
    /** Part of {@code speed} the adapter renders itself. */
    static float nativeSpeed(float speed) { return Math.max(MIN_NATIVE,Math.min(MAX_NATIVE,speed)); }
    /** Remaining time-stretch factor applied to the adapter's PCM; always within 0.5..2. */
    static float residualSpeed(float speed) { return speed/nativeSpeed(speed); }
    /** SynthesisRequest.getPitch() (100 = normal) to a pitch factor; 0 or less means unset. Pico's 50..200 range. */
    static float pitch(int callerPercent) { return callerPercent>0?Math.max(.5f,Math.min(2f,callerPercent/100f)):1f; }
}
