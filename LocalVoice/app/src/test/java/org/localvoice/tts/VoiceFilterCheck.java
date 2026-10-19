package org.localvoice.tts;
import java.util.*;

public final class VoiceFilterCheck {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        List<String> locales=Arrays.asList("","en-US","en-GB","ru-RU","es-419");
        check(VoiceFilter.validLanguage(locales,"en-US").equals("en-US"),"English filter survives catalog update");
        check(VoiceFilter.validLanguage(locales,"ru-RU").equals("ru-RU"),"Russian filter survives new English voices");
        check(VoiceFilter.validLanguage(Arrays.asList("","en-US"),"ru-RU").isEmpty(),"removed voice language resets to All");
        check(VoiceFilter.validLanguage(Collections.emptyList(),"en-US").isEmpty(),"empty installed models has valid All filter");
        check(VoiceFilter.matches("edge:en-US-AriaNeural","Microsoft Aria","en-US","","english",Locale.ENGLISH),"English discoverable by language name");
        check(VoiceFilter.matches("edge:en-US-AriaNeural","Microsoft Aria","en-US","en-US"," ARIA ",Locale.ENGLISH),"query trim and case insensitive");
        check(!VoiceFilter.matches("edge:en-US-AriaNeural","Microsoft Aria","en-US","ru-RU","aria",Locale.ENGLISH),"filter and query intersect");
        check(VoiceFilter.matches("supertonic:ru","Стиль 1","ru-RU","ru-RU","стиль",Locale.ENGLISH),"Cyrillic names searchable");
        check(VoiceFilter.matches("edge:es-419-LorenzoNeural","Lorenzo","es-419","","419",Locale.ENGLISH),"numeric-region voice searchable");
        check(!VoiceFilter.matches("kokoro-en:af_heart","Kokoro Heart","en-US","","not-a-voice",Locale.ENGLISH),"no matches explicit empty result");
        check(VoiceFilter.matches("kokoro-en:af_heart","Kokoro Heart","en-US","","",Locale.ENGLISH),"blank query preserves all matching voices");
        Locale previous=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));check(VoiceFilter.matches("id","INDIGO","en-US","","indigo",Locale.ENGLISH),"search case conversion independent of device Turkish locale");}finally{Locale.setDefault(previous);}
        PreviewSession previews=new PreviewSession();String first=previews.begin("voice-a");check(previews.accepts(first),"current preview accepts callbacks");String second=previews.begin("voice-b");check(!previews.accepts(first),"replaced preview callback ignored");check(previews.accepts(second)&&previews.voice().equals("voice-b"),"new preview identity preserved");previews.clear();check(!previews.accepts(second)&&previews.voice().isEmpty(),"stop invalidates callbacks immediately");String third=previews.begin("voice-b");check(!third.equals(second),"new request for same voice has unique callback token");previews.close();check(!previews.accepts(third)&&previews.voice().isEmpty(),"destroyed UI rejects pending callbacks");check(previews.begin("voice-c").isEmpty(),"destroyed UI cannot start another preview");check(!previews.accepts(""),"empty callback never accepted");
        System.out.println("PASS: "+checks+" UI state cases (catalog replacement/removal/empty, English/search/locale rules, preview replacement/stop/destroy callback isolation)");
    }
}
