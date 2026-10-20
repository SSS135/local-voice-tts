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
    /** Renders segments ahead of playback; one thread keeps requests in order behind the serialized backend. */
    private final java.util.concurrent.ExecutorService synth = java.util.concurrent.Executors.newSingleThreadExecutor(task->new Thread(task,"LocalVoice-synth"));
    private final Object requests=new Object();
    private volatile long activeToken=-1,cancelledByUiToken=-2;
    private volatile String activeModel;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanges=(prefs,key)->{
        // A changed default applies from the next request (default:<lang> aliases resolve per request); it never
        // cancels a client's active request. Revoked online consent still stops the matching online request.
        if(key!=null&&key.startsWith("online.")&&key.substring(7).equals(activeModel)&&!prefs.getBoolean(key,false))cancelFromUi();
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
    /** Named voice (id, retired id or default alias), else the voice a language-only request speaks; see VoiceResolver. */
    private Catalog.VoiceSpec choose(String name,String language,String country) {
        List<Catalog.VoiceSpec> installed = store.installedVoices();
        String selected = store.selectedVoice();
        return name != null && !name.isEmpty() ? VoiceResolver.named(installed,selected,name) : VoiceResolver.byLanguage(installed,selected,language,country);
    }
    private static boolean matches(Catalog.VoiceSpec voice,String language,String country) { return VoiceResolver.matches(voice,language,country); }
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
    @Override protected int onLoadLanguage(String lang,String country,String variant) {
        int result = onIsLanguageAvailable(lang,country,variant);
        if (result >= TextToSpeech.LANG_AVAILABLE) preload(choose(null,lang,country));
        return result;
    }
    @Override public List<Voice> onGetVoices() {
        ArrayList<Voice> voices = new ArrayList<>();
        List<Catalog.VoiceSpec> installed = store.installedVoices();
        String selected = store.selectedVoice();
        Set<String> ids = new HashSet<>(), defaults = new LinkedHashSet<>();
        for (Catalog.VoiceSpec voice : installed) {
            voices.add(voice(voice.id,voice,false)); ids.add(voice.id);
            if (!isOnline(voice) || voice.id.equals(selected)) defaults.add(VoiceResolver.defaultName(voice.languageTag));
        }
        // TextToSpeech.setLanguage() requires its default voice name to be listed here.
        for (String name : defaults) { Catalog.VoiceSpec voice = VoiceResolver.named(installed,selected,name); if (voice != null) voices.add(voice(name,voice,false)); }
        // Downloadable local voices, as Google TTS advertises them; Local Voice downloads only from its own UI.
        for (Catalog.Model model : Catalog.MODELS) if (!model.network && model.available)
            for (Catalog.VoiceSpec voice : model.voices) if (!ids.contains(voice.id)) voices.add(voice(voice.id,voice,true));
        return voices;
    }
    private static Voice voice(String name,Catalog.VoiceSpec spec,boolean notInstalled) {
        Catalog.Model model = Catalog.findModel(spec.modelId);
        Set<String> features = new HashSet<>();
        features.add(model.network ? TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS : TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
        if (notInstalled) features.add(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
        return new Voice(name,Locale.forLanguageTag(spec.languageTag),model.quality,model.latency,model.network,features);
    }
    @Override public int onIsValidVoiceName(String name) { return name != null && !name.isEmpty() && choose(name,"","") != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR; }
    @Override public int onLoadVoice(String name) {
        int result = onIsValidVoiceName(name);
        if (result == TextToSpeech.SUCCESS) preload(choose(name,"",""));
        return result;
    }
    /**
     * Warm-up for setLanguage()/setVoice() and service start: loads the local model on the render thread so the
     * first utterance does not pay model load. Never online; never cancels a request; failures surface at synthesis.
     */
    private void preload(Catalog.VoiceSpec voice) {
        if (voice == null || isOnline(voice)) return;
        Catalog.Model model = Catalog.findModel(voice.modelId);
        Map<String,String> config = new HashMap<>(model.config); config.putAll(voice.config);
        try {
            synth.execute(()->{
                long begin = SystemClock.elapsedRealtime();
                try { store.verifyInstalled(model.id); backend.load(model.id,model.backend,store.modelDirectory(model.id),config); }
                catch (Exception|LinkageError e) { Log.w("LocalVoice","Preload failed for "+model.id+": "+e.getClass().getSimpleName()); return; }
                Log.i("LocalVoiceTiming","preload model="+model.id+" ms="+(SystemClock.elapsedRealtime()-begin));
            });
        } catch (java.util.concurrent.RejectedExecutionException destroyed) {}
    }
    @Override public String onGetDefaultVoiceNameFor(String lang,String country,String variant) {
        Catalog.VoiceSpec voice = choose(null,lang,country);
        // TextToSpeech.setLanguage() turns this answer into a named voice, so it must apply the SEC-004 rule too.
        if (voice == null || (isOnline(voice) && !voice.id.equals(store.selectedVoice()))) return null;
        return VoiceResolver.defaultName(voice.languageTag);
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
        boolean completed = false;
        try {
            if (!model.network) try { store.verifyInstalled(model.id); } catch (java.io.IOException missing) { throw new RequestPolicy.NotInstalledException(missing); }
            Map<String,String> config = new HashMap<>(model.config); config.putAll(voice.config);
            // Offsets of segments and word markers index this original text (SynthesisCallback.rangeStart contract).
            String text = request.getCharSequenceText().toString();
            if (text.length() > 100000) throw new IllegalArgumentException("Utterance too long");
            float speed = SpeechRatePolicy.effective(request.getSpeechRate(),preferences.getFloat(SpeechRatePolicy.PREFERENCE,1f));
            // Network adapters render pitch natively (SSML); local PCM gets the Sonic pitch step in render().
            float pitch = model.network ? 1f : SpeechRatePolicy.pitch(request.getPitch());
            android.os.Bundle params = request.getParams();
            SpeechBackend.Options options = RequestPolicy.options(request.getPitch(),model.network,
                params == null ? null : params.getString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_TIMEOUT_MS),
                params == null ? null : params.getString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_RETRIES_COUNT));
            // Adapter-owned preprocessing (chunking=backend) gets whole sentences; others also get clause-cut first audio.
            List<SpeechPlan.Segment> segments = SpeechPlan.segments(text,!"backend".equals(config.get("chunking")),
                Integer.parseInt(config.getOrDefault("segmentLimit",Integer.toString(SpeechPlan.LIMIT))));
            Rendered.Queue queue = new Rendered.Queue();
            synth.execute(()->render(token,model,voice,config,text,segments,speed,pitch,options,started,queue));
            Playback playback = new Playback(callback,token,text,model.id,speed,started,queue);
            for (int index = 0; index < segments.size(); ) {
                Rendered rendered = queue.take(()->backend.cancelled(token));
                if (rendered == null) break;
                rendered.rethrow();
                index = rendered.index + 1;
                if (!(rendered.live ? playback.live(rendered,index == segments.size()) : playback.buffered(rendered,index == segments.size()))) break;
            }
            if (!backend.cancelled(token)) {
                // Content the model renders as nothing (symbols, punctuation) completes silently: clients such as Edge
                // Read Aloud skip the rest of a paragraph on any error.
                if(!segments.isEmpty() && playback.written == 0) Log.w("LocalVoice","No audio for this request from "+model.id);
                if (!playback.didStart && callback.start(24000,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS) return;
                callback.done(); completed = true;
                host.reportStatus("complete",0);
                Log.i("LocalVoiceTiming","done model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started));
            }
        } catch (Exception|LinkageError e) {
            Log.e("LocalVoice","Synthesis failed for "+model.id,e);
            if (!backend.cancelled(token)) { host.reportStatus("failed",0);callback.error(RequestPolicy.errorCode(e,model.network)); callback.done(); }
        } finally {
            if(backend.cancelled(token))host.reportStatus("stopped",0);
            if(cancelledByUiToken==token&&!callback.hasFinished()){callback.error(TextToSpeech.ERROR_SYNTHESIS);callback.done();}
            synchronized(requests){
                // A failed request must not keep the worker rendering its remaining segments; no later request has begun yet.
                if(!completed&&activeToken==token)backend.cancel();
                if(activeToken==token){activeToken=-1;activeModel=null;}
            }
        }
    }
    /**
     * Synthesis worker: renders every segment ahead of playback. Android blocks audioAvailable() once 500 ms is
     * queued, so synthesizing on the callback thread left a gap whenever a segment took longer than that to render.
     */
    private void render(long token,Catalog.Model model,Catalog.VoiceSpec voice,Map<String,String> config,String text,
                        List<SpeechPlan.Segment> segments,float speed,float pitch,SpeechBackend.Options options,long started,Rendered.Queue queue) {
        Rendered rendered = null;
        try {
            for (int index = 0; index < segments.size() && !backend.cancelled(token); index++) {
                SpeechPlan.Segment segment = segments.get(index);
                long begin = SystemClock.elapsedRealtime();
                Rendered current = rendered = new Rendered(index,segment);
                // Speed beyond the adapter's native range and local pitch are applied to its PCM (Sonic).
                ProsodySink prosody = new ProsodySink(current,SpeechRatePolicy.residualSpeed(speed),pitch,()->current.sampleRate,()->backend.cancelled(token));
                try {
                    backend.execute(token,model.id,model.backend,model.network ? getFilesDir() : store.modelDirectory(model.id),config,
                        text.substring(segment.start,segment.end),voice.languageTag,voice.speakerId,SpeechRatePolicy.nativeSpeed(speed),options,rate -> {
                            current.sampleRate = rate;
                            if (current.index != 0) return;
                            Log.i("LocalVoiceTiming","ready model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started));
                            // Later segments render ahead of playback; only the first one's latency is audible.
                            if (backend.streams()) { current.live = true; queue.put(current,()->backend.cancelled(token)); }
                        },prosody);
                    prosody.finish();
                } catch (Exception unspeakable) {
                    // Load (no sample rate yet) and online failures fail the request; text one segment's model rejects is
                    // skipped, because clients such as Edge Read Aloud drop the rest of the paragraph on any error.
                    if (model.network || current.sampleRate == 0 || backend.cancelled(token)) throw unspeakable;
                    Log.w("LocalVoice","Skipped segment "+index+" of "+model.id+": "+unspeakable.getClass().getSimpleName());
                    current.discard();
                }
                current.seal();
                if (backend.cancelled(token)) return;
                int rate = Math.max(1,current.sampleRate);
                Log.i("LocalVoiceTiming","segment "+index+"/"+segments.size()+" chars="+(segment.end-segment.start)+" synthMs="+(SystemClock.elapsedRealtime()-begin)
                    +" audioMs="+current.length*1000L/rate+(current.live ? " live" : " leadMs="+SpeechPlan.leadingSilence(current.pcm)*1000L/rate
                    +" trailMs="+SpeechPlan.trailingSilence(current.pcm)*1000L/rate));
                if (!current.live && !queue.put(current,()->backend.cancelled(token))) return;
            }
        } catch (Exception|LinkageError e) {
            if (rendered != null) rendered.fail(e);
            queue.fail(e);
        }
    }
    /** Learned speaking rate per model in seconds per {@link SpeechPlan#weight} unit at speed 1; prices live markers. */
    private final Map<String,Double> secondsPerWeight = new java.util.concurrent.ConcurrentHashMap<>();
    /** Callback-thread side of one request: shapes segment silence, places word markers and writes PCM16. */
    private final class Playback {
        private final SynthesisCallback callback; private final long token, started; private final String text, model; private final float speed;
        long written, firstWrite; boolean didStart;
        private final Rendered.Queue queue;
        Playback(SynthesisCallback callback,long token,String text,String model,float speed,long started,Rendered.Queue queue) {
            this.callback = callback; this.token = token; this.text = text; this.model = model; this.speed = speed; this.started = started; this.queue = queue;
        }
        /** A completely rendered segment: adapter word timing, else words spread over its voiced span. */
        boolean buffered(Rendered rendered,boolean last) {
            int rate = rendered.sampleRate;
            int[] shape = SpeechPlan.shape(rendered.pcm,rate,written == 0,last,rendered.segment.pauseMs);
            if (shape[1] == 0) {
                // Skipped or silent segment: keep its pause so the following words do not run on.
                queue.played(rendered.pcm.length);
                int pause = written == 0 || last ? 0 : SpeechPlan.ms(rendered.segment.pauseMs,Math.max(1,rate));
                return write(new float[pause],0,pause,false);
            }
            open(rate,rendered.index);
            int lead = SpeechPlan.leadingSilence(rendered.pcm), trail = SpeechPlan.trailingSilence(rendered.pcm);
            long voiced = Math.max(0,rendered.pcm.length-lead-trail);
            learn(rendered.segment,voiced,rate);
            for (SpeechPlan.Marker word : rendered.words)
                mark(written+Math.max(0,Math.min(shape[1],word.frame-shape[0])),rendered.segment.start+word.start,rendered.segment.start+word.end);
            if (rendered.words.isEmpty()) for (SpeechPlan.Marker word : SpeechPlan.estimate(text,rendered.segment,lead-shape[0],voiced))
                mark(written+word.frame,word.start,word.end);
            if (!write(rendered.pcm,shape[0],shape[1],true)) return false;
            queue.played(rendered.pcm.length-shape[1]);
            return write(new float[shape[2]],0,shape[2],false);
        }
        /** A streaming adapter's first segment: PCM leaves as it is produced; markers use the learned speaking rate. */
        boolean live(Rendered rendered,boolean last) throws Exception {
            int rate = rendered.sampleRate, next = 0, trail = 0;
            long onset = 0;
            List<SpeechPlan.Marker> words = null;
            for (float[] block; (block = rendered.next(()->backend.cancelled(token))) != null; ) {
                int from = 0;
                if (words == null) {
                    int lead = SpeechPlan.leadingSilence(block);
                    if (lead == block.length) continue;
                    from = Math.max(0,lead-SpeechPlan.ms(written == 0 ? SpeechPlan.LEAD_MS : SpeechPlan.WORD_PAUSE_MS,rate));
                    open(rate,rendered.index);
                    onset = written+lead-from;
                    double seconds = secondsPerWeight.getOrDefault(model,0.06)/speed;
                    words = SpeechPlan.estimate(text,rendered.segment,0,Math.round(SpeechPlan.weight(text,rendered.segment)*seconds*rate));
                }
                long end = written+block.length-from;
                for (; next < words.size() && onset+words.get(next).frame < end; next++)
                    mark(Math.max(written,onset+words.get(next).frame),words.get(next).start,words.get(next).end);
                if (!write(block,from,block.length-from,false)) return false;
                int silent = SpeechPlan.trailingSilence(block);
                trail = silent == block.length ? trail+silent : silent;
            }
            rendered.rethrow();
            if (backend.cancelled(token)) return false;
            if (words == null) return true;
            // The rate estimate ran long: the remaining words were spoken before the segment ended.
            for (; next < words.size(); next++) mark(Math.max(onset,written-trail-1),words.get(next).start,words.get(next).end);
            learn(rendered.segment,written-onset-trail,rate);
            int pad = last ? 0 : Math.max(0,SpeechPlan.ms(rendered.segment.pauseMs,rate)-trail);
            return write(new float[pad],0,pad,false);
        }
        private void open(int rate,int index) {
            if (!didStart) {
                if (callback.start(rate,AudioFormat.ENCODING_PCM_16BIT,1) != TextToSpeech.SUCCESS) throw new IllegalStateException("Client stopped");
                didStart = true;
            }
            long now = SystemClock.elapsedRealtime();
            if (firstWrite == 0) { firstWrite = now; Log.i("LocalVoiceTiming","firstPcm model="+model+" ms="+(now-started)); }
            else if (now-firstWrite-written*1000/rate > 0) Log.i("LocalVoiceTiming","underrun segment="+index+" ms="+(now-firstWrite-written*1000/rate));
        }
        private void learn(SpeechPlan.Segment segment,long voicedFrames,int rate) {
            double weight = SpeechPlan.weight(text,segment);
            if (weight < 20 || voicedFrames <= 0) return;
            double seconds = (double)voicedFrames/rate/weight*speed;
            secondsPerWeight.merge(model,seconds,(old,now)->old*0.5+now*0.5);
        }
        private void mark(long frame,int start,int end) { callback.rangeStart((int)Math.min(Integer.MAX_VALUE,frame),start,end); }
        /** counted: samples of a queued segment, released from the look-ahead budget as they are handed to Android. */
        private boolean write(float[] samples,int offset,int length,boolean counted) {
            int capacity = Math.max(2,Math.min(2048,callback.getMaxBufferSize()) & ~1);
            byte[] pcm = new byte[capacity];
            int position = 0;
            for (int i = offset; i < offset + length; i++) {
                if (backend.cancelled(token)) return false;
                int value = Math.round(Math.max(-1f,Math.min(1f,samples[i]))*32767f);
                pcm[position++] = (byte)value; pcm[position++] = (byte)(value >> 8);
                if (position == pcm.length) {
                    if (callback.audioAvailable(pcm,0,position) != TextToSpeech.SUCCESS) { backend.cancel(); return false; }
                    if (counted) queue.played(position/2);
                    position = 0;
                }
            }
            if (position != 0) {
                if (backend.cancelled(token)) return false;
                if (callback.audioAvailable(pcm,0,position) != TextToSpeech.SUCCESS) { backend.cancel(); return false; }
                if (counted) queue.played(position/2);
            }
            written += length;
            return true;
        }
    }
    /** SEC-004 and unknown-name fallback rules: VoiceResolver.forSynthesis. */
    private Catalog.VoiceSpec chooseForSynthesis(SynthesisRequest request) {
        android.os.Bundle params = request.getParams();
        boolean embeddedOnly = params != null && Boolean.parseBoolean(params.getString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS));
        return VoiceResolver.forSynthesis(store.installedVoices(),store.selectedVoice(),request.getVoiceName(),request.getLanguage(),request.getCountry(),embeddedOnly);
    }
    private static boolean isOnline(Catalog.VoiceSpec voice) {
        return Catalog.findModel(voice.modelId).network;
    }
    /** One segment's audio: collected whole, or handed over block by block while a streaming adapter renders it. */
    private static final class Rendered implements SpeechBackend.PcmSink {
        final int index; final SpeechPlan.Segment segment; volatile int sampleRate; volatile boolean live;
        final List<SpeechPlan.Marker> words = new ArrayList<>();
        private final java.util.ArrayDeque<float[]> blocks = new java.util.ArrayDeque<>();
        private boolean sealed; int length; float[] pcm = new float[0]; Throwable failure;
        Rendered(int index,SpeechPlan.Segment segment) { this.index = index; this.segment = segment; }
        // Adapters may reuse their buffers after write() returns.
        @Override public synchronized boolean write(float[] samples) { blocks.add(samples.clone()); length += samples.length; notifyAll(); return true; }
        @Override public synchronized void word(long sample,int start,int end) { words.add(new SpeechPlan.Marker(sample,start,end)); }
        synchronized void seal() {
            if (!live) { pcm = new float[length]; int at = 0; for (float[] block : blocks) { System.arraycopy(block,0,pcm,at,block.length); at += block.length; } blocks.clear(); }
            sealed = true; notifyAll();
        }
        synchronized void fail(Throwable failure) { this.failure = failure; sealed = true; notifyAll(); }
        /** Drops a buffered segment's partial audio; a live segment keeps what already played. */
        synchronized void discard() { if (!live) { blocks.clear(); length = 0; } }
        /** Live segments: the next block, or null once rendering ended or the request was cancelled. */
        synchronized float[] next(SpeechBackend.Cancellation cancellation) throws InterruptedException {
            while (blocks.isEmpty() && !sealed && !cancellation.isCancelled()) wait(20);
            return cancellation.isCancelled() ? null : blocks.poll();
        }
        synchronized void rethrow() throws Exception {
            if (failure instanceof LinkageError) throw (LinkageError)failure;
            if (failure != null) throw (Exception)failure;
        }
        /**
         * Hands rendered segments to the callback thread. Look-ahead stops once a few seconds of audio wait unplayed:
         * enough to hide the next segment's render time, little enough that Stop rarely finds native work in flight.
         */
        static final class Queue {
            private static final long AHEAD_MS = 4000;
            private final java.util.ArrayDeque<Rendered> items = new java.util.ArrayDeque<>();
            private long aheadSamples;
            synchronized boolean put(Rendered rendered,SpeechBackend.Cancellation cancellation) throws InterruptedException {
                while (aheadSamples > AHEAD_MS*rendered.sampleRate/1000 && !cancellation.isCancelled()) wait(20);
                if (cancellation.isCancelled()) return false;
                items.add(rendered); aheadSamples += rendered.pcm.length; notifyAll(); return true;
            }
            synchronized void fail(Throwable failure) { Rendered item = new Rendered(-1,null); item.failure = failure; items.add(item); notifyAll(); }
            /** Next segment, or null once the request is cancelled. */
            synchronized Rendered take(SpeechBackend.Cancellation cancellation) throws InterruptedException {
                while (items.isEmpty() && !cancellation.isCancelled()) wait(20);
                return cancellation.isCancelled() ? null : items.poll();
            }
            synchronized void played(long samples) { aheadSamples -= samples; notifyAll(); }
        }
    }
    @Override public void onDestroy() { backend.cancel();preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanges);store.removeListener(modelChanges);BackendStatus.clearController(this);host.close();new Thread(backend::close,"LocalVoice-close").start();synth.shutdown();super.onDestroy(); }
}
