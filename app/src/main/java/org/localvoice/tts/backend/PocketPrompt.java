package org.localvoice.tts.backend;

import java.util.Locale;

/** Training-time prompt preparation, adapted from the pinned MIT Pocket TTS source. */
final class PocketPrompt {
    private static final String STRONG=".!?\u2026",WEAK=",;:-\u2013\u2014",CLOSERS="\"'\u201d\u2019)]\u00bb";
    private PocketPrompt() {}
    static String prepare(String text) {
        text=trim(text).replace('\n',' ').replace('\r',' ').replace("  "," ");
        if(text.isEmpty())return text;
        int first=text.codePointAt(0),length=Character.charCount(first);
        if(!Character.isUpperCase(first))
            text=text.substring(0,length).toUpperCase(Locale.ROOT)+text.substring(length);
        int coreEnd=text.length();
        while(coreEnd>0&&(CLOSERS.indexOf(text.charAt(coreEnd-1))>=0||text.charAt(coreEnd-1)==' '))coreEnd--;
        if(coreEnd==0||STRONG.indexOf(text.charAt(coreEnd-1))>=0)return text;
        if(WEAK.indexOf(text.charAt(coreEnd-1))>=0) {
            int end=coreEnd;
            while(end>0&&(WEAK.indexOf(text.charAt(end-1))>=0||text.charAt(end-1)==' '))end--;
            return text.substring(0,end)+"."+trim(text.substring(coreEnd));
        }
        return text+".";
    }
    static int words(String text) {
        int words=0;boolean inside=false;
        for(int at=0;at<text.length();) {
            int c=text.codePointAt(at);at+=Character.charCount(c);
            if(space(c))inside=false;else if(!inside){words++;inside=true;}
        }
        return words;
    }
    static java.util.List<String> boundedChunks(String text){
        return boundedChunks(text,Integer.MAX_VALUE);
    }
    static java.util.List<String> boundedChunks(String text,int utf8Limit){
        java.util.List<String> chunks=new java.util.ArrayList<>();
        for(int start=0;start<text.length();){
            // Reserve uppercase expansion and punctuation; English bytes also bound byte-fallback IDs.
            int end=start,bytes=0;
            while(end<text.length()){
                int cp=text.codePointAt(end),width=Character.charCount(cp),count=cp<128?1:cp<2048?2:cp<65536?3:4;
                if(end+width-start>176||bytes+count>utf8Limit)break;
                end+=width;bytes+=count;
            }
            if(end==start)throw new IllegalArgumentException("Pocket text chunk budget is too small");
            if(end<text.length()){
                if(Character.isHighSurrogate(text.charAt(end-1)))end--;
                int boundary=-1;
                for(int i=start;i<end;i++)if(space(text.charAt(i)))boundary=i+1;
                if(boundary>start)end=boundary;
            }
            String part=trim(text.substring(start,end));if(!part.isEmpty())chunks.add(part);start=end;
        }
        return chunks;
    }
    private static boolean space(int c){return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85;}
    private static String trim(String text) {
        int start=0,end=text.length();
        while(start<end&&space(text.codePointAt(start)))start+=Character.charCount(text.codePointAt(start));
        while(end>start&&space(text.codePointBefore(end)))end-=Character.charCount(text.codePointBefore(end));
        return text.substring(start,end);
    }
}
