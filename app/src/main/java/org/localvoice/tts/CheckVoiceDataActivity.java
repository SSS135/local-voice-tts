package org.localvoice.tts;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import java.util.*;
public final class CheckVoiceDataActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(()->{
            ModelStore store=new ModelStore(getApplicationContext());
            ArrayList<String> available=new ArrayList<>(),missing=new ArrayList<>();
            for(Catalog.Model model:Catalog.MODELS)if(!model.network){
                // The main-thread snapshot may still be empty on a cold process. Inspect once on this worker.
                boolean installed=store.isInstalled(model.id);
                for(Catalog.VoiceSpec voice:model.voices){
                    Locale locale=Locale.forLanguageTag(voice.languageTag);String id=LocaleTags.language(locale)+"-"+LocaleTags.country(locale);
                    ArrayList<String> list=installed?available:missing;if(!list.contains(id))list.add(id);
                }
            }
            missing.removeAll(available);Intent data=new Intent();data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,available);
            data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,missing);
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                setResult(available.isEmpty()?TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL:TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,data);finish();
            });
        },"LocalVoice-data-check").start();
    }
}
