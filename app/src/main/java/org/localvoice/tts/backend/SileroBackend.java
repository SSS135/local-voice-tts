package org.localvoice.tts.backend;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Silero v5_6_ru through the sherpa-onnx VITS path: the exported graph takes '|' text '~' symbol IDs,
 * speaker as sid, the Silero sentence type as emotion_id and speed as length_scale (native durations).
 * Stress, ё and homographs come from the Java port of Silero's own frontend.
 */
public final class SileroBackend extends SherpaBackend {
    private SileroStress stress;

    @Override public void load(File directory, Map<String,String> config) {
        long started = System.nanoTime();
        try {
            stress = new SileroStress(new File(path(directory, config, "frontendWeights")), new File(path(directory, config, "frontendData")));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid Silero frontend asset", invalid);
        }
        long frontend = System.nanoTime();
        super.load(directory, config);
        android.util.Log.i("LocalVoiceTiming", "sileroLoad frontendMs=" + (frontend - started) / 1_000_000 + " modelMs=" + (System.nanoTime() - frontend) / 1_000_000);
    }

    @Override void configure(OfflineTtsModelConfig target, File directory, Map<String,String> config) {
        OfflineTtsVitsModelConfig model = new OfflineTtsVitsModelConfig();
        model.setModel(path(directory, config, "model"));
        model.setTokens(path(directory, config, "tokens"));
        target.setVits(model);
    }

    @Override public void synthesize(String text, String languageTag, int speakerId, float speed, Cancellation cancellation, PcmSink sink) {
        boolean[] stopped = {false};
        for (SileroFrontend.Piece piece : SileroFrontend.pieces(text)) {
            if (cancellation.isCancelled() || stopped[0]) return;
            // Stress each piece just before synthesis so first audio waits for one sentence only.
            String encoded = SileroFrontend.encode(stress.accentuate(piece.text));
            GenerationConfig config = new GenerationConfig();
            config.setSid(speakerId); config.setSpeed(speed); config.setSilenceScale(1f);
            config.setExtra(Collections.singletonMap("emotion_id", Integer.toString(piece.type)));
            // Keep the typed method required by Sherpa JNI instead of an erased Java lambda.
            tts.generateWithConfigAndCallback(encoded, config,
                new kotlin.jvm.functions.Function1<float[],Integer>() { @Override public Integer invoke(float[] samples) {
                    if (cancellation.isCancelled() || stopped[0]) return 0;
                    stopped[0] = !sink.write(samples);
                    return stopped[0] ? 0 : 1;
                }});
        }
    }

    @Override public void close() { super.close(); stress = null; }
}
