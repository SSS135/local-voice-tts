package org.localvoice.tts;
import java.util.Locale;
import java.util.MissingResourceException;
/** Edge includes valid BCP47 numeric regions that have no ISO3166 alpha3 entry. */
final class LocaleTags {
    private LocaleTags() {}
    static String language(Locale locale) {
        try{return locale.getISO3Language();}catch(MissingResourceException unavailable){return locale.getLanguage();}
    }
    static String country(Locale locale) {
        try{return locale.getISO3Country();}catch(MissingResourceException unavailable){return locale.getCountry();}
    }
}
