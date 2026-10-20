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
     * Only the latest preload runs, after {@link #PRELOAD_SETTLE_MS}, and a request supersedes pending ones: clients
     * call onLoadLanguage (default locale) at bind and setVoice milliseconds later, and when the two resolve to
     * different models the first load (Kokoro 2.4-3 s on a Pixel 7 Pro) only delayed the second.
     */
    private void preload(Catalog.VoiceSpec voice) {
        long mine = preloads.incrementAndGet();
        if (voice == null || isOnline(voice)) return;
        Catalog.Model model = Catalog.findModel(voice.modelId);
        Map<String,String> config = new HashMap<>(model.config); config.putAll(voice.config);
        settle.postDelayed(()->{
          if (preloads.get() != mine) return;
          try {
            synth.execute(()->{
                if (preloads.get() != mine) return;
                long begin = SystemClock.elapsedRealtime();
                try { store.verifyInstalled(model.id); backend.load(model.id,model.backend,store.modelDirectory(model.id),config); }
                catch (Exception|LinkageError e) { Log.w("LocalVoice","Preload failed for "+model.id+": "+e.getClass().getSimpleName()); return; }
                Log.i("LocalVoiceTiming","preload model="+model.id+" ms="+(SystemClock.elapsedRealtime()-begin));
            });
          } catch (java.util.concurrent.RejectedExecutionException destroyed) {}
        },PRELOAD_SETTLE_MS);
    }
    private static final long PRELOAD_SETTLE_MS = 1000;
    private final java.util.concurrent.atomic.AtomicLong preloads = new java.util.concurrent.atomic.AtomicLong();
    private final android.os.Handler settle = new android.os.Handler(android.os.Looper.getMainLooper());
    @Override public String onGetDefaultVoiceNameFor(String lang,String country,String variant) {
        Catalog.VoiceSpec voice = choose(null,lang,country);
        // TextToSpeech.setLanguage() turns this answer into a named voice, so it must apply the SEC-004 rule too.
        if (voice == null || (isOnline(voice) && !voice.id.equals(store.selectedVoice()))) return null;
        return VoiceResolver.defaultName(voice.languageTag);
    }
    @Override protected void onStop() {
        Log.i("LocalVoiceTiming","stop active="+(activeToken>=0));
        synchronized(requests){backend.cancel();TailHandoff.Tail held=tail;if(held!=null&&held.stoppedAt==0)held.stoppedAt=SystemClock.elapsedRealtime();}
        if(host!=null)host.reportStatus("stopped",0);
    }
    /** Withheld end of the last completed request ({@link TailHandoff}); guarded by {@code requests}. */
    private TailHandoff.Tail tail;
    /** Callers seen queueing requests (QUEUE_ADD): no onDone round trip to hide, so no handoff for them. */
    private final Set<Integer> queuingCallers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Plays a tail nobody claimed (end of reading) off the synthesis thread; a new request waits for it. */
    private volatile Thread ownPlay;
    /**
     * Keeps a completed request current until the framework stops it (the next speak()'s QUEUE_FLUSH or the
     * client's stop(), both via onStop) or its handed audio has ended. A stop while that audio still played is a
     * user stop or seek: the tail stays only for the next request to judge. Otherwise a tail no request claims
     * within FLUSH_MS plays on the service's own track: Edge stops right after the last paragraph's onDone.
     */
    private void linger(TailHandoff.Tail held,long token) {
        synchronized(requests){tail=held;}
        long deadline=held.playEnd+TailHandoff.GRACE_MS;
        while(!backend.cancelled(token)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(5);
        if(backend.cancelled(token)&&(held.stoppedAt==0||held.stoppedAt<held.playEnd-TailHandoff.EARLY_MS))return;
        settle.postDelayed(()->playTail(held),TailHandoff.FLUSH_MS);
    }
    private void playTail(TailHandoff.Tail held) {
        long generation;
        synchronized(requests){if(tail!=held||activeToken>=0)return;tail=null;generation=backend.current();}
        Log.i("LocalVoiceTiming","handoff ownPlay ms="+held.pcm.length*1000L/held.rate);
        Thread player=new Thread(()->{
            short[] rest=TailHandoff.play(held,()->backend.cancelled(generation));
            if(rest!=null&&rest.length>0){
                long now=SystemClock.elapsedRealtime();
                TailHandoff.Tail remainder=new TailHandoff.Tail(rest,held.rate,held.text,held.model,held.uid,now,held.params);
                remainder.stoppedAt=now;
                synchronized(requests){if(tail==null)tail=remainder;}
            }
        },"LocalVoice-tail");
        ownPlay=player; player.start();
    }
    @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback) {
        preloads.incrementAndGet();
        long token; synchronized(requests){token=backend.begin();activeToken=token;}
        // An unclaimed tail playing on the service's own track ends (cancelled by begin(), keeping its remainder) first.
        Thread player = ownPlay;
        if (player != null) try { player.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
        TailHandoff.Tail claimed = null;
        Playback playback = null;
        String text = request.getCharSequenceText() == null ? "" : request.getCharSequenceText().toString();
        try {
            if (!model.network) try { store.verifyInstalled(model.id); } catch (java.io.IOException missing) { throw new RequestPolicy.NotInstalledException(missing); }
            Map<String,String> config = new HashMap<>(model.config); config.putAll(voice.config);
            // Offsets of segments and word markers index this original text (SynthesisCallback.rangeStart contract).
            if (text.length() > 100000) throw new IllegalArgumentException("Utterance too long");
            float speed = SpeechRatePolicy.effective(request.getSpeechRate(),preferences.getFloat(SpeechRatePolicy.PREFERENCE,1f));
            // Network adapters render pitch natively (SSML); local PCM gets the Sonic pitch step in render().
            float pitch = model.network ? 1f : SpeechRatePolicy.pitch(request.getPitch());
            android.os.Bundle params = request.getParams();
            SpeechBackend.Options options = RequestPolicy.options(request.getPitch(),model.network,
                params == null ? null : params.getString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_TIMEOUT_MS),
                params == null ? null : params.getString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_RETRIES_COUNT));
            // chunking=backend (adapter-owned preprocessing) gets whole sentences; chunking=stream (streaming whole-turn
            // adapters) gets whole paragraphs; others also get clause-cut first audio.
            String chunking = config.get("chunking");
            boolean stream = "stream".equals(chunking);
            int maxLimit = Integer.parseInt(config.getOrDefault("segmentLimit",Integer.toString(SpeechPlan.LIMIT)));
            SpeechPlan.Planner plan = new SpeechPlan.Planner(text,!stream && !"backend".equals(chunking));
            boolean any = plan.hasNext();
            int caller = request.getCallerUid();
            // Playback requests only (not synthesizeToFile); preference, per-request param and caller pattern gate it.
            boolean handoff = callback.getClass().getName().endsWith("PlaybackSynthesisCallback") && preferences.getBoolean(TailHandoff.PREFERENCE,true)
                && !(params != null && "false".equals(params.getString(TailHandoff.PREFERENCE))) && !queuingCallers.contains(caller);
            TailHandoff.Tail previous;
            synchronized(requests){previous=tail;tail=null;}
            claimed = previous != null && previous.continuedBy(text,caller,started) ? previous : null;
            // Continued without any stop: the request was queued behind the previous one (QUEUE_ADD), which then
            // lingered for nothing; later requests of this caller hold nothing back.
            if (claimed != null && claimed.stoppedAt == 0 && claimed.pausedText == null) queuingCallers.add(caller);
            Log.i("LocalVoiceTiming","request model="+model.id+" chars="+text.length()+" handoff="+handoff
                +(previous == null ? "" : " tailMs="+previous.pcm.length*1000L/previous.rate+" claimed="+(claimed != null)+" sinceStopMs="+(previous.stoppedAt == 0 ? -1 : started-previous.stoppedAt)+" vsPlayEndMs="+(started-previous.playEnd)));
            Rendered.Queue queue = new Rendered.Queue();
            if (any) synth.execute(()->render(token,model,voice,config,text,plan,stream,maxLimit,speed,pitch,options,started,queue));
            playback = new Playback(callback,token,text,model.id,speed,started,queue);
            if (claimed != null && !playback.prelude(claimed,model.id.equals(claimed.model))) return;
            for (boolean last = !any; !last; ) {
                Rendered rendered = queue.take(()->backend.cancelled(token));
                if (rendered == null) break;
                rendered.rethrow();
                last = rendered.last;
                // The end of the request is held back for the next one; a live segment is held only when it renders well
                // ahead of real time, else holding would delay its own first audio.
                if (last && handoff) {
                    double rtf = realtime.getOrDefault(model.id,1d);
                    int holdMs = TailHandoff.holdMs(firstAudio.getOrDefault(model.id,0d));
                    if (!rendered.live) playback.hold(holdMs,rendered.sampleRate);
                    else if (rtf < 0.5) playback.hold((int)Math.min(TailHandoff.MAX_HOLD_MS,holdMs/(1-rtf)),rendered.sampleRate);
                }
                if (!(rendered.live ? playback.live(rendered,last) : playback.buffered(rendered,last))) break;
            }
            if (!backend.cancelled(token)) {
                // Content the model renders as nothing (symbols, punctuation) completes silently: clients such as Edge
                // Read Aloud skip the rest of a paragraph on any error.
                if(any && playback.written == 0) Log.w("LocalVoice","No audio for this request from "+model.id);
                if (!playback.didStart && callback.start(24000,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS) return;
                TailHandoff.Tail held = playback.release(caller,params,Integer.parseInt(config.getOrDefault("paragraphPauseMs",Integer.toString(SpeechPlan.PARAGRAPH_PAUSE_MS))));
                if (backend.cancelled(token)) return;
                callback.done(); completed = true;
                host.reportStatus("complete",0);
                Log.i("LocalVoiceTiming","done model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started)+(held == null ? "" : " heldMs="+held.pcm.length*1000L/held.rate+" playEndInMs="+(held.playEnd-SystemClock.elapsedRealtime())));
                if (held != null) linger(held,token);
            }
        } catch (Exception|LinkageError e) {
            Log.e("LocalVoice","Synthesis failed for "+model.id,e);
            if (!backend.cancelled(token)) { host.reportStatus("failed",0);callback.error(RequestPolicy.errorCode(e,model.network)); callback.done(); }
        } finally {
            if(backend.cancelled(token))host.reportStatus("stopped",0);
            if(cancelledByUiToken==token&&!callback.hasFinished()){callback.error(TextToSpeech.ERROR_SYNTHESIS);callback.done();}
            synchronized(requests){
                // Stopped (Edge pause) while the previous paragraph's tail played and before any own word: only a resume
                // of this text replays the tail.
                if(claimed!=null&&!completed&&backend.cancelled(token)&&playback!=null&&playback.ownMarks==0&&tail==null){claimed.pausedText=text;tail=claimed;}
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
    private void render(long token,Catalog.Model model,Catalog.VoiceSpec voice,Map<String,String> config,String text,SpeechPlan.Planner plan,
                        boolean stream,int maxLimit,float speed,float pitch,SpeechBackend.Options options,long started,Rendered.Queue queue) {
        Rendered rendered = null;
        long playableMs = 0;
        try {
            for (int index = 0; plan.hasNext() && !backend.cancelled(token); index++) {
                int limit = stream ? maxLimit : SpeechPlan.limit(index,maxLimit);
                // A model slower than real time stalls whatever the size; shorter cuts would only cost prosody.
                if (index > 0 && !stream && realtime.getOrDefault(model.id,0d) < 1) {
                    long playing = queue.playbackStart;
                    limit = SpeechPlan.budgetLimit(limit,renderCost.getOrDefault(model.id,0d),playableMs-(playing == 0 ? 0 : SystemClock.elapsedRealtime()-playing));
                }
                SpeechPlan.Segment segment = plan.next(limit);
                long begin = SystemClock.elapsedRealtime();
                Rendered current = rendered = new Rendered(index,segment,!plan.hasNext());
                // Speed beyond the adapter's native range and local pitch are applied to its PCM (Sonic).
                ProsodySink prosody = new ProsodySink(current,SpeechRatePolicy.residualSpeed(speed),pitch,()->current.sampleRate,()->backend.cancelled(token));
                try {
                    backend.execute(token,model.id,model.backend,model.network ? getFilesDir() : store.modelDirectory(model.id),config,
                        text.substring(segment.start,segment.end),voice.languageTag,voice.speakerId,SpeechRatePolicy.nativeSpeed(speed),options,rate -> {
                            current.sampleRate = rate;
                            if (current.index != 0) return;
                            Log.i("LocalVoiceTiming","ready model="+model.id+" ms="+(SystemClock.elapsedRealtime()-started));
                            // Later segments render ahead of playback; only the first one's latency is audible.
                            if (backend.streams()) { current.live = true; current.timed = backend.timesWords(); queue.put(current,()->backend.cancelled(token)); }
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
                // First own audio ready (live: first block; buffered: whole segment), from request start: sizes the handoff.
                // Measured here, not at playback, which waits behind a previous paragraph's tail.
                if (index == 0 && current.length > 0)
                    firstAudio.merge(model.id,(double)((current.live ? current.firstBlockAt : SystemClock.elapsedRealtime())-started),(old,now)->old*0.5+now*0.5);
                if (backend.cancelled(token)) return;
                int rate = Math.max(1,current.sampleRate), chars = segment.end-segment.start;
                long synthMs = SystemClock.elapsedRealtime()-begin;
                if (chars > 0 && current.length > 0) renderCost.merge(model.id,(double)synthMs/chars,(old,now)->old*0.5+now*0.5);
                if (current.length > 0) realtime.merge(model.id,synthMs/(current.length*1000d/rate),(old,now)->old*0.5+now*0.5);
                int lead = current.live ? 0 : SpeechPlan.leadingSilence(current.pcm), trail = current.live ? 0 : SpeechPlan.trailingSilence(current.pcm);
                playableMs += Math.max(0,current.length-lead-trail)*1000L/rate+(current.length > 0 ? SpeechPlan.pauseMs(segment.pauseMs,speed) : 0);
                Log.i("LocalVoiceTiming","segment "+index+(current.last ? " last" : "")+" limit="+limit+" chars="+chars+" synthMs="+synthMs
                    +" audioMs="+current.length*1000L/rate+(current.live ? " live" : " leadMs="+lead*1000L/rate+" trailMs="+trail*1000L/rate));
                if (!current.live && !queue.put(current,()->backend.cancelled(token))) return;
            }
        } catch (Exception|LinkageError e) {
            if (rendered != null) rendered.fail(e);
            queue.fail(e);
        }
    }
    /** Learned speaking rate per model in seconds per {@link SpeechPlan#weight} unit at speed 1; prices live markers. */
    private final Map<String,Double> secondsPerWeight = new java.util.concurrent.ConcurrentHashMap<>();
    /** Learned first own audio per model in ms from request start, and render time per audio time: size the handoff. */
    private final Map<String,Double> firstAudio = new java.util.concurrent.ConcurrentHashMap<>(), realtime = new java.util.concurrent.ConcurrentHashMap<>();
    /** Learned render cost per model in ms per text char, per-call overhead included; sizes later segments. */
    private final Map<String,Double> renderCost = new java.util.concurrent.ConcurrentHashMap<>();
    /** Callback-thread side of one request: shapes segment silence, places word markers and writes PCM16. */
    private final class Playback {
        private final SynthesisCallback callback; private final long token, started; private final String text, model; private final float speed;
        long written, firstWrite; boolean didStart, ownAudio;
        int ownMarks;
        private final Rendered.Queue queue;
        /** Output rate after start(); uptime when the audio handed so far should have finished playing. */
        private int rate; private long playEnd;
        /** Handoff: the latest holdSamples stay unhanded (PCM16 in held) until release(); minTail of them become the tail. */
        private int holdSamples, minTail, heldCount;
        private short[] held = new short[0];
        /** A previous request's tail that waits for this request's own sample rate (different model). */
        private TailHandoff.Tail pendingTail;
        Playback(SynthesisCallback callback,long token,String text,String model,float speed,long started,Rendered.Queue queue) {
            this.callback = callback; this.token = token; this.text = text; this.model = model; this.speed = speed; this.started = started; this.queue = queue;
        }
        /** A completely rendered segment: adapter word timing, else words spread over its voiced span. */
        boolean buffered(Rendered rendered,boolean last) {
            int rate = rendered.sampleRate, pauseMs = SpeechPlan.pauseMs(rendered.segment.pauseMs,speed);
            int[] shape = SpeechPlan.shape(rendered.pcm,rate,written == 0,last,pauseMs);
            if (shape[1] == 0) {
                // Skipped or silent segment: keep its pause so the following words do not run on.
                queue.played(rendered.pcm.length);
                int pause = written == 0 || last ? 0 : SpeechPlan.ms(pauseMs,Math.max(1,rate));
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
        /**
         * A streaming adapter's first segment: PCM leaves as it is produced. Markers come from the adapter's word
         * timing when it reports one, else from the learned speaking rate. Silence after the latest sound is held
         * back: written as is once sound resumes, shaped like {@link SpeechPlan#shape} at the segment end.
         */
        boolean live(Rendered rendered,boolean last) throws Exception {
            int rate = rendered.sampleRate, next = 0;
            long onset = 0, origin = 0, received = 0;
            boolean sounded = false;
            List<SpeechPlan.Marker> words = null;
            List<float[]> held = new ArrayList<>();
            for (float[] block; (block = rendered.next(()->backend.cancelled(token))) != null; received += block.length) {
                int from = 0;
                if (!sounded) {
                    int lead = SpeechPlan.leadingSilence(block);
                    if (lead == block.length) continue;
                    from = Math.max(0,lead-SpeechPlan.ms(written == 0 ? SpeechPlan.LEAD_MS : SpeechPlan.WORD_PAUSE_MS,rate));
                    open(rate,rendered.index);
                    sounded = true;
                    // Output frame of the segment's sample 0; adapter word samples count from there.
                    origin = written-received-from;
                    onset = written+lead-from;
                    double seconds = secondsPerWeight.getOrDefault(model,0.06)/speed;
                    if (!rendered.timed) words = SpeechPlan.estimate(text,rendered.segment,0,Math.round(SpeechPlan.weight(text,rendered.segment)*seconds*rate));
                }
                int silent = Math.min(block.length-from,SpeechPlan.trailingSilence(block));
                if (silent == block.length-from) { held.add(from == 0 ? block : Arrays.copyOfRange(block,from,block.length)); continue; }
                for (float[] pause : held) if (!write(pause,0,pause.length,false)) return false;
                held.clear();
                long end = written+block.length-from-silent;
                if (rendered.timed) for (SpeechPlan.Marker word; (word = rendered.word(next)) != null && origin+word.frame < end; next++)
                    mark(Math.max(written,origin+word.frame),rendered.segment.start+word.start,rendered.segment.start+word.end);
                else for (; next < words.size() && onset+words.get(next).frame < end; next++)
                    mark(Math.max(written,onset+words.get(next).frame),words.get(next).start,words.get(next).end);
                if (!write(block,from,block.length-from-silent,false)) return false;
                held.add(Arrays.copyOfRange(block,block.length-silent,block.length));
            }
            rendered.rethrow();
            if (backend.cancelled(token)) return false;
            if (!sounded) return true;
            // Words reported or estimated past the last sound were spoken before the segment ended.
            if (rendered.timed) for (SpeechPlan.Marker word; (word = rendered.word(next)) != null; next++)
                mark(Math.max(onset,Math.min(origin+word.frame,written-1)),rendered.segment.start+word.start,rendered.segment.start+word.end);
            else for (; next < words.size(); next++) mark(Math.max(onset,written-1),words.get(next).start,words.get(next).end);
            learn(rendered.segment,written-onset,rate);
            int want = SpeechPlan.ms(last ? SpeechPlan.TAIL_MS : SpeechPlan.pauseMs(rendered.segment.pauseMs,speed),rate);
            for (float[] pause : held) {
                int keep = Math.min(pause.length,want);
                if (!write(pause,0,keep,false)) return false;
                want -= keep;
            }
            int pad = last ? 0 : want;
            return write(new float[pad],0,pad,false);
        }
        private void open(int rate,int index) {
            start(rate);
            if (pendingTail != null) {
                TailHandoff.Tail tail = pendingTail; pendingTail = null;
                if (!writeTail(TailHandoff.resample(tail.pcm,tail.rate,rate))) throw new IllegalStateException("Client stopped");
            }
            long now = SystemClock.elapsedRealtime();
            if (!ownAudio) {
                ownAudio = true;
                Log.i("LocalVoiceTiming","firstPcm model="+model+" ms="+(now-started)+(written > 0 ? " afterTailMs="+written*1000/rate : ""));
            }
            if (firstWrite == 0) { firstWrite = now; queue.playbackStart = now; }
            else if (now-firstWrite-written*1000/rate > 0) Log.i("LocalVoiceTiming","underrun segment="+index+" ms="+(now-firstWrite-written*1000/rate));
        }
        private void start(int rate) {
            if (didStart) return;
            if (callback.start(rate,AudioFormat.ENCODING_PCM_16BIT,1) != TextToSpeech.SUCCESS) throw new IllegalStateException("Client stopped");
            didStart = true; this.rate = rate;
        }
        /**
         * Plays the previous request's withheld tail first: now when this request has the same model (same rate),
         * else once this request's rate is known. Markers of this request then sit after the tail.
         */
        boolean prelude(TailHandoff.Tail tail,boolean sameModel) {
            if (!sameModel) { pendingTail = tail; return true; }
            start(tail.rate);
            long now = SystemClock.elapsedRealtime();
            firstWrite = now; queue.playbackStart = now;
            return writeTail(tail.pcm);
        }
        private boolean writeTail(short[] pcm) {
            // Edge resumes a paused paragraph from its last rangeStart; with none after onStart it skipped the whole
            // paragraph when paused during the tail. Mark the first word at the tail's start (not an own word).
            List<SpeechPlan.Word> words = SpeechPlan.words(text,0,text.length());
            if (!words.isEmpty()) callback.rangeStart(0,words.get(0).start,words.get(0).end);
            int capacity = Math.max(2,Math.min(2048,callback.getMaxBufferSize()) & ~1);
            byte[] bytes = new byte[capacity];
            for (int at = 0; at < pcm.length; ) {
                int n = Math.min(capacity/2,pcm.length-at);
                for (int i = 0; i < n; i++) { bytes[2*i] = (byte)pcm[at+i]; bytes[2*i+1] = (byte)(pcm[at+i] >> 8); }
                if (backend.cancelled(token) || !audio(bytes,2*n)) return false;
                at += n;
            }
            written += pcm.length;
            return true;
        }
        /** Holds back the end of this request: holdMs become the tail, plus up to SEARCH_MS to find a pause to cut at. */
        void hold(int holdMs,int rate) {
            minTail = SpeechPlan.ms(holdMs,rate); holdSamples = minTail+SpeechPlan.ms(TailHandoff.SEARCH_MS,rate);
        }
        /**
         * Hands the held audio up to a pause and returns the rest as a tail for the next request, ending in a
         * paragraph pause; null when nothing is held.
         */
        TailHandoff.Tail release(int caller,android.os.Bundle params,int paragraphMs) {
            if (holdSamples == 0 || heldCount == 0) return null;
            int split = TailHandoff.split(held,heldCount,rate,Math.min(heldCount,minTail));
            int pause = Math.max(0,SpeechPlan.ms(SpeechPlan.pauseMs(paragraphMs,speed)-SpeechPlan.TAIL_MS,rate));
            short[] tail = new short[heldCount-split+pause];
            System.arraycopy(held,split,tail,0,heldCount-split);
            if (!handShorts(held,split)) return null;
            holdSamples = 0; heldCount = 0;
            // Nothing handed (a request shorter than the hold): its audio "ends" now.
            return new TailHandoff.Tail(tail,rate,text,model,caller,Math.max(playEnd,SystemClock.elapsedRealtime()),params);
        }
        private boolean handShorts(short[] pcm,int length) {
            int capacity = Math.max(2,Math.min(2048,callback.getMaxBufferSize()) & ~1);
            byte[] bytes = new byte[capacity];
            for (int at = 0; at < length; ) {
                int n = Math.min(capacity/2,length-at);
                for (int i = 0; i < n; i++) { bytes[2*i] = (byte)pcm[at+i]; bytes[2*i+1] = (byte)(pcm[at+i] >> 8); }
                if (backend.cancelled(token) || !audio(bytes,2*n)) return false;
                at += n;
            }
            return true;
        }
        /** PCM16 bytes to Android, or into the held window whose overflow (oldest first) goes to Android. */
        private boolean hand(byte[] pcm,int length) {
            if (holdSamples == 0) return audio(pcm,length);
            int samples = length/2;
            if (heldCount+samples > held.length) held = Arrays.copyOf(held,Math.max(held.length*2,heldCount+samples+rate));
            for (int i = 0; i < samples; i++) held[heldCount+i] = (short)((pcm[2*i] & 255) | (pcm[2*i+1] << 8));
            heldCount += samples;
            int excess = heldCount-holdSamples;
            if (excess <= 0) return true;
            if (!handShorts(held,excess)) return false;
            System.arraycopy(held,excess,held,0,heldCount-excess); heldCount -= excess;
            return true;
        }
        private boolean audio(byte[] pcm,int length) {
            long now = SystemClock.elapsedRealtime();
            // Android plays handed audio in order: it starts when the previous audio ended or when it arrives.
            playEnd = Math.max(playEnd,now)+length/2*1000L/Math.max(1,rate);
            return callback.audioAvailable(pcm,0,length) == TextToSpeech.SUCCESS;
        }
        private void learn(SpeechPlan.Segment segment,long voicedFrames,int rate) {
            double weight = SpeechPlan.weight(text,segment);
            if (weight < 20 || voicedFrames <= 0) return;
            double seconds = (double)voicedFrames/rate/weight*speed;
            secondsPerWeight.merge(model,seconds,(old,now)->old*0.5+now*0.5);
        }
        private void mark(long frame,int start,int end) { ownMarks++; callback.rangeStart((int)Math.min(Integer.MAX_VALUE,frame),start,end); }
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
                    if (!hand(pcm,position)) { backend.cancel(); return false; }
                    if (counted) queue.played(position/2);
                    position = 0;
                }
            }
            if (position != 0) {
                if (backend.cancelled(token)) return false;
                if (!hand(pcm,position)) { backend.cancel(); return false; }
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
        final int index; final SpeechPlan.Segment segment; final boolean last; volatile int sampleRate;
        /** live: streamed block by block; timed: the adapter reports word timing ahead of the samples it marks. */
        volatile boolean live, timed;
        final List<SpeechPlan.Marker> words = new ArrayList<>();
        private final java.util.ArrayDeque<float[]> blocks = new java.util.ArrayDeque<>();
        private boolean sealed; int length; float[] pcm = new float[0]; Throwable failure;
        Rendered(int index,SpeechPlan.Segment segment,boolean last) { this.index = index; this.segment = segment; this.last = last; }
        // Adapters may reuse their buffers after write() returns.
        volatile long firstBlockAt;
        @Override public synchronized boolean write(float[] samples) { if (firstBlockAt == 0) firstBlockAt = SystemClock.elapsedRealtime(); blocks.add(samples.clone()); length += samples.length; notifyAll(); return true; }
        @Override public synchronized void word(long sample,int start,int end) { words.add(new SpeechPlan.Marker(sample,start,end)); }
        /** Adapter word marker {@code index} reported so far, else null. */
        synchronized SpeechPlan.Marker word(int index) { return index < words.size() ? words.get(index) : null; }
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
            /** Uptime of the request's first PCM write, 0 before it: playback progress for render budgets. */
            volatile long playbackStart;
            synchronized boolean put(Rendered rendered,SpeechBackend.Cancellation cancellation) throws InterruptedException {
                while (aheadSamples > AHEAD_MS*rendered.sampleRate/1000 && !cancellation.isCancelled()) wait(20);
                if (cancellation.isCancelled()) return false;
                items.add(rendered); aheadSamples += rendered.pcm.length; notifyAll(); return true;
            }
            synchronized void fail(Throwable failure) { Rendered item = new Rendered(-1,null,true); item.failure = failure; items.add(item); notifyAll(); }
            /** Next segment, or null once the request is cancelled. */
            synchronized Rendered take(SpeechBackend.Cancellation cancellation) throws InterruptedException {
                while (items.isEmpty() && !cancellation.isCancelled()) wait(20);
                return cancellation.isCancelled() ? null : items.poll();
            }
            synchronized void played(long samples) { aheadSamples -= samples; notifyAll(); }
        }
    }
    @Override public void onDestroy() { settle.removeCallbacksAndMessages(null);backend.cancel();preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanges);store.removeListener(modelChanges);BackendStatus.clearController(this);host.close();new Thread(backend::close,"LocalVoice-close").start();synth.shutdown();super.onDestroy(); }
}
