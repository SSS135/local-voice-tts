package org.localvoice.tts.backend;

import java.util.*;

/** Expected Russian cardinal wording and final prompt/chunk boundaries, without JNI or model weights. */
public final class ShtormTextCheck {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    interface Action{void run();}
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void equal(String actual,String expected){check(actual.equals(expected),"expected ["+expected+"] got ["+actual+"]");}
    static void reject(Action action){try{action.run();throw new AssertionError("Expected unsupported numeric format");}catch(IllegalArgumentException expected){checks++;}}
    static void cardinal(long value,String expected){equal(RussianCardinals.spellout(value),expected);}
    public static void main(String[] args){
        cardinal(0,"ноль");cardinal(1,"один");cardinal(2,"два");cardinal(11,"одиннадцать");cardinal(14,"четырнадцать");cardinal(21,"двадцать один");cardinal(40,"сорок");cardinal(90,"девяносто");
        cardinal(100,"сто");cardinal(200,"двести");cardinal(300,"триста");cardinal(400,"четыреста");cardinal(500,"пятьсот");cardinal(600,"шестьсот");cardinal(700,"семьсот");cardinal(800,"восемьсот");cardinal(900,"девятьсот");
        cardinal(1000,"тысяча");cardinal(2000,"две тысячи");cardinal(5000,"пять тысяч");cardinal(11000,"одиннадцать тысяч");cardinal(14000,"четырнадцать тысяч");cardinal(21000,"двадцать одна тысяча");cardinal(22000,"двадцать две тысячи");cardinal(25000,"двадцать пять тысяч");cardinal(101000,"сто одна тысяча");cardinal(111000,"сто одиннадцать тысяч");cardinal(112000,"сто двенадцать тысяч");
        cardinal(1000000,"один миллион");cardinal(2000000,"два миллиона");cardinal(5000000,"пять миллионов");cardinal(11000000,"одиннадцать миллионов");cardinal(14000000,"четырнадцать миллионов");cardinal(21000000,"двадцать один миллион");cardinal(22000000,"двадцать два миллиона");
        cardinal(999999999,"девятьсот девяносто девять миллионов девятьсот девяносто девять тысяч девятьсот девяносто девять");cardinal(-1001,"минус тысяча один");
        cardinal(1000000000,"один миллиард");cardinal(-1000000000,"минус один миллиард");cardinal(1000000000000000L,"один квадриллион");
        check(RussianCardinals.spellout(Long.MIN_VALUE).startsWith("минус девять"),"Long.MIN_VALUE spoken");check(RussianCardinals.spellout(Long.MAX_VALUE).startsWith("девять квинтиллионов"),"Long.MAX_VALUE spoken");
        equal(PocketPrompt.prepare("  привет\nмир  "),"Привет мир.");
        equal(PocketPrompt.prepare("привет,\""),"Привет.\"");
        equal(PocketPrompt.prepare("Привет!"),"Привет!");
        check(PocketPrompt.words("один\u00a0два\tтри")==3,"Unicode whitespace words");
        String expanded=RussianNormalizer.apply(repeat("999999999 ",14).trim());
        List<String> chunks=PocketPrompt.boundedChunks(expanded);check(chunks.size()>1,"expanded text rechunked");
        String joined=String.join(" ",chunks);equal(joined,expanded);
        for(String chunk:chunks)check(chunk.length()<=180,"expanded raw chunk bound");
        // Prompt preparation appends punctuation/capitalization; enforce the bound on what the frontend actually receives.
        for(String chunk:PocketPrompt.boundedChunks(repeat("а",180)))check(PocketPrompt.prepare(chunk).length()<=180,"prepared prompt exceeds 180-character bound");
        for(String chunk:PocketPrompt.boundedChunks(repeat("а",179)+"😀"+repeat("б",180))){check(!Character.isHighSurrogate(chunk.charAt(chunk.length()-1)),"no split surrogate tail");check(!Character.isLowSurrogate(chunk.charAt(0)),"no split surrogate start");}
        System.out.println("PASS all "+checks+" Russian cardinal/prompt checks");
    }
    static String repeat(String text,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(text);return result.toString();}
}
