package org.localvoice.tts;
import android.app.Activity;import android.os.Bundle;import android.content.Intent;import android.speech.tts.TextToSpeech;
public final class SampleTextActivity extends Activity { @Override public void onCreate(Bundle state) { super.onCreate(state); String lang=getIntent().getStringExtra("language"); Intent data=new Intent();data.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,"rus".equals(lang)?"Тихий вечер наступает у реки.":"A quiet evening falls beside the river.");setResult(TextToSpeech.LANG_AVAILABLE,data);finish(); } }
