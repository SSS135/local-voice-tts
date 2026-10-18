package org.localvoice.tts;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import java.util.*;
public final class CheckVoiceDataActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); ModelStore store=new ModelStore(this); ArrayList<String> available=new ArrayList<>(),missing=new ArrayList<>();
        for (Catalog.Model model:Catalog.MODELS) if (!model.network) for(Catalog.VoiceSpec voice:model.voices) {
            Locale locale=Locale.forLanguageTag(voice.languageTag); String id=locale.getISO3Language()+"-"+locale.getISO3Country();
            ArrayList<String> list=store.isInstalled(model.id)?available:missing; if(!list.contains(id)) list.add(id);
        }
        missing.removeAll(available); Intent data=new Intent(); data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,available);
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,missing);
        setResult(available.isEmpty()?TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL:TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,data); finish();
    }
}
