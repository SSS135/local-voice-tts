package org.localvoice.tts;

import android.speech.tts.TextToSpeech;
import java.io.FileInputStream;
import java.util.*;
import org.localvoice.tts.backend.EdgeCatalogData;

/** isLanguageAvailable must match what a locale-only request speaks: unselected online voices do not count (SEC-004). */
public final class LocaleAvailabilityCheck {
    @org.junit.Test public void check() throws Exception {
        try (FileInputStream in = new FileInputStream("src/main/assets/edge-voices-snapshot.json")) {
            Catalog.replaceEdgeVoices(EdgeCatalogData.load(in,"bundled",()->false));
        }
        Catalog.VoiceSpec local = Catalog.findVoice("kokoro-en:af_heart"), german = Catalog.findVoice("edge:de-DE-KatjaNeural");
        if (local == null || german == null) throw new AssertionError("fixture voices missing from catalog");
        List<Catalog.VoiceSpec> installed = new ArrayList<>(Catalog.findModel("edge").voices);
        installed.add(local);
        String selectedLocal = local.id;
        check(LocalVoiceService.availability(installed,selectedLocal,"deu","DEU") == TextToSpeech.LANG_MISSING_DATA,"unselected online-only language reported available");
        check(LocalVoiceService.availability(installed,german.id,"deu","DEU") == TextToSpeech.LANG_COUNTRY_AVAILABLE,"selected online voice not available");
        check(LocalVoiceService.availability(installed,german.id,"deu","AUT") == TextToSpeech.LANG_AVAILABLE,"selected online voice must cover its whole language");
        check(LocalVoiceService.availability(installed,selectedLocal,"eng","USA") == TextToSpeech.LANG_COUNTRY_AVAILABLE,"local voice not available");
        check(LocalVoiceService.availability(installed,selectedLocal,"eng","GBR") == TextToSpeech.LANG_AVAILABLE,"local language without country variant");
        check(LocalVoiceService.availability(installed,selectedLocal,"xxx","") == TextToSpeech.LANG_NOT_SUPPORTED,"unknown language");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
