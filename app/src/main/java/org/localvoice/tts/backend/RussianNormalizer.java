package org.localvoice.tts.backend;

import static org.localvoice.tts.backend.RussianCardinals.*;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic rule-based Russian text normalization for TTS frontends; never throws on content. Output holds
 * Russian Cyrillic (case kept, U+0301 stress and Silero '+' stress kept), spaces, '\n', and the punctuation
 * {@code . , ! ? : ; - — … ( ) « »}. Every output char maps back to the original [start,end) it was spoken for.
 * Rules and limits: docs/RUSSIAN-NORMALIZER.md.
 */
public final class RussianNormalizer {
    private RussianNormalizer() {}

    /** Normalized text plus, per output char, the original source range it came from. */
    public static final class Result {
        public final String text;
        private final int[] start, end;
        Result(String text, int[] start, int[] end) { this.text = text; this.start = start; this.end = end; }
        public int sourceStart(int i) { return start[i]; }
        public int sourceEnd(int i) { return end[i]; }
        /** Smallest original range covering normalized text[from,to); {-1,-1} for an empty range. */
        public int[] source(int from, int to) {
            int a = Integer.MAX_VALUE, b = -1;
            for (int i = Math.max(0, from); i < Math.min(to, text.length()); i++) { a = Math.min(a, start[i]); b = Math.max(b, end[i]); }
            return b < 0 ? new int[]{-1, -1} : new int[]{a, b};
        }
    }

    /** Tests set this to surface rule bugs instead of degrading to a skipped character or the fallback. */
    static boolean strict;

    public static String apply(String text) { return normalize(text).text; }

    public static Result normalize(String text) {
        if (text == null) text = "";
        try {
            Run run = new Run(text);
            run.run();
            return run.out.result();
        } catch (RuntimeException | StackOverflowError bug) {
            if (strict) throw bug;
            return fallback(text);
        }
    }

    /** Last resort: Russian letters, spaces and sentence punctuation only, one-to-one. */
    private static Result fallback(String text) {
        StringBuilder t = new StringBuilder();
        int[] a = new int[text.length()], b = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char k = russian(c) || ".,!?".indexOf(c) >= 0 ? c : c == '\n' ? '\n' : ' ';
            if (k == ' ' && (t.length() == 0 || t.charAt(t.length() - 1) == ' ')) continue;
            a[t.length()] = i; b[t.length()] = i + 1; t.append(k);
        }
        return new Result(t.toString(), a, b);
    }

    // ---- tables ----
    private static final char NBSP = ' ';
    private static final String SP = "[ \\u00A0]";
    private static final String[] MONTH_GEN = {"", "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
    private static final Set<String> MONTH_WORDS = new HashSet<>();
    static {
        for (String stem : "январ феврал апрел июн июл сентябр октябр ноябр декабр".split(" "))
            for (String end : "ь я ю ём ем е".split(" ")) MONTH_WORDS.add(stem + end);
        for (String stem : "март август".split(" ")) for (String end : ",а,у,ом,е".split(",", -1)) MONTH_WORDS.add(stem + end);
        MONTH_WORDS.addAll(Arrays.asList("май", "мая", "маю", "маем", "мае"));
    }
    private static final Set<String> GEN = set("от до из без у около для после кроме вокруг среди против ради свыше более менее больше меньше порядка вроде возле близ накануне мимо вдоль"),
        DAT = set("к ко благодаря согласно вопреки навстречу"), INS = set("между перед над"), PRE = set("о об обо при"), LOC = set("в во на"),
        WITH = set("с со"), PREPS = set("в во на за через по под про о об обо при к ко с со от до из без у около для после между перед над"),
        VERSION_WORDS = set("версия версии версию версией version v android ios windows python java api");

    private static final class Unit {
        final Noun noun; final String suffix; final boolean scale;
        /** Hundredth subunit of a currency ("копейка", "цент"), or null. */
        Noun cents;
        Unit(Noun noun, String suffix, boolean scale) { this.noun = noun; this.suffix = suffix; this.scale = scale; }
    }
    private static final Map<String, Unit> UNITS = new HashMap<>();
    private static final Pattern UNIT;
    private static void unit(Noun noun, String suffix, boolean scale, String... keys) { for (String k : keys) UNITS.put(k, new Unit(noun, suffix, scale)); }
    static {
        Noun km = Noun.hard("километр"), m = Noun.hard("метр"), sec = Noun.fem("секунд", "секунд"), hour = Noun.hard("час"), deg = Noun.hard("градус");
        unit(THOUSAND, "", true, "тыс.", "тыс");
        unit(Noun.hard("миллион"), "", true, "млн.", "млн");
        unit(Noun.hard("миллиард"), "", true, "млрд.", "млрд");
        unit(Noun.hard("триллион"), "", true, "трлн.", "трлн");
        unit(Noun.hard("процент"), "", false, "%");
        unit(Noun.fixed(N, "промилле"), "", false, "‰");
        unit(deg, " Цельсия", false, "°C", "°С");
        unit(deg, " Фаренгейта", false, "°F");
        unit(deg, "", false, "°");
        unit(Noun.soft("рубл"), "", false, "₽", "руб.", "руб", "р.");
        unit(Noun.fem("копейк", "копеек"), "", false, "коп.", "коп");
        unit(Noun.hard("доллар"), "", false, "$", "долл.", "долл");
        unit(Noun.fixed(N, "евро"), "", false, "€");
        unit(Noun.hard("фунт"), "", false, "£");
        unit(Noun.fem("иен", "иен"), "", false, "¥");
        unit(Noun.fem("гривн", "гривен"), "", false, "₴");
        unit(Noun.fixed(N, "тенге"), "", false, "₸");
        unit(km, " в час", false, "км/ч", "km/h");
        unit(m, " в секунду", false, "м/с", "m/s");
        unit(km, "", false, "км", "km");
        unit(m, "", false, "м");
        unit(Noun.hard("сантиметр"), "", false, "см", "cm");
        unit(Noun.hard("миллиметр"), "", false, "мм", "mm");
        unit(Noun.hard("килограмм"), "", false, "кг", "kg");
        unit(Noun.hard("грамм"), "", false, "г", "гр.", "гр");
        unit(Noun.hard("миллиграмм"), "", false, "мг", "mg");
        unit(Noun.fem("тонн", "тонн"), "", false, "т");
        unit(Noun.hard("литр"), "", false, "л");
        unit(Noun.hard("миллилитр"), "", false, "мл", "ml");
        unit(hour, "", false, "ч");
        unit(Noun.fem("минут", "минут"), "", false, "мин.", "мин");
        unit(sec, "", false, "сек.", "сек", "с");
        unit(Noun.fem("миллисекунд", "миллисекунд"), "", false, "мс", "ms");
        unit(Noun.fem("штук", "штук"), "", false, "шт.", "шт");
        unit(Noun.hard("гигабайт", "гигабайт"), "", false, "ГБ", "Гб", "GB");
        unit(Noun.hard("мегабайт", "мегабайт"), "", false, "МБ", "Мб", "MB");
        unit(Noun.hard("килобайт", "килобайт"), "", false, "КБ", "Кб", "KB");
        unit(Noun.hard("терабайт", "терабайт"), "", false, "ТБ", "Тб", "TB");
        unit(Noun.hard("гигагерц", "гигагерц"), "", false, "ГГц", "GHz");
        unit(Noun.hard("мегагерц", "мегагерц"), "", false, "МГц", "MHz");
        unit(Noun.hard("килогерц", "килогерц"), "", false, "кГц", "kHz");
        unit(Noun.hard("герц", "герц"), "", false, "Гц", "Hz");
        unit(Noun.hard("киловатт", "киловатт"), "", false, "кВт", "kW");
        unit(Noun.hard("ватт", "ватт"), "", false, "Вт");
        unit(Noun.hard("вольт", "вольт"), "", false, "В");
        Noun kopeck = Noun.fem("копейк", "копеек"), cent = Noun.hard("цент");
        for (String k : new String[]{"₽", "руб.", "руб", "р."}) UNITS.get(k).cents = kopeck;
        for (String k : new String[]{"$", "долл.", "долл", "€"}) UNITS.get(k).cents = cent;
        StringBuilder alt = new StringBuilder();
        UNITS.keySet().stream().sorted((x, y) -> y.length() - x.length()).forEach(k -> alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(k)));
        UNIT = Pattern.compile(SP + "?(" + alt + ")");
    }
    static final Noun HOUR = Noun.hard("час"), MINUTE = Noun.fem("минут", "минут"), SECOND = Noun.fem("секунд", "секунд");
    private static final String CURRENCY = "$€£¥₽₴₸";

    private static final Map<Character, String> GREEK = new HashMap<>();
    static {
        String g = "α=альфа β=бета γ=гамма δ=дельта ε=эпсилон ζ=дзета η=эта θ=тета ι=йота κ=каппа λ=лямбда μ=мю ν=ню ξ=кси π=пи ρ=ро σ=сигма ς=сигма τ=тау υ=ипсилон φ=фи χ=хи ψ=пси ω=омега";
        for (String p : g.split(" ")) GREEK.put(p.charAt(0), p.substring(2));
    }
    private static final String HOMO_LAT = "AaBCcEeHKkMOoPpTXxYy", HOMO_CYR = "АаВСсЕеНКкМОоРрТХхУу";
    private static final Map<String, Character> ROMAN_NOUNS = new HashMap<>();
    static {
        for (String s : "глав част стать книг сери".split(" ")) ROMAN_NOUNS.put(s, F);
        for (String s : "том раздел акт сезон класс этап тур уров съезд созыв пункт".split(" ")) ROMAN_NOUNS.put(s, M);
        for (String s : "приложени издани".split(" ")) ROMAN_NOUNS.put(s, N);
    }
    private static final String[][] ABBR = {
        {"т\\." + SP + "?е\\.", "то есть"}, {"т\\." + SP + "?к\\.", "так как"}, {"т\\." + SP + "?д\\.", "так далее"}, {"т\\." + SP + "?п\\.", "тому подобное"},
        {"напр\\.", "например"}, {"см\\.", "смотри"}, {"рис\\.", "рисунок"}, {"табл\\.", "таблица"}, {"ул\\.", "улица"}, {"стр\\.", "страница"},
        {"тел\\.", "телефон"}, {"обл\\.", "область"}, {"им\\.", "имени"}, {"проф\\.", "профессор"}, {"г\\.(?=" + SP + "?[А-ЯЁ])", "город"},
        {"др\\.", "другие"}, {"руб\\.", "рублей"}, {"коп\\.", "копеек"}, {"тыс\\.", "тысяч"}, {"млн\\.?", "миллионов"}, {"млрд\\.?", "миллиардов"}, {"долл\\.", "долларов"}};
    private static final Pattern[] ABBR_P = new Pattern[ABBR.length];
    static { for (int k = 0; k < ABBR.length; k++) ABBR_P[k] = Pattern.compile(ABBR[k][0] + "(?![\\p{L}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }

    private static final Pattern URL = Pattern.compile("(?i)(?:(?:https?|ftp)://|www\\.)[^\\s«»\"<>]+"),
        DOMAIN = Pattern.compile("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.(?:com|ru|org|net|io|ai|dev|app|info|me|tv|edu|gov|uk|de|su|by|kz|ua|рф)(?![\\p{L}\\p{N}])(?:/[^\\s«»\"<>]*)?"),
        EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"),
        PHONE = Pattern.compile("\\+\\d{1,3}" + SP + "?-?(?:\\(\\d{1,5}\\)|\\d{1,5})(?:" + SP + "?-?\\d{1,4}){1,5}(?!\\d)"
            + "|8" + SP + "?\\(\\d{3,5}\\)" + SP + "?\\d{1,4}(?:" + SP + "?-?\\d{2,4}){1,3}(?!\\d)"
            + "|\\(\\d{3,5}\\)" + SP + "?\\d{1,4}(?:" + SP + "?-?\\d{2,4}){1,3}(?!\\d)"
            + "|\\d{3}-\\d{2}-\\d{2}(?![\\d-])"),
        ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d-])"),
        DOT_DATE = Pattern.compile("(\\d{1,2})([./])(\\d{1,2})\\2(\\d{4}|\\d{2})(?!\\d|[.,/]\\d)"),
        TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d|:\\d)"),
        VERSION = Pattern.compile("\\d+(?:\\.\\d+){2,}(?!\\d)"),
        LIST = Pattern.compile("\\d+(?:,\\d+){2,}(?!\\d)"),
        GROUPED = Pattern.compile("\\d{1,3}(?:[ \\u00A0']\\d{3})+(?!\\d)"),
        DIGITS = Pattern.compile("\\d+"),
        ORD_SUFFIX = Pattern.compile("(-?)(ыми|ими|ьими|ого|его|ому|ему|ьего|ьему|ая|ое|ую|ые|ых|ым|ый|ий|ой|ом|ем|ей|их|им|ья|ье|ью|ьи|го|му|ми|ти|мя|й|я|е|х|м|ю)(?![\\p{L}\\d])"),
        RANGE = Pattern.compile(SP + "?[-–—]" + SP + "?(\\d+)(?!\\d|[.,:]\\d)"),
        FRACTION = Pattern.compile("/(\\d{1,3})(?!\\d|[.,/]\\d)"),
        TIMES = Pattern.compile(SP + "?[x×хХ]" + SP + "?(\\d+)(?![\\p{L}\\d])"),
        DAY_AHEAD = Pattern.compile("(?:" + SP + "*(?:[-–—]|по|до)" + SP + "*\\d{1,2})?" + SP + "+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        ROMAN = Pattern.compile("M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"),
        ROMAN_RANGE = Pattern.compile(SP + "?[-–—]" + SP + "?([IVXLCDM]+)(?![\\p{L}])");
    private static final Set<String> TIME_PREPS = set("в к до с после около по"), DATE_PREPS = set("до с по к от после на");
    private static final Set<String> COMPOUNDS = set("wi-fi e-mail t-shirt");

    private static Set<String> set(String words) { return new HashSet<>(Arrays.asList(words.split(" "))); }
    static boolean russian(char c) { return c >= 'А' && c <= 'я' || c == 'ё' || c == 'Ё'; }
    private static boolean cyrVowel(char c) { return "аеёиоуыэюяАЕЁИОУЫЭЮЯ".indexOf(c) >= 0; }
    private static boolean latin(char c) { return Character.isLetter(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN; }

    /** One normalization pass over the pre-mapped text. */
    private static final class Run {
        final String s; final int n; final int[] from, to; final Out out;
        int i;

        Run(String raw) {
            StringBuilder b = new StringBuilder(raw.length());
            int[] f = new int[raw.length() * 2 + 4], t = new int[raw.length() * 2 + 4];
            int len = 0;
            for (int at = 0; at < raw.length(); ) {
                char ch = raw.charAt(at);
                if (Character.isSurrogate(ch) && !(Character.isHighSurrogate(ch) && at + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(at + 1)))) { at++; continue; }
                int end = at + Character.charCount(raw.codePointAt(at));
                while (end < raw.length() && mark(raw.codePointAt(end))) end += Character.charCount(raw.codePointAt(end));
                String mapped = map(raw.substring(at, end));
                for (int k = 0; k < mapped.length(); k++) {
                    if (len == f.length) { f = Arrays.copyOf(f, len * 2); t = Arrays.copyOf(t, len * 2); }
                    b.append(mapped.charAt(k)); f[len] = at; t[len] = end; len++;
                }
                at = end;
            }
            s = b.toString(); n = s.length(); from = f; to = t;
            out = new Out();
        }

        private static boolean mark(int cp) { int type = Character.getType(cp); return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK; }

        /** Per grapheme cluster: NFKC, whitespace classes, and dropped marks/format chars. */
        private static String map(String cluster) {
            int cp = cluster.codePointAt(0);
            switch (cp) {
                case '\r': case '\n': case 0x85: case 0x2028: case 0x2029: case 0x0B: case 0x0C: return "\n";
                case 0xA0: case 0x2007: case 0x202F: case 0x2009: case 0x200A: return String.valueOf(NBSP);
                case 0x200B: case 0x200C: case 0x200D: case 0x2060: case 0xFEFF: case 0xAD: case '™': case '®': case '©': return "";
                case '№': case '…': return String.valueOf((char) cp);
                case 0x2044: case 0x2215: return "/";
                default:
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) return " ";
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) return "";
            String k = Normalizer.normalize(cluster, Normalizer.Form.NFKC);
            StringBuilder kept = new StringBuilder(k.length());
            for (int j = 0; j < k.length(); j++) {
                char c = k.charAt(j);
                if (mark(c)) {
                    if (c == '́' && kept.length() > 0 && cyrVowel(kept.charAt(kept.length() - 1))) kept.append(c);
                    continue;
                }
                kept.append(c);
            }
            return kept.toString();
        }

        void run() {
            while (i < n) {
                int start = i;
                try { step(); } catch (RuntimeException bug) { if (strict) throw bug; i = start; }
                if (i <= start) { i = start + Character.charCount(s.codePointAt(start)); out.space(start, i); }
            }
        }

        void step() {
            char c = s.charAt(i);
            if (c == '\n') { out.newline(i, i + 1); i++; return; }
            if (c == ' ' || c == NBSP) { out.space(i, i + 1); i++; return; }
            if (!wordCharBefore(i) && (Character.isLetterOrDigit(c)) && (link(URL) || link(EMAIL) || link(DOMAIN))) return;
            if (Character.isDigit(c) || (c == '-' || c == '−') && digit(i + 1) && !wordCharBefore(i) || (c == '+' || c == '(') && digit(i + 1) || CURRENCY.indexOf(c) >= 0 && digit(skipSpaces(i + 1))) {
                if (numeric()) return;
            }
            if (Character.isLetter(c) || c == '+' && i + 1 < n && cyrVowel(s.charAt(i + 1)) && !Character.isLetter(before(i))) {
                if (Character.isLetter(c) && !Character.isLetter(before(i)) && (roman() || abbreviation())) return;
                word();
                return;
            }
            symbol(c);
        }

        // ---- context helpers ----
        char before(int pos) { return pos > 0 ? s.charAt(pos - 1) : ' '; }
        boolean digit(int pos) { return pos < n && pos >= 0 && s.charAt(pos) >= '0' && s.charAt(pos) <= '9'; }
        boolean wordCharBefore(int pos) { return Character.isLetterOrDigit(before(pos)); }
        int skipSpaces(int pos) { while (pos < n && (s.charAt(pos) == ' ' || s.charAt(pos) == NBSP)) pos++; return pos; }
        Matcher at(Pattern p, int pos) {
            if (pos > n) return null;
            Matcher m = p.matcher(s).region(pos, n).useTransparentBounds(true).useAnchoringBounds(false);
            return m.lookingAt() ? m : null;
        }
        String prevWord(int pos) {
            int j = pos;
            while (j > 0 && (s.charAt(j - 1) == ' ' || s.charAt(j - 1) == NBSP)) j--;
            int e = j;
            while (j > 0 && Character.isLetter(s.charAt(j - 1))) j--;
            return s.substring(j, e).toLowerCase(Locale.ROOT);
        }
        String prevRaw(int pos) {
            int j = pos;
            while (j > 0 && (s.charAt(j - 1) == ' ' || s.charAt(j - 1) == NBSP)) j--;
            int e = j;
            while (j > 0 && Character.isLetter(s.charAt(j - 1))) j--;
            return s.substring(j, e);
        }
        /** {start, end} of the letters right after pos (spaces skipped), or null. */
        int[] nextWord(int pos) {
            int a = skipSpaces(pos), b = a;
            while (b < n && Character.isLetter(s.charAt(b))) b++;
            return b > a ? new int[]{a, b} : null;
        }
        String word(int[] range) { return range == null ? "" : s.substring(range[0], range[1]).toLowerCase(Locale.ROOT); }
        boolean dotAt(int pos) { return pos < n && s.charAt(pos) == '.'; }
        /** A consumed abbreviation dot also ends the sentence: end of text, line break, or a capital letter follows. */
        boolean sentenceEnd(int pos) {
            if (pos >= n || s.charAt(pos) == '\n') return true;
            int j = skipSpaces(pos);
            return j >= n || s.charAt(j) == '\n' || j > pos && Character.isUpperCase(s.charAt(j));
        }
        void dot(int dotPos) { if (sentenceEnd(dotPos + 1)) out.punct('.', dotPos, dotPos + 1); }

        Case caseFor(String prev, String next) {
            if (WITH.contains(prev)) return next.endsWith("ами") || next.endsWith("ями") ? Case.INS : Case.GEN;
            if (GEN.contains(prev)) return Case.GEN;
            if (DAT.contains(prev)) return Case.DAT;
            if (INS.contains(prev)) return Case.INS;
            if (PRE.contains(prev)) return Case.PRE;
            if (LOC.contains(prev) && (next.endsWith("ах") || next.endsWith("ях"))) return Case.PRE;
            return Case.NOM;
        }
        /** Gender for 1 and 2 from the following word's ending; other numbers do not inflect for gender. */
        static char genderFor(long v, Case c, String next) {
            long t = Math.abs(v) % 100, u = t % 10;
            if (next.isEmpty() || t >= 11 && t <= 14) return M;
            if (u == 1) {
                switch (c) {
                    case NOM: return next.endsWith("а") || next.endsWith("я") ? F : next.endsWith("о") || next.endsWith("е") ? N : next.endsWith("у") || next.endsWith("ю") ? A : M;
                    case GEN: return next.endsWith("и") || next.endsWith("ы") ? F : M;
                    case DAT: return next.endsWith("е") ? F : M;
                    case INS: return next.endsWith("ой") || next.endsWith("ей") ? F : M;
                    default: return M;
                }
            }
            if (u == 2 && c == Case.NOM) return next.endsWith("и") || next.endsWith("ы") ? F : M;
            return M;
        }
        Ord dateOrd(String prev) {
            if (GEN.contains(prev) || WITH.contains(prev)) return Ord.M_GEN;
            if (DAT.contains(prev)) return Ord.M_DAT;
            if (INS.contains(prev)) return Ord.M_INS;
            if (PRE.contains(prev)) return Ord.M_PRE;
            return Ord.N_NOM;
        }

        // ---- links ----
        boolean link(Pattern p) {
            Matcher m = at(p, i);
            if (m == null) return false;
            int end = m.end();
            while (end > i && ".,;:!?)»'\"".indexOf(s.charAt(end - 1)) >= 0) end--;
            String t = s.substring(i, end);
            String spoken;
            int atSign = t.indexOf('@');
            if (p == EMAIL) spoken = alnum(t.substring(0, atSign)) + " собака " + host(t.substring(atSign + 1));
            else {
                String h = t.replaceFirst("(?i)^(?:https?|ftp)://", "");
                int cut = h.length();
                for (char stop : "/?#:".toCharArray()) { int k = h.indexOf(stop); if (k >= 0) cut = Math.min(cut, k); }
                spoken = host(h.substring(0, cut).replaceFirst("(?i)^www\\.", ""));
            }
            out.word(spoken, i, end);
            i = end;
            return true;
        }
        String host(String h) {
            StringBuilder b = new StringBuilder();
            for (String label : h.split("\\.")) {
                if (label.isEmpty()) continue;
                if (b.length() > 0) b.append(" точка ");
                b.append(label.equalsIgnoreCase("рф") ? "эр-эф" : alnum(label));
            }
            return b.toString();
        }
        /** Letters and digit runs of a link part, other characters as spaces. */
        String alnum(String t) {
            StringBuilder b = new StringBuilder();
            Matcher m = Pattern.compile("[A-Za-z]+|\\d+|[А-Яа-яЁё]+").matcher(t);
            while (m.find()) {
                String g = m.group();
                String w = Character.isDigit(g.charAt(0)) ? (g.length() > 3 || g.length() > 1 && g.charAt(0) == '0' ? digitGroups(g) : spellout(Long.parseLong(g)))
                    : latin(g.charAt(0)) ? LatinTranslit.word(g.toLowerCase(Locale.ROOT)) : g;
                if (b.length() > 0) b.append(' ');
                b.append(w);
            }
            return b.toString();
        }

        // ---- numbers ----
        boolean numeric() {
            int st = i;
            char c = s.charAt(i);
            Matcher m;
            if ((m = at(PHONE, i)) != null && phone(m)) return true;
            if (c == '+' || c == '(') return false;
            if (digit(i)) {
                if ((m = at(ISO_DATE, i)) != null && date(m, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), m.group(1))) return true;
                if ((m = at(DOT_DATE, i)) != null && date(m, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), m.group(4))) return true;
                if ((m = at(TIME, i)) != null && time(m, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3))) return true;
                if ((m = at(VERSION, i)) != null) { dotted(m.group(), " точка ", m.end()); return true; }
                if ((m = at(LIST, i)) != null) { dotted(m.group(), ", ", m.end()); return true; }
            }
            i = st;
            return amount();
        }

        boolean phone(Matcher m) {
            String t = m.group();
            int count = 0;
            for (char ch : t.toCharArray()) if (Character.isDigit(ch)) count++;
            if (count < 7) return false;
            StringBuilder b = new StringBuilder(t.startsWith("+") ? "плюс" : "");
            Matcher g = DIGITS.matcher(t);
            while (g.find()) {
                String run = g.group();
                String w = run.length() <= 3 && (run.length() == 1 || run.charAt(0) != '0') ? spellout(Long.parseLong(run))
                    : run.length() == 4 ? group2(run.substring(0, 2)) + " " + group2(run.substring(2)) : digitGroups(run);
                if (b.length() > 0) b.append(b.toString().equals("плюс") ? " " : ", ");
                b.append(w);
            }
            out.word(b.toString(), i, m.end());
            i = m.end();
            return true;
        }
        static String group2(String g) { return g.charAt(0) == '0' ? digits(g) : spellout(Long.parseLong(g)); }

        boolean date(Matcher m, int d, int mo, String y) {
            if (d < 1 || d > 31 || mo < 1 || mo > 12) return false;
            int year = Integer.parseInt(y);
            if (y.length() == 2) year += year <= 50 ? 2000 : 1900;
            String prev = prevWord(i);
            int end = m.end();
            String tail = " года";
            int[] next = nextWord(end);
            String nw = word(next);
            int abbrevEnd = -1;
            if (nw.equals("года")) tail = "";
            else if (nw.equals("г") && dotAt(next[1])) abbrevEnd = next[1] + 1;
            else if (nw.equals("г")) abbrevEnd = next[1];
            out.word(ordinal(d, dateOrd(prev)) + " " + MONTH_GEN[mo] + " " + ordinal(year, Ord.M_GEN) + tail, i, end);
            i = end;
            if (abbrevEnd > 0) { i = abbrevEnd; if (dotAt(abbrevEnd - 1)) dot(abbrevEnd - 1); }
            return true;
        }

        boolean time(Matcher m, int h, int min, String sec) {
            if (h > 99 || min > 59 || sec != null && Integer.parseInt(sec) > 59) return false;
            int[] next = nextWord(m.end());
            Case c = caseFor(prevWord(i), word(next));
            out.word(clock(h, min, sec == null ? -1 : Integer.parseInt(sec), c), i, m.end());
            i = m.end();
            return true;
        }
        static String clock(int h, int min, int sec, Case c) {
            if (sec >= 0) return cardinal(h, c, M) + " " + HOUR.agree(h, c) + " " + cardinal(min, c, F) + " " + MINUTE.agree(min, c) + " " + cardinal(sec, c, F) + " " + SECOND.agree(sec, c);
            return cardinal(h, c, M) + " " + (min == 0 ? "ноль ноль" : min < 10 ? "ноль " + cardinal(min, c, F) : cardinal(min, c, F));
        }

        void dotted(String t, String joiner, int end) {
            StringBuilder b = new StringBuilder();
            for (String part : t.split("[.,]")) {
                if (b.length() > 0) b.append(joiner);
                b.append(part.length() > 18 ? digitGroups(part) : part.length() > 1 && part.charAt(0) == '0' ? digits(part) : spellout(Long.parseLong(part)));
            }
            out.word(b.toString(), i, end);
            i = end;
        }

        /** A number with optional currency prefix, sign, group separators, fraction, suffix, range, unit or date context. */
        boolean amount() {
            int st = i, pos = i;
            Unit prefix = null; int curFrom = -1;
            if (CURRENCY.indexOf(s.charAt(pos)) >= 0) { prefix = UNITS.get(String.valueOf(s.charAt(pos))); curFrom = pos; pos = skipSpaces(pos + 1); }
            boolean neg = false;
            if (s.charAt(pos) == '-' || s.charAt(pos) == '−') { neg = true; pos++; }
            if (!digit(pos)) return false;
            Matcher m = at(GROUPED, pos);
            if (m == null) m = at(DIGITS, pos);
            String whole = m.group().replaceAll("\\D", "");
            pos = m.end();
            String frac = null;
            if (pos + 1 < n && (s.charAt(pos) == '.' || s.charAt(pos) == ',') && digit(pos + 1)) { Matcher f = at(DIGITS, pos + 1); frac = f.group(); pos = f.end(); }
            int numFrom = curFrom >= 0 ? skipSpaces(curFrom + 1) : st, numEnd = pos;
            boolean lead0 = whole.length() > 1 && whole.charAt(0) == '0', big = whole.length() > 18;
            long v = big ? 0 : Long.parseLong(whole);
            boolean plain = frac == null && !neg && !lead0 && !big && prefix == null;
            String prev = prevWord(st);

            if (frac != null && s.charAt(numEnd - frac.length() - 1) == '.' && VERSION_WORDS.contains(prev) && prefix == null) {
                out.word((neg ? "минус " : "") + spellout(v) + " точка " + (frac.length() > 1 && frac.charAt(0) == '0' ? digits(frac) : frac.length() > 18 ? digitGroups(frac) : spellout(Long.parseLong(frac))), st, numEnd);
                i = numEnd; return true;
            }
            if (frac != null && frac.length() == 2 && whole.length() == 2 && s.charAt(numEnd - 3) == '.' && !neg && prefix == null && DATE_PREPS.contains(prev)
                && v >= 1 && v <= 31 && Integer.parseInt(frac) >= 1 && Integer.parseInt(frac) <= 12 && unitAt(numEnd) == null) {
                // "до 31.12": day.month after a date preposition.
                out.word(ordinal(v, dateOrd(prev)) + " " + MONTH_GEN[Integer.parseInt(frac)], st, numEnd);
                i = numEnd; return true;
            }
            if (frac != null && frac.length() == 2 && whole.length() <= 2 && s.charAt(numEnd - 3) == '.' && !neg && prefix == null
                && TIME_PREPS.contains(prev) && v <= 23 && Long.parseLong(frac) <= 59 && unitAt(numEnd) == null && !(numEnd < n && s.charAt(numEnd) == '%')) {
                Case c = caseFor(prev, word(nextWord(numEnd)));
                out.word(clock((int) v, Integer.parseInt(frac), -1, c), st, numEnd);
                i = numEnd; return true;
            }
            if (plain) {
                Matcher sm = at(ORD_SUFFIX, numEnd);
                if (sm != null && (sm.group(1).length() > 0 || !sm.group(2).equals("м") && !sm.group(2).equals("ю"))) { suffixed(v, sm, st, prev); return true; }
                Matcher rm = at(RANGE, numEnd);
                if (rm != null && rm.group(1).length() <= 18 && !(rm.group(1).length() > 1 && rm.group(1).charAt(0) == '0')) { range(v, rm, st, numEnd, prev); return true; }
                Matcher fm = at(FRACTION, numEnd);
                if (fm != null) {
                    long d = Long.parseLong(fm.group(1));
                    String w = v < d && d >= 2 && d <= 10 ? cardinal(v, Case.NOM, F) + " " + ordinal(d, v % 10 == 1 && v % 100 != 11 ? Ord.F_NOM : Ord.P_GEN) : spellout(v) + " дробь " + spellout(d);
                    out.word(w, st, fm.end()); i = fm.end(); return true;
                }
                Matcher tm = at(TIMES, numEnd);
                if (tm != null && tm.group(1).length() <= 18) { out.word(spellout(v) + " на " + spellout(Long.parseLong(tm.group(1))), st, tm.end()); i = tm.end(); return true; }
                if (v >= 1 && v <= 31 && at(DAY_AHEAD, numEnd) != null) { out.word(ordinal(v, dateOrd(prev)), st, numEnd); i = numEnd; return true; }
                if (whole.length() == 4 && MONTH_WORDS.contains(prev)) { year(v, Ord.M_GEN, st, numEnd, true); return true; }
                if (yearNoun(v, st, numEnd, prev) || century(v, st, numEnd, prev)) return true;
            }
            Unit scale = null, unit = prefix;
            int scaleFrom = -1, scaleEnd = -1, unitFrom = curFrom, unitEnd = curFrom + 1, end = numEnd;
            Matcher um = unitAt(numEnd);
            if (um != null) {
                Unit u = UNITS.get(um.group(1));
                if (u.scale) {
                    scale = u; scaleFrom = um.start(1); scaleEnd = end = unitTail(um);
                    Matcher second = prefix == null ? unitAt(end) : null;
                    if (second != null && !UNITS.get(second.group(1)).scale) { unit = UNITS.get(second.group(1)); unitFrom = second.start(1); unitEnd = end = unitTail(second); }
                } else if (prefix == null) { unit = u; unitFrom = um.start(1); unitEnd = end = unitTail(um); }
            }
            String nw = word(nextWord(end));
            Case c = caseFor(prev, nw);
            Unit lead = scale != null ? scale : unit;
            char gender = lead != null ? lead.noun.gender : genderFor(v, c, nw);
            boolean counted = counted(whole);
            if (unit != null && unit.cents != null && scale == null && frac != null && frac.length() == 2 && counted) {
                // Money: "1 999,99 ₽" → "... рублей девяносто девять копеек".
                long cents = Long.parseLong(frac);
                out.word((neg ? "минус " : "") + cardinal(v, c, unit.noun.gender), numFrom, numEnd);
                out.word(unit.noun.agree(v, c) + " " + cardinal(cents, c, unit.cents.gender) + " " + unit.cents.agree(cents, c), unitFrom, unitEnd);
                i = end;
                if (end > numEnd && dotAt(end - 1)) dot(end - 1);
                return true;
            }
            String words = lead0 ? digits(whole) : big ? digitGroups(whole) : frac != null ? decimal(whole, frac, c) : cardinal(v, c, gender);
            out.word(neg ? "минус " + words : words, numFrom, numEnd);
            // Counted forms agree with the integer; fractions take genitive singular; IDs and scaled amounts the "many" form.
            if (scale != null) out.word(frac != null ? scale.noun.fraction() : scale.noun.agree(counted ? v : 5, c), scaleFrom, scaleEnd);
            if (unit != null) {
                String w = scale != null || !counted ? unit.noun.agree(5, c) : frac != null ? unit.noun.fraction() : unit.noun.agree(v, c);
                out.word(w + unit.suffix, unitFrom, unitEnd);
            }
            i = end;
            if (end > numEnd && dotAt(end - 1)) dot(end - 1);
            return true;
        }

        static boolean counted(String whole) { return whole.length() <= 18 && !(whole.length() > 1 && whole.charAt(0) == '0'); }

        /** Unit or scale right after a number; one-letter Cyrillic units only when no word follows them. */
        Matcher unitAt(int pos) {
            Matcher um = at(UNIT, pos);
            if (um == null) return null;
            String key = um.group(1);
            int end = um.end();
            if (end < n && Character.isLetterOrDigit(s.charAt(end)) && Character.isLetter(key.charAt(key.length() - 1))) return null;
            if (key.length() == 1 && Character.isLetter(key.charAt(0)) && !dotAt(end)) {
                int j = skipSpaces(end);
                // "5 с половиной", "5 т угля": a spaced one-letter unit followed by a word is a word.
                if (um.start(1) > pos && j < n && Character.isLetter(s.charAt(j))) return null;
            }
            return um;
        }
        /** Unit end, swallowing an abbreviation dot right after a dot-less key ("кг." stays one token). */
        int unitTail(Matcher um) {
            String key = um.group(1);
            int end = um.end();
            if (!key.endsWith(".") && Character.isLetter(key.charAt(key.length() - 1)) && dotAt(end) && !digit(end + 1)) return end + 1;
            return end;
        }

        void suffixed(long v, Matcher sm, int st, String prev) {
            String suf = sm.group(2);
            int end = sm.end();
            String nw = word(nextWord(end));
            String words;
            boolean twoToFour = v % 10 >= 2 && v % 10 <= 4 && (v % 100 < 12 || v % 100 > 14) && v < 1000;
            // Case endings on cardinals: "5-ти" пяти, "7-ми" семи, "2-х" двух, "2-м" двум, "2-мя" двумя.
            switch (suf) {
                case "ти": words = cardinal(v, Case.GEN, M); break;
                case "мя": case "ью": words = cardinal(v, Case.INS, M); break;
                case "ми": words = cardinal(v, Case.GEN, M); break;
                case "х": words = twoToFour ? cardinal(v, Case.GEN, M) : ordinal(v, Ord.P_GEN); break;
                case "м": words = twoToFour ? cardinal(v, Case.DAT, M) : ordinal(v, suffixForm(v, suf, prev, nw)); break;
                default: words = ordinal(v, suffixForm(v, suf, prev, nw));
            }
            out.word(words, st, end);
            i = end;
        }
        Ord suffixForm(long v, String suf, String prev, String next) {
            switch (suf) {
                case "й": case "ой":
                    return next.endsWith("е") || next.endsWith("и") || next.endsWith("ы") || next.endsWith("ой") || next.endsWith("ей") ? Ord.F_OBL : Ord.M_NOM;
                case "я": case "ая": case "ья": return Ord.F_NOM;
                case "ю": case "ую": case "ью": return Ord.F_ACC;
                case "е": case "ое": case "ье": return v >= 10 && v % 10 == 0 && !next.endsWith("е") ? Ord.P_NOM : Ord.N_NOM;
                case "ые": case "ьи": return Ord.P_NOM;
                case "го": case "ого": case "его": case "ьего": return Ord.M_GEN;
                case "му": case "ому": case "ему": case "ьему": return Ord.M_DAT;
                case "м": case "ым": case "им": case "ьим": return LOC.contains(prev) || PRE.contains(prev) ? Ord.M_PRE : Ord.M_INS;
                case "ом": case "ем": case "ьем": return Ord.M_PRE;
                case "ых": case "их": case "ьих": return Ord.P_GEN;
                case "ми": case "ыми": case "ими": case "ьими": return Ord.P_INS;
                case "ей": case "ьей": return Ord.F_OBL;
                default: return Ord.M_NOM;
            }
        }

        void range(long a, Matcher rm, int st, int numEnd, String prev) {
            long b = Long.parseLong(rm.group(1));
            int bFrom = rm.start(1), end = rm.end();
            Matcher sm = at(ORD_SUFFIX, end);
            if (sm != null && sm.group(1).length() > 0) {
                Ord form = suffixForm(b, sm.group(2), prev, word(nextWord(sm.end())));
                out.word(ordinal(a, form), st, numEnd); out.punct('—', numEnd, bFrom); out.word(ordinal(b, form), bFrom, sm.end());
                i = sm.end(); return;
            }
            if (a >= 1 && a <= 31 && b >= 1 && b <= 31 && at(DAY_AHEAD, end) != null) {
                if (PREPS.contains(prev)) { Ord f = dateOrd(prev); out.word(ordinal(a, f), st, numEnd); out.punct('—', numEnd, bFrom); out.word(ordinal(b, f), bFrom, end); }
                else { out.word("с " + ordinal(a, Ord.M_GEN), st, numEnd); out.word("по " + ordinal(b, Ord.N_NOM), bFrom, end); }
                i = end; return;
            }
            Tail y = yearNounAt(b, end, prev, true);
            if (y != null) {
                out.word(ordinal(a, y.form), st, numEnd); out.punct('—', numEnd, bFrom); out.word(ordinal(b, y.form), bFrom, end);
                i = end; y.emit(); return;
            }
            if (a >= 1000 && b <= 2100 && b > a && unitAt(end) == null) {
                out.word(ordinal(a, Ord.M_NOM), st, numEnd); out.punct('—', numEnd, bFrom); out.word(ordinal(b, Ord.M_NOM), bFrom, end);
                i = end; return;
            }
            Matcher um = unitAt(end);
            Unit u = um == null ? null : UNITS.get(um.group(1));
            int unitEnd = um == null ? end : unitTail(um);
            String nw = word(nextWord(unitEnd));
            // After a preposition keep its case ("в 5–7 раз"); otherwise read "от X до Y".
            boolean preposition = PREPS.contains(prev);
            Case c = preposition ? caseFor(prev, nw) : Case.GEN;
            char g = u != null ? u.noun.gender : genderFor(b, c, nw);
            if (preposition) { out.word(cardinal(a, c, g), st, numEnd); out.punct('—', numEnd, bFrom); out.word(cardinal(b, c, g), bFrom, end); }
            else { out.word("от " + cardinal(a, c, g), st, numEnd); out.word("до " + cardinal(b, c, g), bFrom, end); }
            if (u != null) out.word(u.noun.agree(b, c) + u.suffix, um.start(1), unitEnd);
            i = unitEnd;
            if (unitEnd > end && dotAt(unitEnd - 1)) dot(unitEnd - 1);
        }

        final class Tail {
            final Ord form; final String spoken; final int from, end;
            Tail(Ord form, String spoken, int from, int end) { this.form = form; this.spoken = spoken; this.from = from; this.end = end; }
            void emit() {
                if (spoken == null) return;
                out.word(spoken, from, end);
                i = end;
                if (dotAt(end - 1)) dot(end - 1);
            }
        }
        private final Map<String, Ord> yearForms = forms("год:M_NOM года:M_GEN году:M_DAT годом:M_INS годе:M_PRE годы:P_NOM годов:P_GEN годам:P_DAT годами:P_INS годах:P_GEN");
        /** "г."/"гг."/"год*" after a number: the ordinal form and, for abbreviations, the spoken noun. */
        Tail yearNounAt(long v, int pos, String prev, boolean range) {
            int[] next = nextWord(pos);
            if (next == null) return null;
            String w = word(next);
            boolean dot = dotAt(next[1]);
            int end = next[1] + (dot ? 1 : 0);
            if (w.equals("г") || w.equals("гг")) {
                boolean plural = w.equals("гг") || range;
                if (!plural && (v < 1000 || v > 2999)) return null;
                boolean loc = LOC.contains(prev) || PRE.contains(prev);
                if (plural) {
                    if (loc) return new Tail(Ord.P_GEN, "годах", next[0], end);
                    if (GEN.contains(prev) || WITH.contains(prev)) return new Tail(Ord.P_GEN, "годов", next[0], end);
                    if (DAT.contains(prev)) return new Tail(Ord.P_DAT, "годам", next[0], end);
                    return new Tail(Ord.P_NOM, "годы", next[0], end);
                }
                if (loc) return new Tail(Ord.M_PRE, "году", next[0], end);
                if (GEN.contains(prev) || WITH.contains(prev) || MONTH_WORDS.contains(prev)) return new Tail(Ord.M_GEN, "года", next[0], end);
                if (DAT.contains(prev)) return new Tail(Ord.M_DAT, "году", next[0], end);
                return new Tail(Ord.M_NOM, "год", next[0], end);
            }
            Ord f = yearForms.get(w);
            if (f == null || v < 1000) return null;
            if (w.equals("году") && (LOC.contains(prev) || PRE.contains(prev))) f = Ord.M_PRE;
            return new Tail(f, null, next[0], next[1]);
        }
        boolean yearNoun(long v, int st, int numEnd, String prev) {
            Tail y = yearNounAt(v, numEnd, prev, false);
            if (y == null) return false;
            out.word(ordinal(v, y.form), st, numEnd);
            i = numEnd;
            y.emit();
            return true;
        }
        void year(long v, Ord form, int st, int numEnd, boolean addNoun) {
            out.word(ordinal(v, form), st, numEnd);
            i = numEnd;
            int[] next = nextWord(numEnd);
            String w = word(next);
            if (w.equals("г")) { int end = next[1] + (dotAt(next[1]) ? 1 : 0); out.word("года", next[0], end); i = end; if (dotAt(end - 1)) dot(end - 1); }
            else if (addNoun && !w.equals("года")) out.word("года", st, numEnd);
        }

        private final Map<String, Ord> centuryForms = forms("век:M_NOM века:M_GEN веку:M_DAT веком:M_INS веке:M_PRE веков:P_GEN векам:P_DAT веками:P_INS веках:P_GEN "
            + "столетие:N_NOM столетия:M_GEN столетию:M_DAT столетием:M_INS столетии:M_PRE тысячелетие:N_NOM тысячелетия:M_GEN тысячелетию:M_DAT тысячелетием:M_INS тысячелетии:M_PRE");
        /** "век"/"в."/"вв."/"столетие" after a numeral: ordinal form and, for abbreviations, the spoken noun. */
        Tail centuryAt(int pos, String prev, boolean range) {
            int[] next = nextWord(pos);
            if (next == null) return null;
            String w = word(next);
            Ord f = centuryForms.get(w);
            if (f != null) return new Tail(range && f == Ord.M_GEN ? Ord.P_NOM : f, null, next[0], next[1]);
            if (!(w.equals("в") || w.equals("вв")) || !dotAt(next[1])) return null;
            boolean plural = w.equals("вв") || range, loc = LOC.contains(prev) || PRE.contains(prev);
            int end = next[1] + 1;
            if (loc) return plural ? new Tail(Ord.P_GEN, "веках", next[0], end) : new Tail(Ord.M_PRE, "веке", next[0], end);
            if (GEN.contains(prev) || WITH.contains(prev)) return plural ? new Tail(Ord.P_GEN, "веков", next[0], end) : new Tail(Ord.M_GEN, "века", next[0], end);
            if (DAT.contains(prev)) return plural ? new Tail(Ord.P_DAT, "векам", next[0], end) : new Tail(Ord.M_DAT, "веку", next[0], end);
            return plural ? new Tail(Ord.P_NOM, "века", next[0], end) : new Tail(Ord.M_NOM, "век", next[0], end);
        }
        boolean century(long v, int st, int numEnd, String prev) {
            Tail t = centuryAt(numEnd, prev, false);
            if (t == null) return false;
            out.word(ordinal(v, t.form), st, numEnd);
            i = numEnd;
            t.emit();
            return true;
        }
        private static Map<String, Ord> forms(String table) {
            Map<String, Ord> m = new HashMap<>();
            for (String p : table.split(" ")) { int k = p.indexOf(':'); m.put(p.substring(0, k), Ord.valueOf(p.substring(k + 1))); }
            return m;
        }

        // ---- letters ----
        boolean roman() {
            int a = i, b = i;
            while (b < n && "IVXLCDM".indexOf(s.charAt(b)) >= 0) b++;
            if (b == a || b < n && Character.isLetter(s.charAt(b)) || !ROMAN.matcher(s.substring(a, b)).matches()) return false;
            long v = romanValue(s.substring(a, b));
            String prev = prevWord(a);
            // "XIX–XX вв.": a Roman range before a century noun.
            Matcher rr = at(ROMAN_RANGE, b);
            if (rr != null && ROMAN.matcher(rr.group(1)).matches()) {
                Tail t = centuryAt(rr.end(), prev, true);
                if (t != null) {
                    out.word(ordinal(v, t.form), a, b); out.punct('—', b, rr.start(1)); out.word(ordinal(romanValue(rr.group(1)), t.form), rr.start(1), rr.end());
                    i = rr.end();
                    t.emit();
                    return true;
                }
            }
            if (century(v, a, b, prev)) return true;
            String raw = prevRaw(a);
            for (Map.Entry<String, Character> e : ROMAN_NOUNS.entrySet()) {
                if (!prev.startsWith(e.getKey())) continue;
                out.word(ordinal(v, nounForm(prev, e.getValue())), a, b);
                i = b;
                return true;
            }
            if (raw.length() >= 2 && Character.isUpperCase(raw.charAt(0)) && russian(raw.charAt(0))) {
                out.word(ordinal(v, prev.endsWith("а") || prev.endsWith("я") ? Ord.F_NOM : Ord.M_NOM), a, b);
                i = b;
                return true;
            }
            return false;
        }
        static long romanValue(String r) {
            long total = 0;
            for (int k = 0; k < r.length(); k++) {
                int v = val(r.charAt(k)), next = k + 1 < r.length() ? val(r.charAt(k + 1)) : 0;
                total += v < next ? -v : v;
            }
            return total;
        }
        private static int val(char c) { return c == 'I' ? 1 : c == 'V' ? 5 : c == 'X' ? 10 : c == 'L' ? 50 : c == 'C' ? 100 : c == 'D' ? 500 : 1000; }
        static Ord nounForm(String w, char gender) {
            if (gender == F) return w.endsWith("а") || w.endsWith("я") ? Ord.F_NOM : w.endsWith("у") || w.endsWith("ю") ? Ord.F_ACC : Ord.F_OBL;
            if (gender == N) return w.endsWith("е") ? Ord.N_NOM : w.endsWith("я") ? Ord.M_GEN : w.endsWith("ю") ? Ord.M_DAT : w.endsWith("ем") ? Ord.M_INS : Ord.M_PRE;
            return w.endsWith("ом") || w.endsWith("ем") ? Ord.M_INS : w.endsWith("а") || w.endsWith("я") ? Ord.M_GEN : w.endsWith("у") || w.endsWith("ю") ? Ord.M_DAT
                : w.endsWith("е") ? Ord.M_PRE : Ord.M_NOM;
        }

        boolean abbreviation() {
            if (!russian(s.charAt(i))) return false;
            for (int k = 0; k < ABBR_P.length; k++) {
                Matcher m = at(ABBR_P[k], i);
                if (m == null) continue;
                out.word(ABBR[k][1], i, m.end());
                i = m.end();
                if (dotAt(i - 1)) dot(i - 1);
                return true;
            }
            return false;
        }

        void word() {
            int st = i, j = i;
            while (j < n) {
                char c = s.charAt(j);
                if (Character.isLetter(c) || c == '́') j++;
                else if (c == '+' && j + 1 < n && cyrVowel(s.charAt(j + 1)) && (j == st || russian(s.charAt(j - 1)))) j++;
                else if ((c == '\'' || c == '’') && j > st && latin(s.charAt(j - 1)) && j + 1 < n && latin(s.charAt(j + 1))) j++;
                else break;
            }
            String w = s.substring(st, j);
            i = j;
            int cyr = 0, lat = 0;
            for (int k = 0; k < w.length(); k++) { char c = w.charAt(k); if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) cyr++; else if (latin(c)) lat++; }
            if (cyr > 0) { out.word(cyrillic(w), st, j); return; }
            if (lat > 0) {
                // Hyphenated dictionary words: "Wi-Fi", "e-mail".
                if (j + 1 < n && s.charAt(j) == '-' && latin(s.charAt(j + 1))) {
                    int k = j + 1;
                    while (k < n && latin(s.charAt(k))) k++;
                    if (COMPOUNDS.contains(s.substring(st, k).toLowerCase(Locale.ROOT))) { out.word(LatinTranslit.word(s.substring(st, k)), st, k); i = k; return; }
                }
                out.word(LatinTranslit.word(w), st, j);
                return;
            }
            StringBuilder g = new StringBuilder();
            for (int k = 0; k < w.length(); k++) {
                String name = GREEK.get(Character.toLowerCase(w.charAt(k)));
                if (name != null) { if (g.length() > 0) g.append(' '); g.append(name); }
            }
            if (g.length() > 0) out.word(g.toString(), st, j); else out.space(st, j);
        }

        /** Russian letters kept; Latin look-alikes mapped, Ukrainian/Belarusian letters approximated, others dropped. */
        static String cyrillic(String w) {
            StringBuilder b = new StringBuilder(w.length());
            for (int k = 0; k < w.length(); k++) {
                char c = w.charAt(k);
                int h = HOMO_LAT.indexOf(c);
                if (russian(c) || c == '+' || c == '́') b.append(c);
                else if (h >= 0) b.append(HOMO_CYR.charAt(h));
                else if (c == 'і' || c == 'І') b.append(c == 'і' ? 'и' : 'И');
                else if (c == 'ї' || c == 'Ї') b.append(c == 'ї' ? "йи" : "Йи");
                else if (c == 'є' || c == 'Є') b.append(c == 'є' ? 'е' : 'Е');
                else if (c == 'ґ' || c == 'Ґ') b.append(c == 'ґ' ? 'г' : 'Г');
                else if (c == 'ў' || c == 'Ў') b.append(c == 'ў' ? 'у' : 'У');
            }
            return b.toString();
        }

        // ---- symbols ----
        void symbol(char c) {
            int st = i;
            i++;
            switch (c) {
                case '.': {
                    int k = st;
                    while (k < n && s.charAt(k) == '.') k++;
                    i = k;
                    out.punct(k - st >= 2 ? '…' : '.', st, k);
                    return;
                }
                case ',': case ';': case ':': case '!': case '?': case '(': case ')': case '«': case '»': case '…': out.punct(c, st, i); return;
                case '[': out.punct('(', st, i); return;
                case ']': out.punct(')', st, i); return;
                case '“': case '„': out.punct('«', st, i); return;
                case '”': out.punct('»', st, i); return;
                case '"': out.punct(Character.isLetterOrDigit(before(st)) || ".,!?…)".indexOf(before(st)) >= 0 ? '»' : '«', st, i); return;
                case '-': case '‐': case '‑': case '‒': case '–': case '—': case '―': case '−': {
                    while (i < n && "-‐‑‒–—―−".indexOf(s.charAt(i)) >= 0) i++;
                    char p = before(st), q = i < n ? s.charAt(i) : ' ';
                    if (Character.isLetter(p) && Character.isLetter(q) && i == st + 1 && c != '—' && c != '–') out.punct('-', st, i);
                    else if (p == ' ' || p == NBSP || p == '\n' || q == ' ' || q == NBSP || q == '\n' || st == 0 || i == n) out.punct('—', st, i);
                    else out.space(st, i);
                    return;
                }
                case '+': out.word("плюс", st, i); return;
                case '&': out.word("и", st, i); return;
                case '=': out.word("равно", st, i); return;
                case '№': out.word("номер", st, i); return;
                case '§': out.word("параграф", st, i); return;
                case '@': out.word("собака", st, i); return;
                case '%': out.word("процент", st, i); return;
                case '‰': out.word("промилле", st, i); return;
                case '≈': out.word("примерно", st, i); return;
                case '±': out.word("плюс-минус", st, i); return;
                case '×': out.word("на", st, i); return;
                case '÷': out.word("разделить на", st, i); return;
                case '<': out.word("меньше", st, i); return;
                case '>': out.word("больше", st, i); return;
                case '≤': out.word("меньше или равно", st, i); return;
                case '≥': out.word("больше или равно", st, i); return;
                case '≠': out.word("не равно", st, i); return;
                case '°': out.word("градус", st, i); return;
                case '$': out.word("доллар", st, i); return;
                case '€': out.word("евро", st, i); return;
                case '£': out.word("фунт", st, i); return;
                case '¥': out.word("иена", st, i); return;
                case '₽': out.word("рубль", st, i); return;
                default:
                    // Emoji, bullets, slashes, other scripts' signs: a word break only.
                    if (Character.isHighSurrogate(c) && i < n && Character.isLowSurrogate(s.charAt(i))) i++;
                    out.space(st, i);
            }
        }

        // ---- output with source map ----
        final class Out {
            final StringBuilder t = new StringBuilder();
            int[] a = new int[64], b = new int[64];

            char last() { return t.length() == 0 ? '\n' : t.charAt(t.length() - 1); }
            void put(char c, int pf, int pt) {
                if (t.length() == a.length) { a = Arrays.copyOf(a, a.length * 2); b = Arrays.copyOf(b, b.length * 2); }
                int pe = Math.max(pf, pt - 1);
                a[t.length()] = from[Math.min(pf, n - 1)]; b[t.length()] = to[Math.min(pe, n - 1)];
                t.append(c);
            }
            void pop() { t.setLength(t.length() - 1); }
            void word(String w, int pf, int pt) {
                if (w.isEmpty()) return;
                char l = last();
                if (l != ' ' && l != '\n' && "(«-".indexOf(l) < 0) put(' ', pf, pt);
                for (int k = 0; k < w.length(); k++) put(w.charAt(k), pf, pt);
            }
            void punct(char p, int pf, int pt) {
                if (".,!?;:)»…".indexOf(p) >= 0) {
                    while (t.length() > 0 && last() == ' ') pop();
                    if (t.length() == 0 || last() == '\n') return;
                    if (p != '…' && p == last() && p != ')' && p != '»') return;
                } else if (p == '—') {
                    if (t.length() == 0 || last() == '\n') return;
                    if (last() == '—') return;
                    if (last() != ' ') put(' ', pf, pt);
                } else if (p == '(' || p == '«') {
                    if (t.length() > 0 && last() != ' ' && last() != '\n' && "(«".indexOf(last()) < 0) put(' ', pf, pt);
                } else if (p == '-' && (t.length() == 0 || !Character.isLetter(last()))) return;
                put(p, pf, pt);
            }
            void space(int pf, int pt) { if (t.length() > 0 && " \n(«-".indexOf(last()) < 0) put(' ', pf, pt); }
            void newline(int pf, int pt) {
                while (t.length() > 0 && last() == ' ') pop();
                if (t.length() > 0 && last() != '\n') put('\n', pf, pt);
            }
            Result result() {
                int end = t.length();
                while (end > 0 && (t.charAt(end - 1) == ' ' || t.charAt(end - 1) == '\n')) end--;
                return new Result(t.substring(0, end), Arrays.copyOf(a, end), Arrays.copyOf(b, end));
            }
        }
    }
}
