package org.localvoice.tts.backend;

import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** App-owned bounded cardinal expansion, separate from Shtorm's original tokenizer. */
public final class RussianCardinals {
    private static final Pattern INTEGER=Pattern.compile("-?[0-9]+");
    private static final Pattern AMBIGUOUS=Pattern.compile("[0-9]\\s*[-+/:.,]\\s*-?[0-9]");
    private RussianCardinals() {}
    private static final String[] SMALL={"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять","десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать","семнадцать","восемнадцать","девятнадцать"};
    private static final String[] TENS={"","","двадцать","тридцать","сорок","пятьдесят","шестьдесят","семьдесят","восемьдесят","девяносто"};
    private static final String[] HUNDREDS={"","сто","двести","триста","четыреста","пятьсот","шестьсот","семьсот","восемьсот","девятьсот"};
    /** Standalone nominative cardinals, deliberately not grammatical inflection or date parsing. */
    public static String spellout(long value){
        if(value < -999999999L||value>999999999L)throw unsupported();
        if(value==0)return SMALL[0];
        StringBuilder out=new StringBuilder();if(value<0){add(out,"минус");value=-value;}
        int millions=(int)(value/1000000),thousands=(int)(value/1000%1000),units=(int)(value%1000);
        if(millions!=0){group(out,millions,false);add(out,form(millions,"миллион","миллиона","миллионов"));}
        if(thousands!=0){group(out,thousands,true);add(out,form(thousands,"тысяча","тысячи","тысяч"));}
        if(units!=0)group(out,units,false);
        return out.toString();
    }
    private static void group(StringBuilder out,int value,boolean feminine){
        if(value>=100)add(out,HUNDREDS[value/100]);value%=100;
        if(value>=20){add(out,TENS[value/10]);value%=10;}
        if(value!=0)add(out,feminine&&value==1?"одна":feminine&&value==2?"две":SMALL[value]);
    }
    private static String form(int value,String one,String few,String many){int tail=value%100;if(tail>=11&&tail<=14)return many;return value%10==1?one:value%10>=2&&value%10<=4?few:many;}
    private static void add(StringBuilder out,String word){if(out.length()!=0)out.append(' ');out.append(word);}

    /** An explicit cardinal formatter is injected; the Android adapter uses the app-owned implementation. */
    public static String expand(String text,LongFunction<String> spellout) {
        if(AMBIGUOUS.matcher(text).find())throw unsupported();
        Matcher matcher=INTEGER.matcher(text);
        StringBuilder result=new StringBuilder(text.length());
        int copied=0;
        while(matcher.find()) {
            String number=matcher.group();
            int start=matcher.start(),end=matcher.end();
            int digits=number.charAt(0)=='-'?number.length()-1:number.length();
            int first=number.charAt(0)=='-'?1:0;
            if(digits>9 || (digits>1&&number.charAt(first)=='0') ||
                    (start>0&&text.charAt(start-1)=='+') ||
                    (start>0&&wordCharacter(text.codePointBefore(start))) ||
                    (end<text.length()&&wordCharacter(text.codePointAt(end))) ||
                    numericSeparatorAfter(text,end) || numericSeparatorBefore(text,start))
                throw unsupported();
            String words=spellout.apply(Long.parseLong(number));
            if(words==null||words.isEmpty()||words.length()>256)throw unsupported();
            result.append(text,copied,start).append(words);
            if(result.length()>8192)throw unsupported();
            copied=end;
        }
        return result.append(text,copied,text.length()).toString();
    }
    private static boolean wordCharacter(int c) {
        int type=Character.getType(c);
        return Character.isLetterOrDigit(c)||c=='_'||type==Character.NON_SPACING_MARK||type==Character.COMBINING_SPACING_MARK;
    }
    private static boolean separator(char c) {
        return c=='.'||c==','||c==':'||c=='/'||c=='-'||c=='+'||c=='%';
    }
    private static boolean numericSeparatorAfter(String text,int at) {
        return at+1<text.length()&&separator(text.charAt(at))&&Character.isDigit(text.charAt(at+1));
    }
    private static boolean numericSeparatorBefore(String text,int at) {
        return at>=2&&separator(text.charAt(at-1))&&Character.isDigit(text.charAt(at-2));
    }
    private static IllegalArgumentException unsupported() {
        // Do not include the user's text or number in logs.
        return new IllegalArgumentException("Shtorm supports isolated cardinal integers up to nine digits; this numeric format is unsupported");
    }
}
