package org.localvoice.tts.backend;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.Map;
public final class KokoroBackend extends SherpaBackend {
    @Override void configure(OfflineTtsModelConfig target, File directory, Map<String,String> config) {
        OfflineTtsKokoroModelConfig model = new OfflineTtsKokoroModelConfig();
        model.setModel(path(directory, config,"model")); model.setVoices(path(directory,config,"voices"));
        model.setTokens(path(directory,config,"tokens")); model.setDataDir(path(directory,config,"dataDir"));
        model.setLang(config.getOrDefault("lang","en-us")); target.setKokoro(model);
        if (config.containsKey("lexicon")) {
            String[] files=config.get("lexicon").split(",");
            for(int i=0;i<files.length;i++) files[i]=new File(directory,files[i]).getAbsolutePath();
            model.setLexicon(String.join(",",files));
        }
    }
    @Override public void synthesize(String text,String languageTag,int speakerId,float speed,Cancellation cancellation,PcmSink sink) {
        // JNI looks up invoke(float[]) specifically; an erased Java lambda has only invoke(Object).
        if (!cancellation.isCancelled()) tts.generateWithCallback(text,speakerId,speed,
            new kotlin.jvm.functions.Function1<float[],Integer>() { @Override public Integer invoke(float[] samples) {
                return cancellation.isCancelled() ? 0 : (sink.write(samples) ? 1 : 0);
            }});
    }
}
