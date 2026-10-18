package org.localvoice.tts.backend;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.Map;

abstract class SherpaBackend implements SpeechBackend {
    OfflineTts tts;
    abstract void configure(OfflineTtsModelConfig target, File directory, Map<String,String> config);
    static String path(File directory, Map<String,String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing backend config: " + key);
        return new File(directory, value).getAbsolutePath();
    }
    @Override public void load(File directory, Map<String,String> config) {
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        int threads=Integer.parseInt(config.getOrDefault("numThreads","2"));
        model.setNumThreads(Math.max(1,Math.min(4,threads))); model.setProvider("cpu");
        configure(model, directory, config);
        OfflineTtsConfig settings = new OfflineTtsConfig();
        settings.setModel(model); settings.setMaxNumSentences(1);
        tts = new OfflineTts(null, settings);
        if (tts.sampleRate() <= 0) { close(); throw new IllegalStateException("Model initialization failed"); }
    }
    @Override public int sampleRate() { return tts.sampleRate(); }
    @Override public void close() { if (tts != null) { tts.free(); tts = null; } }
}
