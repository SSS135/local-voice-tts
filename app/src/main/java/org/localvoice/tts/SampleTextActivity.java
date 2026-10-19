package org.localvoice.tts;
import android.app.Activity;import android.os.Bundle;import android.content.Intent;import android.speech.tts.TextToSpeech;
import java.util.Locale;
/** ACTION_GET_SAMPLE_TEXT. Android Settings sends ISO 639-1 codes ("ru"), Pico-era callers ISO3 ("rus"). */
public final class SampleTextActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String sample=sample(getIntent().getStringExtra("language"));
        Intent data=new Intent();
        if(sample!=null)data.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,sample);
        // LANG_NOT_SUPPORTED makes Settings fall back to its own localized demo string for that language.
        setResult(sample!=null?TextToSpeech.LANG_AVAILABLE:TextToSpeech.LANG_NOT_SUPPORTED,data);
        finish();
    }
    /** Sample for a 2- or 3-letter language code, or null when Local Voice ships none for it. */
    static String sample(String language) {
        if(language==null)return null;
        String code=language.trim().toLowerCase(Locale.ROOT);
        if(code.equals("en")||code.equals("eng"))return "A quiet evening falls beside the river.";
        if(code.equals("ru")||code.equals("rus"))return "Тихий вечер наступает у реки.";
        return null;
    }
}
