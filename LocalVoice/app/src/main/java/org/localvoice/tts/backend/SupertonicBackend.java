package org.localvoice.tts.backend;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.Map;
public final class SupertonicBackend extends SherpaBackend {
    private int numSteps=8;
    @Override void configure(OfflineTtsModelConfig target,File directory,Map<String,String> config) {
        OfflineTtsSupertonicModelConfig model = new OfflineTtsSupertonicModelConfig();
        model.setDurationPredictor(path(directory,config,"durationPredictor"));
        model.setTextEncoder(path(directory,config,"textEncoder")); model.setVectorEstimator(path(directory,config,"vectorEstimator"));
        model.setVocoder(path(directory,config,"vocoder")); model.setTtsJson(path(directory,config,"ttsJson"));
        model.setUnicodeIndexer(path(directory,config,"unicodeIndexer")); model.setVoiceStyle(path(directory,config,"voiceStyle"));
        target.setSupertonic(model);
        numSteps=Math.max(2,Math.min(16,Integer.parseInt(config.getOrDefault("numSteps","8"))));
    }
    @Override public void synthesize(String text,String languageTag,int speakerId,float speed,Cancellation cancellation,PcmSink sink) {
        GenerationConfig config = new GenerationConfig(); config.setSid(speakerId); config.setSpeed(speed); config.setNumSteps(numSteps);
        config.setExtra(java.util.Collections.singletonMap("lang",java.util.Locale.forLanguageTag(languageTag).getLanguage()));
        // Keep the typed method required by Sherpa JNI instead of an erased Java lambda.
        if (!cancellation.isCancelled()) tts.generateWithConfigAndCallback(text,config,
            new kotlin.jvm.functions.Function1<float[],Integer>() { @Override public Integer invoke(float[] samples) {
                return cancellation.isCancelled() ? 0 : (sink.write(samples) ? 1 : 0);
            }});
    }
}
