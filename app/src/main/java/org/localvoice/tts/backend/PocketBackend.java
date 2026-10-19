package org.localvoice.tts.backend;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Shared Sherpa Pocket graph lifecycle. Each catalog entry pins its own frontend and voice. */
public class PocketBackend extends SherpaBackend {
    protected static final class Prepared {
        final String encoded;
        final int framesAfterEos;
        Prepared(String encoded,int framesAfterEos){this.encoded=encoded;this.framesAfterEos=framesAfterEos;}
    }
    private PcmWave.Clip reference;
    private int steps,maxFrames;
    private float temperature;

    @Override public void load(File directory,Map<String,String> config) {
        try {
            reference="true".equals(config.get("fixedVoice"))?new PcmWave.Clip(new float[1920],24000):PcmWave.read(new File(path(directory,config,"referenceAudio")),24000*12);
            loadFrontend(directory,config);
        } catch(IOException invalid) {
            throw new IllegalArgumentException("Invalid Pocket reference or frontend asset",invalid);
        }
        super.load(directory,config);
    }
    protected void loadFrontend(File directory,Map<String,String> config)throws IOException {}
    @Override void configure(OfflineTtsModelConfig target,File directory,Map<String,String> config) {
        OfflineTtsPocketModelConfig model=new OfflineTtsPocketModelConfig();
        model.setLmFlow(path(directory,config,"lmFlow"));
        model.setLmMain(path(directory,config,"lmMain"));
        model.setEncoder(path(directory,config,"encoder"));
        model.setDecoder(path(directory,config,"decoder"));
        model.setTextConditioner(path(directory,config,"textConditioner"));
        model.setVocabJson(path(directory,config,"vocabJson"));
        model.setTokenScoresJson(path(directory,config,"tokenScoresJson"));
        model.setVoiceEmbeddingCacheCapacity(1);
        target.setPocket(model);
        steps=Integer.parseInt(config.getOrDefault("numSteps","1"));
        maxFrames=Integer.parseInt(config.getOrDefault("maxFrames","500"));
        temperature=Float.parseFloat(config.getOrDefault("temperature","0.3"));
        if(steps<1||steps>16||maxFrames<1||maxFrames>500||!Float.isFinite(temperature)||temperature<=0||temperature>1.5f)
            throw new IllegalArgumentException("Unsupported Pocket generation configuration");
    }
    protected Prepared prepare(String text) {
        String prepared=PocketPrompt.prepare(text);
        return new Prepared(prepared,PocketPrompt.words(prepared)<=4?3:1);
    }
    protected java.util.List<Prepared> prepareChunks(String text,Cancellation cancellation){
        java.util.List<Prepared> chunks=new java.util.ArrayList<>();
        for(String chunk:PocketPrompt.boundedChunks(text,240)){
            if(cancellation.isCancelled())return java.util.Collections.emptyList();
            chunks.add(prepare(chunk));
        }
        return chunks;
    }
    // Native Pocket decodes and emits its latent frames in 15-frame chunks after generation.
    @Override public boolean streams(){return true;}
    @Override public void synthesize(String text,String languageTag,int speakerId,float speed,Cancellation cancellation,PcmSink sink) {
        if(cancellation.isCancelled())return;
        for(Prepared prepared:prepareChunks(text,cancellation)){
            if(cancellation.isCancelled())return;
            if(!synthesizePrepared(prepared,speed,cancellation,sink))return;
        }
    }
    private boolean synthesizePrepared(Prepared prepared,float speed,Cancellation cancellation,PcmSink sink){
        if(cancellation.isCancelled())return false;
        if(prepared.encoded.isEmpty())return true;
        GenerationConfig config=new GenerationConfig();
        config.setReferenceAudio(reference.samples);config.setReferenceSampleRate(reference.sampleRate);
        config.setNumSteps(steps);config.setSpeed(1f);config.setSilenceScale(1f);
        Map<String,String> extra=new HashMap<>();
        extra.put("max_frames",Integer.toString(maxFrames));
        extra.put("frames_after_eos",Integer.toString(prepared.framesAfterEos));
        extra.put("temperature",Float.toString(temperature));
        extra.put("max_reference_audio_len","12");
        // The outer service bounds real text. Encoded PUA token IDs must remain one native sentence.
        extra.put("max_char_in_sentence","4096");extra.put("min_char_in_sentence","1");extra.put("chunk_size","15");
        config.setExtra(extra);
        PcmTimeStretch stretch=new PcmTimeStretch(sampleRate(),speed);
        boolean[] stopped={false};
        tts.generateWithConfigAndCallback(prepared.encoded,config,
            new kotlin.jvm.functions.Function1<float[],Integer>() {
                @Override public Integer invoke(float[] pcm) {
                    if(cancellation.isCancelled()||stopped[0])return 0;
                    stopped[0]=!stretch.write(pcm,cancellation,sink);
                    return stopped[0]?0:1;
                }
            });
        if(!cancellation.isCancelled()&&!stopped[0]&&!stretch.finish(cancellation,sink))
            throw new IllegalStateException("Pocket PCM consumer stopped");
        return !cancellation.isCancelled()&&!stopped[0];
    }
    @Override public void close(){super.close();reference=null;}
}
