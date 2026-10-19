package org.localvoice.tts;

import android.speech.tts.TextToSpeech;
import java.io.FileInputStream;
import java.util.*;
import org.localvoice.tts.backend.EdgeCatalogData;
import org.localvoice.tts.backend.EdgeRetry;
import org.localvoice.tts.backend.SpeechBackend;

/** Voice-name stability (retired ids, default aliases, unknown-name fallback), SEC-004, sample-text codes, request options and error codes. */
public final class VoiceStabilityCheck {
    @org.junit.Test public void check() throws Exception {
        try (FileInputStream in = new FileInputStream("src/main/assets/edge-voices-snapshot.json")) {
            Catalog.replaceEdgeVoices(EdgeCatalogData.load(in,"bundled",()->false));
        }
        Catalog.VoiceSpec heart = voice("kokoro-en:af_heart"), alba = voice("pocket-en-v2:alba"), xenia = voice("silero-ru:xenia"), german = voice("edge:de-DE-KatjaNeural");
        List<Catalog.VoiceSpec> installed = Arrays.asList(heart,alba,xenia,german);

        check(Catalog.canonicalVoiceId("pocket-en-alba:alba").equals(alba.id),"retired Pocket id not mapped");
        check(Catalog.canonicalVoiceId(alba.id).equals(alba.id) && Catalog.canonicalVoiceId("gone:voice").equals("gone:voice"),"canonical id changed a current/unknown id");
        check(VoiceResolver.defaultName("en-GB").equals("default:en") && "en".equals(VoiceResolver.defaultLanguage("default:en")),"default alias format");
        check(VoiceResolver.defaultLanguage(alba.id) == null && VoiceResolver.defaultLanguage("default:") == null,"non-alias parsed as alias");

        check(VoiceResolver.named(installed,alba.id,"pocket-en-alba:alba") == alba,"retired id not resolved");
        check(VoiceResolver.named(installed,alba.id,"default:en") == alba,"default alias ignores the selected voice");
        check(VoiceResolver.named(installed,heart.id,"default:en") == heart,"default alias must follow a changed selection");
        check(VoiceResolver.named(installed,alba.id,"default:de") == null,"default alias spoke an unselected online voice");
        check(VoiceResolver.named(installed,german.id,"default:de") == german,"default alias misses the selected online voice");
        check(VoiceResolver.named(installed,alba.id,"kokoro-en:af_bella") == null,"uninstalled voice reported valid");

        check(VoiceResolver.forSynthesis(installed,alba.id,"pocket-en-alba:alba","eng","GBR",false) == alba,"retired id not spoken");
        check(VoiceResolver.forSynthesis(installed,alba.id,"kokoro-en:af_bella","eng","USA",false) == heart,"uninstalled name must fall back to a local voice of its language");
        check(VoiceResolver.forSynthesis(installed,german.id,"gone:voice","deu","DEU",false) == null,"unknown name fell back to an online voice");
        check(VoiceResolver.forSynthesis(installed,alba.id,"default:en","eng","USA",false) == alba,"alias request");
        check(VoiceResolver.forSynthesis(installed,alba.id,null,"deu","DEU",false) == null,"SEC-004: unselected online voice for a locale request");
        check(VoiceResolver.forSynthesis(installed,german.id,null,"deu","DEU",false) == german,"selected online voice");
        check(VoiceResolver.forSynthesis(installed,german.id,null,"deu","DEU",true) == null,"embedded-only request used an online voice");
        check(VoiceResolver.forSynthesis(installed,alba.id,german.id,"deu","DEU",false) == german,"explicitly named online voice");
        check(VoiceResolver.forSynthesis(installed,alba.id,null,"rus","RUS",false) == xenia,"local language");
        check(VoiceResolver.forSynthesis(installed,alba.id,null,"eng","USA",false) == alba,"selected voice covers its whole language");

        check("Тихий вечер наступает у реки.".equals(SampleTextActivity.sample("ru")) && SampleTextActivity.sample("rus") != null && SampleTextActivity.sample("RU") != null,"Russian sample codes");
        check(SampleTextActivity.sample("en") != null && SampleTextActivity.sample("eng") != null,"English sample codes");
        check(SampleTextActivity.sample("fr") == null && SampleTextActivity.sample(null) == null,"unsupported sample language must defer to Settings");

        SpeechBackend.Options online = RequestPolicy.options(150,true,"5000","2"), local = RequestPolicy.options(150,false,null,null), invalid = RequestPolicy.options(0,true,"soon","-3");
        check(online.pitch == 1.5f && online.networkTimeoutMs == 5000 && online.networkRetries == 2,"client network keys/pitch");
        check(local.pitch == 1f && local.networkTimeoutMs == RequestPolicy.DEFAULT_NETWORK_TIMEOUT_MS && local.networkRetries == -1,"local defaults");
        check(invalid.pitch == 1f && invalid.networkTimeoutMs == RequestPolicy.DEFAULT_NETWORK_TIMEOUT_MS && invalid.networkRetries == -1,"invalid keys must use defaults");

        check(RequestPolicy.errorCode(new EdgeRetry.ExhaustedException(true,null),true) == TextToSpeech.ERROR_NETWORK_TIMEOUT,"timeout code");
        check(RequestPolicy.errorCode(new EdgeRetry.ExhaustedException(false,null),true) == TextToSpeech.ERROR_NETWORK,"retry count code");
        check(RequestPolicy.errorCode(new RuntimeException(new EdgeRetry.FatalException("403")),true) == TextToSpeech.ERROR_SERVICE,"fatal online code");
        check(RequestPolicy.errorCode(new RequestPolicy.NotInstalledException(null),false) == TextToSpeech.ERROR_NOT_INSTALLED_YET,"missing files code");
        check(RequestPolicy.errorCode(new IllegalStateException(),false) == TextToSpeech.ERROR_SYNTHESIS && RequestPolicy.errorCode(new java.io.IOException(),true) == TextToSpeech.ERROR_NETWORK,"default codes");
        System.out.println("PASS voice stability, SEC-004, sample text, request options and error codes");
    }
    private static Catalog.VoiceSpec voice(String id) { Catalog.VoiceSpec voice = Catalog.findVoice(id); if (voice == null) throw new AssertionError("fixture voice missing: " + id); return voice; }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
