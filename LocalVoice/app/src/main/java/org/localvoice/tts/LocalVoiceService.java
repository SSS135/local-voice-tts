package org.localvoice.tts;

import android.media.AudioFormat;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.speech.tts.*;
import android.util.Log;
import org.localvoice.tts.backend.SerializedBackend;
import org.localvoice.tts.backend.BackendRegistry;
import org.localvoice.tts.backend.SpeechBackend;
import java.util.*;

public final class LocalVoiceService extends TextToSpeechService {
    private AndroidBackendHost host;
    private final SerializedBackend backend = new SerializedBackend(kind->{SpeechBackend adapter=BackendRegistry.create(kind);adapter.setHost(host);return adapter;});
    private ModelStore store;
    private final Object requests=new Object();
    private volatile long activeToken=-1,cancelledByUiToken=-2;
    private volatile String activeModel;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanges=(prefs,key)->{
        if("voice".equals(key))cancelFromUi();
        else if(key!=null&&key.startsWith("online.")&&key.substring(7).equals(activeModel)&&!prefs.getBoolean(key,false))cancelFromUi();
    };
    private final ModelStore.Listener modelChanges=()->{String model=activeModel;if(model!=null&&store!=null&&!store.isInstalled(model))cancelFromUi();};
    @Override public void onCreate() { store = new ModelStore(this);host=new AndroidBackendHost(this);preferences=getSharedPreferences("voices",MODE_PRIVATE);preferences.registerOnSharedPreferenceChangeListener(preferenceChanges);store.addListener(modelChanges);BackendStatus.controller(this,this::cancelFromUi);super.onCreate(); }
    private void cancelFromUi(){synchronized(requests){if(activeToken>=0){cancelledByUiToken=activeToken;backend.cancel();host.reportStatus("stopped",0);}}}
    @Override protected String[] onGetLanguage() {
        Catalog.VoiceSpec voice = choose(null, "", "");
        if (voice == null) return new String[]{"", "", ""};
        Locale locale = Locale.forLanguageTag(voice.languageTag);
        return new String[]{LocaleTags.language(locale),LocaleTags.country(locale),""};
    }
    private Catalog.VoiceSpec choose(String name,String language,String country) {
        List<Catalog.VoiceSpec> available = store.installedVoices();
        if (name != null && !name.isEmpty()) {
            for (Catalog.VoiceSpec voice : available) if (voice.id.equals(name)) return voice;
            return null;
        }
        String selected = store.selectedVoice();
        // The voice picked in Local Voice is the system voice for its whole language: clients such as browsers
        // request the page/device region (en-US) and must still get a selected en-GB voice. Country ranks the rest.
        for (Catalog.VoiceSpec voice : available) if (voice.id.equals(selected) && matches(voice,language,"")) return voice;
        for (Catalog.VoiceSpec voice : available) if (!Catalog.findModel(voice.modelId).network && matches(voice,language,country)) return voice;
        // Prefer a locally installed language even when only another country variant is present.
        if(country != null && !country.isEmpty())
            for(Catalog.VoiceSpec voice:available) if(!Catalog.findModel(voice.modelId).network&&matches(voice,language,""))return voice;
        // The online catalog is installed only after explicit opt-in. Selection happens before synthesis;
        // failures never switch provider or fall back to a different voice.
        for(Catalog.VoiceSpec voice:available) if(Catalog.findModel(voice.modelId).network&&matches(voice,language,country))return voice;
        if(country != null && !country.isEmpty()) return choose(null,language,"");
        return null;
    }
    /** language tag -> {language, ISO3 language, country, ISO3 country}; parsed once, matched ~10^5 times per browser bind. */
    private static final Map<String,String[]> TAGS = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean matches(Catalog.VoiceSpec voice,String language,String country) {
        String[] tag = TAGS.computeIfAbsent(voice.languageTag,key->{Locale locale=Locale.forLanguageTag(key);return new String[]{locale.getLanguage(),LocaleTags.language(locale),locale.getCountry(),LocaleTags.country(locale)};});
        return (language == null || language.isEmpty() || language.equals(tag[0]) || language.equals(tag[1]))
            && (country == null || country.isEmpty() || country.equals(tag[2]) || country.equals(tag[3]));
    }
    @Override protected int onIsLanguageAvailable(String lang,String country,String variant) {
        return availability(store.installedVoices(),store.selectedVoice(),lang,country);
    }
    /**
     * Counts only voices a locale-only request will speak (SEC-004): local voices plus the selected online voice.
     * Unselected online voices stay discoverable by name via getVoices(); counting them here made browsers list
     * languages whose setLanguage() then finds no default voice.
     */
    static int availability(List<Catalog.VoiceSpec> installed,String selected,String lang,String country) {
        boolean withCountry = country != null && !country.isEmpty();
        for (Catalog.VoiceSpec voice : installed) if ((!isOnline(voice) || voice.id.equals(selected)) && matches(voice,lang,country)) return withCountry ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_AVAILABLE;
        for (Catalog.VoiceSpec voice : installed) if ((!isOnline(voice) || voice.id.equals(selected)) && matches(voice,lang,"")) return TextToSpeech.LANG_AVAILABLE;
        // Catalog support is distinct from installed availability.
        for (Catalog.Model model : Catalog.MODELS) for (Catalog.VoiceSpec candidate : model.voices)
            if (matches(candidate,lang,"")) return TextToSpeech.LANG_MISSING_DATA;
        return TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String lang,String country,String variant) { return onIsLanguageAvailable(lang,country,variant); }
    @Override public List<Voice> onGetVoices() {
        ArrayList<Voice> voices = new ArrayList<>();
        for (Catalog.VoiceSpec voice : store.installedVoices()) {
            boolean network = Catalog.findModel(voice.modelId).network;
            voices.add(new Voice(voice.id,Locale.forLanguageTag(voice.languageTag),network ? Voice.QUALITY_NORMAL : Voice.QUALITY_HIGH,
                network ? Voice.LATENCY_NORMAL : Voice.LATENCY_LOW,network,
                Collections.singleton(network ? TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS : TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)));
        }
        return voices;
    }
    @Override public int onIsValidVoiceName(String name) { return name != null && !name.isEmpty() && choose(name,"","") != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR; }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override public String onGetDefaultVoiceNameFor(String lang,String country,String variant) {
        Catalog.VoiceSpec voice = choose(null,lang,country);
        // TextToSpeech.setLanguage() turns this answer into a named voice, so it must apply the SEC-004 rule too.
        if (voice == null || (isOnline(voice) && !voice.id.equals(store.selectedVoice()))) return null;
        return voice.id;
    }
    @Override protected void onStop() { synchronized(requests){backend.cancel();} if(host!=null)host.reportStatus("stopped",0); }
    @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback) {
        long token; synchronized(requests){token=backend.begin();activeToken=token;}
        long started = SystemClock.elapsedRealtime();
        Catalog.VoiceSpec voice = chooseForSynthesis(request);
        if (voice == null) { callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); callback.done(); synchronized(requests){if(activeToken==token)activeToken=-1;} return; }
        Catalog.Model model = Catalog.findModel(voice.modelId);
        synchronized(requests) {
            activeModel=model.id;
            // Consent may have changed after choose() captured the voice but before publication.
            if(model.network&&!store.isInstalled(model.id)){cancelledByUiToken=token;backend.cancel();}
        }
        if(!backend.cancelled(token))host.reportStatus("synthesizing",0);
        boolean[] didStart = {false},firstPcm={false};
        try {
            if (!model.network) store.verifyInstalled(model.id);
            Map<String,String> config = new HashMap<>(model.config); config.putAll(voice.config);
            String text = trimWhitespace(request.getCharSequenceText().toString());
            if (text.length() > 100000) throw new IllegalArgumentException("Utterance too long");
            float speed = SpeechRatePolicy.effective(request.getSpeechRate(),preferences.getFloat(SpeechRatePolicy.PREFERENCE,1f));
            // Supertonic native compute cannot be interrupted mid-chunk; bound work and discard cancelled PCM.
            for (String chunk : "backend".equals(config.get("chunking"))?java.util.Collections.singletonList(text):chunks(text,180)) {
                if (backend.cancelled(token)) break;
                backend.execute(token,model.id,model.backend,model.network ? getFilesDir() : store.modelDirectory(model.id),config,
                    chunk,voice.languageTag,voice.speakerId,speed,rate -> {
                        if (!didStart[0] && !backend.cancelled(token)) {
                            if (callback.start(rate,AudioFormat.ENCODING_PCM_16BIT,1) != TextToSpeech.SUCCESS) throw new IllegalStateException("Client stopped");
                            didStart[0] = true;
                            Log.i("LocalVoiceTiming","ready model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started));
                        }
                    },samples -> {
                        if (!firstPcm[0] && samples.length>0 && !backend.cancelled(token)) {
                            firstPcm[0]=true;Log.i("LocalVoiceTiming","firstPcm model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started));
                        }
                        int capacity = Math.max(2,Math.min(2048,callback.getMaxBufferSize()) & ~1);
                        byte[] pcm = new byte[Math.min(capacity,Math.max(2,samples.length*2))];
                        int position = 0;
                        for (float sample : samples) {
                            if (backend.cancelled(token)) return false;
                            int value = Math.round(Math.max(-1f,Math.min(1f,sample))*32767f);
                            pcm[position++] = (byte)value; pcm[position++] = (byte)(value >> 8);
                            if (position == pcm.length) {
                                if (callback.audioAvailable(pcm,0,position) != TextToSpeech.SUCCESS) { backend.cancel(); return false; }
                                position = 0;
                            }
                        }
                        if(position==0) return true;
                        if(backend.cancelled(token)) return false;
                        if(callback.audioAvailable(pcm,0,position)!=TextToSpeech.SUCCESS) { backend.cancel(); return false; }
                        return true;
                    });
            }
            if (!backend.cancelled(token)) {
                if(!text.isEmpty() && !firstPcm[0]) throw new IllegalStateException("Backend returned no audio");
                if (!didStart[0] && callback.start(24000,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS) return;
                callback.done();
                host.reportStatus("complete",0);
                Log.i("LocalVoiceTiming","done model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started));
            }
        } catch (Exception|LinkageError e) {
            Log.e("LocalVoice","Synthesis failed for "+model.id,e);
            if (!backend.cancelled(token)) { host.reportStatus("failed",0);callback.error(model.network ? TextToSpeech.ERROR_NETWORK : TextToSpeech.ERROR_SYNTHESIS); callback.done(); }
        } finally {
            if(backend.cancelled(token))host.reportStatus("stopped",0);
            if(cancelledByUiToken==token&&!callback.hasFinished()){callback.error(TextToSpeech.ERROR_SYNTHESIS);callback.done();}
            synchronized(requests){if(activeToken==token){activeToken=-1;activeModel=null;}}
        }
    }
    /**
     * SEC-004: text goes online only for an online voice the client named or the user selected in Local Voice,
     * and never when the client requested embedded synthesis.
     */
    private Catalog.VoiceSpec chooseForSynthesis(SynthesisRequest request) {
        String name = request.getVoiceName();
        Catalog.VoiceSpec voice = choose(name,request.getLanguage(),request.getCountry());
        if (voice == null || !isOnline(voice)) return voice;
        android.os.Bundle params = request.getParams();
        boolean embeddedOnly = params != null && Boolean.parseBoolean(params.getString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS));
        if (embeddedOnly) return localVoice(request.getLanguage(),request.getCountry());
        boolean named = name != null && !name.isEmpty();
        if (named || voice.id.equals(store.selectedVoice())) return voice;
        return null;
    }
    /** Best installed on-device voice for the language, preferring the requested country. */
    private Catalog.VoiceSpec localVoice(String language,String country) {
        Catalog.VoiceSpec languageOnly = null;
        for (Catalog.VoiceSpec voice : store.installedVoices()) {
            if (isOnline(voice) || !matches(voice,language,"")) continue;
            if (matches(voice,language,country)) return voice;
            if (languageOnly == null) languageOnly = voice;
        }
        return languageOnly;
    }
    private static boolean isOnline(Catalog.VoiceSpec voice) {
        return Catalog.findModel(voice.modelId).network;
    }
    static List<String> chunks(String text,int limit) {
        ArrayList<String> result = new ArrayList<>(); int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(),offset+limit);
            if (end < text.length()) {
                int boundary = -1;
                for (int i=end-1;i>offset+limit/3;i--) if (".!?;\n ".indexOf(text.charAt(i))>=0) { boundary=i+1; break; }
                if (boundary>offset) end=boundary;
                if (Character.isHighSurrogate(text.charAt(end-1))) end--;
            }
            String chunk=trimWhitespace(text.substring(offset,end)); if (!chunk.isEmpty()) result.add(chunk); offset=end;
        }
        return result;
    }
    private static String trimWhitespace(String text) {
        int start=0,end=text.length();
        while(start<end&&space(text.codePointAt(start)))start+=Character.charCount(text.codePointAt(start));
        while(end>start&&space(text.codePointBefore(end)))end-=Character.charCount(text.codePointBefore(end));
        return text.substring(start,end);
    }
    private static boolean space(int c){return Character.isSpaceChar(c)||(c>=9&&c<=13)||c==0x85;}
    @Override public void onDestroy() { backend.cancel();preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanges);store.removeListener(modelChanges);BackendStatus.clearController(this);host.close();new Thread(backend::close,"LocalVoice-close").start();super.onDestroy(); }
}
