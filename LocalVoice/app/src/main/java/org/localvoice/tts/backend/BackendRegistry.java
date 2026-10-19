package org.localvoice.tts.backend;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;
public final class BackendRegistry {
    private static final Map<String,Supplier<SpeechBackend>> FACTORIES = new HashMap<>();
    static { FACTORIES.put("sherpa-kokoro",KokoroBackend::new); FACTORIES.put("sherpa-supertonic",SupertonicBackend::new); FACTORIES.put("edge",EdgeBackend::new); FACTORIES.put("sherpa-pocket",PocketBackend::new); FACTORIES.put("sherpa-pocket-shtorm",ShtormBackend::new); FACTORIES.put("sherpa-silero",SileroBackend::new); }
    public static boolean known(String id) { return FACTORIES.containsKey(id); }
    public static SpeechBackend create(String id) {
        Supplier<SpeechBackend> factory = FACTORIES.get(id);
        if (factory == null) throw new IllegalArgumentException("Unknown backend: " + id);
        return factory.get();
    }
}
