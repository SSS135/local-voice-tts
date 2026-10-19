package org.localvoice.tts;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure voice resolution over the installed voices and the voice selected in Local Voice.
 *
 * <p>{@code TextToSpeech.setLanguage()} stores the name from onGetDefaultVoiceNameFor in the client, and browsers call
 * it only when the page language changes. A concrete id there would pin the voice selected at that moment, so the
 * default for a language is a stable alias ({@code default:en}) that resolves at synthesis time.
 */
final class VoiceResolver {
    static final String DEFAULT_PREFIX = "default:";
    private VoiceResolver() {}

    /** Stable alias for the default voice of a language tag's language. */
    static String defaultName(String languageTag) { return DEFAULT_PREFIX + Locale.forLanguageTag(languageTag).getLanguage(); }
    /** Language of a default alias, or null for other names. */
    static String defaultLanguage(String name) {
        return name != null && name.startsWith(DEFAULT_PREFIX) && name.length() > DEFAULT_PREFIX.length() ? name.substring(DEFAULT_PREFIX.length()) : null;
    }

    /**
     * Voice a language-only request speaks. The voice picked in Local Voice is the system voice for its whole language:
     * clients such as browsers request the page/device region (en-US) and must still get a selected en-GB voice.
     * Country ranks the rest. An online voice is returned only when it is the selected one (SEC-004).
     */
    static Catalog.VoiceSpec byLanguage(List<Catalog.VoiceSpec> installed, String selected, String language, String country) {
        for (Catalog.VoiceSpec voice : installed) if (voice.id.equals(selected) && matches(voice,language,"")) return voice;
        return local(installed,language,country);
    }

    /** Explicitly named voice: installed id (retired ids map to their successor) or a default alias; else null. */
    static Catalog.VoiceSpec named(List<Catalog.VoiceSpec> installed, String selected, String name) {
        if (name == null || name.isEmpty()) return null;
        String language = defaultLanguage(name);
        if (language != null) return byLanguage(installed,selected,language,"");
        String id = Catalog.canonicalVoiceId(name);
        for (Catalog.VoiceSpec voice : installed) if (voice.id.equals(id)) return voice;
        return null;
    }

    /**
     * SEC-004: text goes online only for an online voice the client named or the user selected in Local Voice,
     * and never when the client requested embedded synthesis. An unknown or uninstalled name (e.g. a voice removed
     * since the client saved it) falls back to an installed local voice of the request language, never to an online one.
     */
    static Catalog.VoiceSpec forSynthesis(List<Catalog.VoiceSpec> installed, String selected, String name,
                                          String language, String country, boolean embeddedOnly) {
        Catalog.VoiceSpec voice;
        if (name == null || name.isEmpty()) voice = byLanguage(installed,selected,language,country);
        else if (defaultLanguage(name) != null) voice = byLanguage(installed,selected,defaultLanguage(name),country);
        else {
            voice = named(installed,selected,name);
            if (voice == null) return local(installed,language,country);
        }
        if (voice != null && embeddedOnly && isOnline(voice)) return local(installed,language,country);
        return voice;
    }

    /** Best installed on-device voice for the language, preferring the requested country. */
    static Catalog.VoiceSpec local(List<Catalog.VoiceSpec> installed, String language, String country) {
        Catalog.VoiceSpec languageOnly = null;
        for (Catalog.VoiceSpec voice : installed) {
            if (isOnline(voice) || !matches(voice,language,"")) continue;
            if (matches(voice,language,country)) return voice;
            if (languageOnly == null) languageOnly = voice;
        }
        return languageOnly;
    }

    static boolean isOnline(Catalog.VoiceSpec voice) { return Catalog.findModel(voice.modelId).network; }

    /** language tag -> {language, ISO3 language, country, ISO3 country}; parsed once, matched ~10^5 times per browser bind. */
    private static final Map<String,String[]> TAGS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Empty language or country matches any; both 2-letter and ISO3 codes are accepted. */
    static boolean matches(Catalog.VoiceSpec voice, String language, String country) {
        String[] tag = TAGS.computeIfAbsent(voice.languageTag,key->{Locale locale=Locale.forLanguageTag(key);return new String[]{locale.getLanguage(),LocaleTags.language(locale),locale.getCountry(),LocaleTags.country(locale)};});
        return (language == null || language.isEmpty() || language.equals(tag[0]) || language.equals(tag[1]))
            && (country == null || country.isEmpty() || country.equals(tag[2]) || country.equals(tag[3]));
    }
}
