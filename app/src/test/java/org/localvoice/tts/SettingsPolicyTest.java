package org.localvoice.tts;
import java.util.Locale;
public final class SettingsPolicyTest {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    private static int checks;
    private static void rate(int caller,float global,float expected){float actual=SpeechRatePolicy.effective(caller,global);if(Math.abs(actual-expected)>.00001f)throw new AssertionError(actual+" != "+expected);checks++;}
    private static void pitch(int caller,float expected){float actual=SpeechRatePolicy.pitch(caller);if(Math.abs(actual-expected)>.00001f)throw new AssertionError("pitch "+caller+": "+actual+" != "+expected);checks++;}
    public static void main(String[] args){
        rate(125,1.5f,1.875f);rate(100,.75f,.75f);rate(100,1.25f,1.25f);
        rate(200,2f,4f);rate(25,.5f,.25f);rate(100,Float.NaN,1f);rate(100,Float.POSITIVE_INFINITY,1f);
        rate(0,1.5f,1.5f);rate(-100,1f,1f);rate(100,99f,2f);rate(100,-1f,.5f);rate(1000,1f,4f);rate(10,1f,.25f);
        // Native adapter speed stays 0.5..2; the Sonic residual covers the rest and stays within its 0.5..2 range.
        for(float speed:new float[]{.25f,.5f,1f,1.31f,2f,3f,4f}){
            float n=SpeechRatePolicy.nativeSpeed(speed),r=SpeechRatePolicy.residualSpeed(speed);
            if(n<.5f||n>2f||r<.5f||r>2f||Math.abs(n*r-speed)>1e-5f)throw new AssertionError("speed split "+speed+" -> "+n+"*"+r);checks++;
        }
        if(SpeechRatePolicy.residualSpeed(1.31f)!=1f)throw new AssertionError("in-range speed must not be stretched");checks++;
        pitch(100,1f);pitch(0,1f);pitch(-5,1f);pitch(200,2f);pitch(150,1.5f);pitch(10,.5f);pitch(1000,2f);
        if(!LocaleTags.country(Locale.forLanguageTag("es-419")).equals("419"))throw new AssertionError("Numeric region lost");checks++;
        if(!LocaleTags.country(Locale.US).equals("USA"))throw new AssertionError("ISO3 country changed");checks++;
        if(!LocaleTags.language(Locale.forLanguageTag("ru-RU")).equals("rus"))throw new AssertionError("Russian ISO3 changed");checks++;
        System.out.println("PASS "+checks+" settings policy checks: composed/clamped caller-global rates, native/Sonic speed split, pitch and numeric/ISO3 locales");
    }
}
