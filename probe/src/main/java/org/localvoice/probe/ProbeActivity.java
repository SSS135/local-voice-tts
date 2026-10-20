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
    final JSONArray events=new JSONArray(); long began; volatile JSONArray chain;
    final java.util.concurrent.ConcurrentHashMap<String,Long> pcmBytes=new java.util.concurrent.ConcurrentHashMap<>(),lastPcm=new java.util.concurrent.ConcurrentHashMap<>();
    final Set<String> stopped=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Chain mode: PCM exactly as the engine handed it over, saved per utterance as <id>.pcm (16-bit mono) for silence analysis. */
    final java.util.concurrent.ConcurrentHashMap<String,ByteArrayOutputStream> chainPcm=new java.util.concurrent.ConcurrentHashMap<>();
    void capture(String id,byte[] audio){if(id.startsWith("chain-"))chainPcm.computeIfAbsent(id,k->new ByteArrayOutputStream()).write(audio,0,audio.length);}
    void savePcm(String id){ByteArrayOutputStream pcm=chainPcm.remove(id);if(pcm==null)return;try(FileOutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),id+".pcm"))){pcm.writeTo(out);}catch(IOException e){Log.e("LVProbe","pcm",e);}}
    /** toFile: chain without playback (synthesizeToFile), for measuring PCM and render timing while the device is in use. */
    int say(String text,String id){Bundle params=new Bundle();String handoff=getIntent().getStringExtra("handoff");if(handoff!=null)params.putString("paragraph_handoff",handoff);// handoff=false: engine A/B switch for the paragraph tail handoff
        String threads=getIntent().getStringExtra("numThreads");if(threads!=null)params.putString("numThreads",threads);// engine A/B: local model inference threads (1-4)
        return getIntent().getBooleanExtra("toFile",false)?tts.synthesizeToFile(text,params,new File(getExternalFilesDir(null),id+".wav"),id):tts.speak(text,TextToSpeech.QUEUE_FLUSH,params,id);}
    /** chainFile: JSON array of paragraphs in the external files dir; avoids shell quoting of long texts. */
    String chainText()throws IOException{String file=getIntent().getStringExtra("chainFile");if(file==null)return getIntent().getStringExtra("chain");if(!file.matches("[A-Za-z0-9_.-]{1,64}"))throw new IOException("invalid chainFile");return new String(java.nio.file.Files.readAllBytes(new File(getExternalFilesDir(null),file).toPath()),"UTF-8");}
    void event(String name,String detail) {
        try { JSONObject e=new JSONObject();e.put("elapsedMs",SystemClock.elapsedRealtime()-began);e.put("event",name);e.put("detail",detail);synchronized(events){events.put(e);try(FileWriter out=new FileWriter(new File(getExternalFilesDir(null),"events.json"))){out.write(events.toString(2));}} }catch(Exception e){Log.e("LVProbe","write",e);}
        Log.i("LVProbe",name+" "+detail);runOnUiThread(()->status.setText(name+"\n"+detail));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);status=new TextView(this);status.setTextSize(19);setContentView(status);began=SystemClock.elapsedRealtime();
        tts=new TextToSpeech(this,result->handler.post(()->{
            event("init",Integer.toString(result));if(result!=TextToSpeech.SUCCESS)return;
            try {
                // chainEngine: measure another installed engine (e.g. Google TTS baseline) with the same chained requests.
                if(getIntent().getStringExtra("chainEngine")!=null){
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                        @Override public void onStart(String id){event("start",id);}
                        @Override public void onRangeStart(String id,int start,int end,int frame){event("range",id+":"+start+":"+end+":"+frame);}
                        @Override public void onError(String id){event("error",id);}
                        @Override public void onBeginSynthesis(String id,int sampleRate,int format,int channels){event("pcmFormat",id+":"+sampleRate+":"+format+":"+channels);}
                        @Override public void onAudioAvailable(String id,byte[] audio){if(!chainPcm.containsKey(id))event("firstPcm",id+":"+audio.length);capture(id,audio);}
                        @Override public void onDone(String id){savePcm(id);event("done",id);int next=Integer.parseInt(id.substring(6))+1;if(next<chain.length())handler.postDelayed(()->{try{event("request","chain-"+next+":"+say(chain.getString(next),"chain-"+next));}catch(Exception e){event("exception",e.toString());}},getIntent().getIntExtra("chainDelayMs",0));}
                    });
                    chain=new JSONArray(chainText());event("request","chain-0:"+say(chain.getString(0),"chain-0"));return;
                }
                boolean registered=false;for(TextToSpeech.EngineInfo engine:tts.getEngines())if("org.localvoice.tts".equals(engine.name))registered=true;
                event("engineRegistered",Boolean.toString(registered));if(!registered)throw new IllegalStateException("Local Voice engine missing");
                JSONArray voices=new JSONArray();for(Voice voice:tts.getVoices()){if(voice.getName().indexOf(':')<0)throw new IllegalStateException("Unexpected fallback voice "+voice.getName());JSONObject row=new JSONObject();row.put("name",voice.getName());row.put("language",voice.getLocale().toLanguageTag());row.put("network",voice.isNetworkConnectionRequired());row.put("notInstalled",voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));voices.put(row);}event("voices",voices.toString());
                event("enAvailability",Integer.toString(tts.isLanguageAvailable(Locale.US)));event("ruAvailability",Integer.toString(tts.isLanguageAvailable(Locale.forLanguageTag("ru-RU"))));
                String requested=getIntent().getStringExtra("voice");if(requested==null) return;
                Voice selected=null;
                if(requested.equals("@default")){event("setLanguage",Integer.toString(tts.setLanguage(Locale.forLanguageTag(getIntent().getStringExtra("language")==null?"en-US":getIntent().getStringExtra("language")))));selected=tts.getVoice();event("defaultVoice",selected==null?"none":selected.getName());}
                else if(requested.startsWith("!"))selected=new Voice(requested.substring(1),Locale.US,Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,Collections.emptySet());
                else for(Voice voice:tts.getVoices())if(voice.getName().equals(requested))selected=voice;
                if(selected==null){event("missingVoice",requested);return;}
                int voiceResult=tts.setVoice(selected);event("setVoice",Integer.toString(voiceResult));if(voiceResult!=TextToSpeech.SUCCESS){event("invalidVoiceRejected",requested);return;}tts.setSpeechRate(getIntent().getFloatExtra("rate",1f));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    @Override public void onStart(String id){event("start",id);int restart=getIntent().getIntExtra("restartMs",0);// restartMs: Edge-style tap/skip, stop() then speak() of the next text while chain-0 plays.
                        if(restart>0&&"chain-0".equals(id))handler.postDelayed(()->{try{event("stopCalling","chain-0");tts.stop();event("request","chain-1:"+tts.speak(chain.getString(1),TextToSpeech.QUEUE_FLUSH,new Bundle(),"restart-1"));}catch(Exception e){event("exception",e.toString());}},restart);}
                    @Override public void onRangeStart(String id,int start,int end,int frame){event("range",id+":"+start+":"+end+":"+frame);}
                    @Override public void onDone(String id){savePcm(id);event("done",id+":bytes="+pcmBytes.getOrDefault(id,0L)+":lastPcmMs="+lastPcm.getOrDefault(id,0L));
                        // chain: speak the next text only after onDone, as browsers (Chromium TtsController) and Edge Read Aloud do.
                        if(chain!=null&&id.startsWith("chain-")){int next=Integer.parseInt(id.substring(6))+1;if(next<chain.length())handler.postDelayed(()->{try{event("request","chain-"+next+":"+say(chain.getString(next),"chain-"+next));}catch(Exception e){event("exception",e.toString());}},getIntent().getIntExtra("chainDelayMs",0));}String repeat=getIntent().getStringExtra("repeatText");if(repeat!=null&&!id.equals("warm"))handler.post(()->event("warmRequest","warm:"+tts.synthesizeToFile(repeat,new Bundle(),new File(getExternalFilesDir(null),"warm.wav"),"warm")));}
                    @Override public void onError(String id){event("error",id);}
                    @Override public void onError(String id,int code){event("error",id+":"+code);}
                    @Override public void onStop(String id,boolean interrupted){stopped.add(id);event("stop",id+":"+interrupted+":bytes="+pcmBytes.getOrDefault(id,0L));}
                    @Override public void onBeginSynthesis(String id,int sampleRate,int format,int channels){event("pcmFormat",id+":"+sampleRate+":"+format+":"+channels);}
                    @Override public void onAudioAvailable(String id,byte[] audio){if(!pcmBytes.containsKey(id)){event("firstPcm",id+":"+audio.length);if(getIntent().getBooleanExtra("stopOnFirstPcm",false))handler.post(()->{long start=SystemClock.elapsedRealtime();event("stopCalling",id);int result=tts.stop();event("stopReturned",result+":durationMs="+(SystemClock.elapsedRealtime()-start));});}if(stopped.contains(id))event("latePcm",id+":"+audio.length);capture(id,audio);pcmBytes.merge(id,(long)audio.length,Long::sum);lastPcm.put(id,SystemClock.elapsedRealtime()-began);}
                });
                String text=getIntent().getStringExtra("text");if(text==null)text="A quiet evening falls beside the river.";
                String name=getIntent().getStringExtra("name");if(name==null)name="sample";
                // The name becomes an output file name: no path separators or dot segments.
                if(!name.matches("[A-Za-z0-9_-]{1,64}")){
                    event("exception","invalid name extra");
                    return;
                }
                final String utteranceName=name;
                String chained=chainText();
                if(chained!=null){chain=new JSONArray(chained);event("request","chain-0:"+say(chain.getString(0),"chain-0"));return;}
                String queue=getIntent().getStringExtra("queue");
                if(queue!=null){JSONArray texts=new JSONArray(queue);for(int i=0;i<texts.length();i++){String id=name+"-"+i;event("request",id+":"+tts.synthesizeToFile(texts.getString(i),new Bundle(),new File(getExternalFilesDir(null),id+".wav"),id));}return;}
                event("request",name+":"+(getIntent().getBooleanExtra("speak",false)?tts.speak(text,TextToSpeech.QUEUE_FLUSH,new Bundle(),name):tts.synthesizeToFile(text,new Bundle(),new File(getExternalFilesDir(null),name+".wav"),name)));
                int stop=getIntent().getIntExtra("stopMs",0);if(stop>0)handler.postDelayed(()->{long start=SystemClock.elapsedRealtime();event("stopCalling",utteranceName);int stopResult=tts.stop();event("stopRequested",stopResult+":durationMs="+(SystemClock.elapsedRealtime()-start));},stop);
                String replacement=getIntent().getStringExtra("replacement");if(replacement!=null){final String second=replacement;handler.postDelayed(()->{tts.stop();String replacementVoice=getIntent().getStringExtra("replacementVoice");if(replacementVoice!=null)for(Voice candidate:tts.getVoices())if(candidate.getName().equals(replacementVoice))event("replacementVoice",replacementVoice+":"+tts.setVoice(candidate));event("replaceRequested",Integer.toString(tts.synthesizeToFile(second,new Bundle(),new File(getExternalFilesDir(null),"replacement.wav"),"replacement")));},getIntent().getIntExtra("replaceMs",200));}
            } catch(Exception e){event("exception",e.toString());}
        }),getIntent().getStringExtra("chainEngine")!=null?getIntent().getStringExtra("chainEngine"):"org.localvoice.tts");
        if(getIntent().getBooleanExtra("checkData",false))startActivityForResult(new Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).setPackage("org.localvoice.tts"),42);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42)event("checkDataResult",result+":available="+(data==null?"none":data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES))+":missing="+(data==null?"none":data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES)));}
    @Override public void onDestroy(){if(tts!=null)tts.shutdown();super.onDestroy();}
}
