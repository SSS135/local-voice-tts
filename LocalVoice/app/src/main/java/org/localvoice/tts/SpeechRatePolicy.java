package org.localvoice.tts;

/** Compose the caller rate with the persisted user multiplier exactly once. */
public final class SpeechRatePolicy {
    public static final String PREFERENCE="speed_multiplier";
    private SpeechRatePolicy() {}
    public static float effective(int callerPercent,float multiplier) {
        if(!Float.isFinite(multiplier)) multiplier=1f;
        multiplier=Math.max(.5f,Math.min(2f,multiplier));
        float caller=callerPercent>0?callerPercent/100f:1f;
        return Math.max(.5f,Math.min(2f,caller*multiplier));
    }
}
