package org.localvoice.tts;
import java.util.Locale;
public final class SettingsPolicyTest {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    private static int checks;
    private static void rate(int caller,float global,float expected){float actual=SpeechRatePolicy.effective(caller,global);if(Math.abs(actual-expected)>.00001f)throw new AssertionError(actual+" != "+expected);checks++;}
    public static void main(String[] args){
        rate(125,1.5f,1.875f);rate(100,.75f,.75f);rate(100,1.25f,1.25f);
        rate(200,2f,2f);rate(25,.5f,.5f);rate(100,Float.NaN,1f);rate(100,Float.POSITIVE_INFINITY,1f);
        rate(0,1.5f,1.5f);rate(-100,1f,1f);rate(100,99f,2f);rate(100,-1f,.5f);
        if(!LocaleTags.country(Locale.forLanguageTag("es-419")).equals("419"))throw new AssertionError("Numeric region lost");checks++;
        if(!LocaleTags.country(Locale.US).equals("USA"))throw new AssertionError("ISO3 country changed");checks++;
        if(!LocaleTags.language(Locale.forLanguageTag("ru-RU")).equals("rus"))throw new AssertionError("Russian ISO3 changed");checks++;
        System.out.println("PASS "+checks+" settings policy checks: composed/clamped caller-global rates and numeric/ISO3 locales");
    }
}
