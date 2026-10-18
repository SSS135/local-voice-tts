package org.localvoice.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.speech.tts.*;
import android.widget.TextView;
import android.util.Log;
import java.io.*;
import java.util.*;
import org.json.*;

/** Separate application using only Android's public TextToSpeech client API. */
public final class ProbeActivity extends Activity {
    TextToSpeech tts; TextView status; final Handler handler=new Handler(Looper.getMainLooper());
    final JSONArray events=new JSONArray(); long began;
    final java.util.concurrent.ConcurrentHashMap<String,Long> pcmBytes=new java.util.concurrent.ConcurrentHashMap<>(),lastPcm=new java.util.concurrent.ConcurrentHashMap<>();
    final Set<String> stopped=java.util.concurrent.ConcurrentHashMap.newKeySet();
    void event(String name,String detail) {
        try { JSONObject e=new JSONObject();e.put("elapsedMs",SystemClock.elapsedRealtime()-began);e.put("event",name);e.put("detail",detail);synchronized(events){events.put(e);try(FileWriter out=new FileWriter(new File(getExternalFilesDir(null),"events.json"))){out.write(events.toString(2));}} }catch(Exception e){Log.e("LVProbe","write",e);}
        Log.i("LVProbe",name+" "+detail);runOnUiThread(()->status.setText(name+"\n"+detail));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);status=new TextView(this);status.setTextSize(19);setContentView(status);began=SystemClock.elapsedRealtime();
        tts=new TextToSpeech(this,result->handler.post(()->{
            event("init",Integer.toString(result));if(result!=TextToSpeech.SUCCESS)return;
            try {
                boolean registered=false;for(TextToSpeech.EngineInfo engine:tts.getEngines())if("org.localvoice.tts".equals(engine.name))registered=true;
                event("engineRegistered",Boolean.toString(registered));if(!registered)throw new IllegalStateException("Local Voice engine missing");
                JSONArray voices=new JSONArray();for(Voice voice:tts.getVoices()){if(!voice.getName().startsWith("kokoro-en:")&&!voice.getName().startsWith("supertonic-3:")&&!voice.getName().startsWith("edge:"))throw new IllegalStateException("Unexpected fallback voice "+voice.getName());JSONObject row=new JSONObject();row.put("name",voice.getName());row.put("language",voice.getLocale().toLanguageTag());row.put("network",voice.isNetworkConnectionRequired());voices.put(row);}event("voices",voices.toString());
                event("enAvailability",Integer.toString(tts.isLanguageAvailable(Locale.US)));event("ruAvailability",Integer.toString(tts.isLanguageAvailable(Locale.forLanguageTag("ru-RU"))));
                String requested=getIntent().getStringExtra("voice");if(requested==null) return;
                Voice selected=null;
                if(requested.equals("@default")){event("setLanguage",Integer.toString(tts.setLanguage(Locale.forLanguageTag(getIntent().getStringExtra("language")==null?"en-US":getIntent().getStringExtra("language")))));selected=tts.getVoice();event("defaultVoice",selected==null?"none":selected.getName());}
                else if(requested.startsWith("!"))selected=new Voice(requested.substring(1),Locale.US,Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,Collections.emptySet());
                else for(Voice voice:tts.getVoices())if(voice.getName().equals(requested))selected=voice;
                if(selected==null){event("missingVoice",requested);return;}
                int voiceResult=tts.setVoice(selected);event("setVoice",Integer.toString(voiceResult));if(voiceResult!=TextToSpeech.SUCCESS){event("invalidVoiceRejected",requested);return;}tts.setSpeechRate(getIntent().getFloatExtra("rate",1f));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    @Override public void onStart(String id){event("start",id);}
                    @Override public void onDone(String id){event("done",id+":bytes="+pcmBytes.getOrDefault(id,0L)+":lastPcmMs="+lastPcm.getOrDefault(id,0L));String repeat=getIntent().getStringExtra("repeatText");if(repeat!=null&&!id.equals("warm"))handler.post(()->event("warmRequest","warm:"+tts.synthesizeToFile(repeat,new Bundle(),new File(getExternalFilesDir(null),"warm.wav"),"warm")));}
                    @Override public void onError(String id){event("error",id);}
                    @Override public void onError(String id,int code){event("error",id+":"+code);}
                    @Override public void onStop(String id,boolean interrupted){stopped.add(id);event("stop",id+":"+interrupted+":bytes="+pcmBytes.getOrDefault(id,0L));}
                    @Override public void onBeginSynthesis(String id,int sampleRate,int format,int channels){event("pcmFormat",id+":"+sampleRate+":"+format+":"+channels);}
                    @Override public void onAudioAvailable(String id,byte[] audio){if(!pcmBytes.containsKey(id)){event("firstPcm",id+":"+audio.length);if(getIntent().getBooleanExtra("stopOnFirstPcm",false))handler.post(()->{long start=SystemClock.elapsedRealtime();event("stopCalling",id);int result=tts.stop();event("stopReturned",result+":durationMs="+(SystemClock.elapsedRealtime()-start));});}if(stopped.contains(id))event("latePcm",id+":"+audio.length);pcmBytes.merge(id,(long)audio.length,Long::sum);lastPcm.put(id,SystemClock.elapsedRealtime()-began);}
                });
                String text=getIntent().getStringExtra("text");if(text==null)text="A quiet evening falls beside the river.";
                String name=getIntent().getStringExtra("name");if(name==null)name="sample";
                final String utteranceName=name;
                String queue=getIntent().getStringExtra("queue");
                if(queue!=null){JSONArray texts=new JSONArray(queue);for(int i=0;i<texts.length();i++){String id=name+"-"+i;event("request",id+":"+tts.synthesizeToFile(texts.getString(i),new Bundle(),new File(getExternalFilesDir(null),id+".wav"),id));}return;}
                event("request",name+":"+(getIntent().getBooleanExtra("speak",false)?tts.speak(text,TextToSpeech.QUEUE_FLUSH,new Bundle(),name):tts.synthesizeToFile(text,new Bundle(),new File(getExternalFilesDir(null),name+".wav"),name)));
                int stop=getIntent().getIntExtra("stopMs",0);if(stop>0)handler.postDelayed(()->{long start=SystemClock.elapsedRealtime();event("stopCalling",utteranceName);int stopResult=tts.stop();event("stopRequested",stopResult+":durationMs="+(SystemClock.elapsedRealtime()-start));},stop);
                String replacement=getIntent().getStringExtra("replacement");if(replacement!=null){final String second=replacement;handler.postDelayed(()->{tts.stop();String replacementVoice=getIntent().getStringExtra("replacementVoice");if(replacementVoice!=null)for(Voice candidate:tts.getVoices())if(candidate.getName().equals(replacementVoice))event("replacementVoice",replacementVoice+":"+tts.setVoice(candidate));event("replaceRequested",Integer.toString(tts.synthesizeToFile(second,new Bundle(),new File(getExternalFilesDir(null),"replacement.wav"),"replacement")));},getIntent().getIntExtra("replaceMs",200));}
            } catch(Exception e){event("exception",e.toString());}
        }),"org.localvoice.tts");
        if(getIntent().getBooleanExtra("checkData",false))startActivityForResult(new Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).setPackage("org.localvoice.tts"),42);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42)event("checkDataResult",result+":available="+(data==null?"none":data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES))+":missing="+(data==null?"none":data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES)));}
    @Override public void onDestroy(){if(tts!=null)tts.shutdown();super.onDestroy();}
}
