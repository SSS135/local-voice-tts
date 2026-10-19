package org.localvoice.tts;

import java.util.Collection;
import java.util.Locale;

/** Pure filter rules shared by the worker and small JVM state checks. */
final class VoiceFilter {
    private VoiceFilter() {}
    static String validLanguage(Collection<String> available,String requested){return available.contains(requested)?requested:"";}
    static boolean matches(String id,String label,String tag,String requested,String query,Locale displayLocale){
        if(!requested.isEmpty()&&!tag.equals(requested))return false;
        String text=label+" "+id+" "+tag+" "+Locale.forLanguageTag(tag).getDisplayName(displayLocale);
        return text.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }
}
