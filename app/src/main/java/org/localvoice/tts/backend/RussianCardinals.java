package org.localvoice.tts.backend;

/**
 * App-owned Russian number words: cardinals in five cases and three genders, ordinals in all adjective forms,
 * decimal fractions, and count agreement for following nouns. Accusative is spoken as nominative (inanimate).
 */
public final class RussianCardinals {
    private RussianCardinals() {}

    public enum Case { NOM, GEN, DAT, INS, PRE }
    /** Ordinal adjective forms; neuter oblique forms equal the masculine ones, P_GEN also serves prepositional plural. */
    public enum Ord { M_NOM, M_GEN, M_DAT, M_INS, M_PRE, F_NOM, F_OBL, F_ACC, N_NOM, P_NOM, P_GEN, P_DAT, P_INS }
    /** Genders; A is feminine accusative ("одну книгу"), spoken only in the nominative slot. */
    public static final char M = 'm', F = 'f', N = 'n', A = 'a';

    /** Noun forms: nomOne, nomFew (2-4), nomMany, sg GEN/DAT/INS/PRE, pl GEN/DAT/INS/PRE. */
    public static final class Noun {
        final String[] f; final char gender;
        Noun(char gender, String... forms) { this.gender = gender; f = forms; }
        static Noun hard(String stem) { return hard(stem, stem + "ов"); }
        static Noun hard(String stem, String genPl) {
            return new Noun(M, stem, stem + "а", genPl, stem + "а", stem + "у", stem + "ом", stem + "е", genPl, stem + "ам", stem + "ами", stem + "ах");
        }
        static Noun soft(String stem) {
            return new Noun(M, stem + "ь", stem + "я", stem + "ей", stem + "я", stem + "ю", stem + "ём", stem + "е", stem + "ей", stem + "ям", stem + "ями", stem + "ях");
        }
        static Noun fem(String stem, String genPl) {
            char last = stem.charAt(stem.length() - 1);
            String y = "кгхжшчщ".indexOf(last) >= 0 ? "и" : "ы", ins = "жшчщц".indexOf(last) >= 0 ? "ей" : "ой";
            return new Noun(F, stem + "а", stem + y, genPl, stem + y, stem + "е", stem + ins, stem + "е", genPl, stem + "ам", stem + "ами", stem + "ах");
        }
        static Noun fixed(char gender, String word) { String[] all = new String[11]; java.util.Arrays.fill(all, word); return new Noun(gender, all); }
        /** Form after the integer |n| in case c. */
        public String agree(long n, Case c) {
            n = Math.abs(n % 100);
            boolean one = n % 10 == 1 && n != 11;
            if (c == Case.NOM) return n >= 11 && n <= 14 ? f[2] : one ? f[0] : n % 10 >= 2 && n % 10 <= 4 ? f[1] : f[2];
            return f[(one ? 2 : 6) + c.ordinal()];
        }
        /** Form after a decimal fraction: genitive singular. */
        public String fraction() { return f[3]; }
    }

    static final Noun THOUSAND = Noun.fem("тысяч", "тысяч"), WHOLE = new Noun(F, "целая", "целых", "целых", "целой", "целой", "целой", "целой", "целых", "целым", "целыми", "целых");
    private static final Noun[] SCALES = {null, THOUSAND, Noun.hard("миллион"), Noun.hard("миллиард"), Noun.hard("триллион"), Noun.hard("квадриллион"), Noun.hard("квинтиллион")};
    private static final String[] SCALE_ORD = {"", "тысячн", "миллионн", "миллиардн", "триллионн", "квадриллионн", "квинтиллионн"};

    private static final String[] SMALL = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать",
        "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"};
    /** [value][case] for 0..19. */
    private static final String[][] UNIT = new String[20][];
    private static final String[][] TEN = new String[10][], HUNDRED = new String[10][];
    private static final String[][] ONE = {{"один", "одного", "одному", "одним", "одном"}, {"одна", "одной", "одной", "одной", "одной"}, {"одно", "одного", "одному", "одним", "одном"}};
    private static final String[] TWO_F = {"две", "двух", "двум", "двумя", "двух"};
    static {
        UNIT[0] = new String[]{"ноль", "ноля", "нолю", "нолём", "ноле"};
        UNIT[1] = ONE[0];
        UNIT[2] = new String[]{"два", "двух", "двум", "двумя", "двух"};
        UNIT[3] = new String[]{"три", "трёх", "трём", "тремя", "трёх"};
        UNIT[4] = new String[]{"четыре", "четырёх", "четырём", "четырьмя", "четырёх"};
        for (int v = 5; v < 20; v++) UNIT[v] = soft(SMALL[v]);
        UNIT[8] = new String[]{"восемь", "восьми", "восьми", "восемью", "восьми"};
        TEN[2] = soft("двадцать"); TEN[3] = soft("тридцать");
        TEN[4] = new String[]{"сорок", "сорока", "сорока", "сорока", "сорока"};
        for (int v = 5; v <= 8; v++) {
            String gen = UNIT[v][1] + "десяти";
            TEN[v] = new String[]{TENS[v], gen, gen, UNIT[v][3] + "десятью", gen};
        }
        TEN[9] = new String[]{"девяносто", "девяноста", "девяноста", "девяноста", "девяноста"};
        HUNDRED[1] = new String[]{"сто", "ста", "ста", "ста", "ста"};
        for (int v = 2; v <= 9; v++) HUNDRED[v] = new String[]{HUNDREDS[v], UNIT[v][1] + "сот", UNIT[v][2] + "стам", UNIT[v][3] + "стами", UNIT[v][1] + "стах"};
    }
    private static String[] soft(String nom) { String stem = nom.substring(0, nom.length() - 1); return new String[]{nom, stem + "и", stem + "и", nom + "ю", stem + "и"}; }

    /** Standalone nominative masculine cardinal; never throws. */
    public static String spellout(long value) { return cardinal(value, Case.NOM, M); }

    public static String cardinal(long n, Case c, char gender) {
        if (n == Long.MIN_VALUE) return "минус " + digits(Long.toString(n).substring(1));
        if (n < 0) return "минус " + cardinal(-n, c, gender);
        if (n == 0) return UNIT[0][c.ordinal()];
        StringBuilder out = new StringBuilder();
        long scale = 1_000_000_000_000_000_000L;
        for (int k = 6; k >= 0; k--, scale /= 1000) {
            int g = (int) (n / scale % 1000);
            if (g == 0) continue;
            boolean leadingThousand = k == 1 && g == 1 && n < 2000;
            // "тысяча", not "одна тысяча", when it leads the number.
            if (!leadingThousand) group(out, g, c, k == 0 ? gender : SCALES[k].gender);
            if (k > 0) add(out, SCALES[k].agree(g, c));
        }
        return out.toString();
    }

    private static void group(StringBuilder out, int g, Case c, char gender) {
        int i = c.ordinal();
        if (g >= 100) add(out, HUNDRED[g / 100][i]);
        g %= 100;
        if (g >= 20) { add(out, TEN[g / 10][i]); g %= 10; }
        if (g == 0) return;
        add(out, g == 1 ? (gender == A && c == Case.NOM ? "одну" : ONE[gender == F || gender == A ? 1 : gender == N ? 2 : 0][i]) : g == 2 && (gender == F || gender == A) ? TWO_F[i] : UNIT[g][i]);
    }

    private static final String[] ORD_SMALL = {"нулев", "перв", "втор", "трет", "четвёрт", "пят", "шест", "седьм", "восьм", "девят", "десят", "одиннадцат",
        "двенадцат", "тринадцат", "четырнадцат", "пятнадцат", "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат"};
    private static final String[] ORD_TENS = {"", "", "двадцат", "тридцат", "сороков", "пятидесят", "шестидесят", "семидесят", "восьмидесят", "девяност"};
    private static final String[] ORD_HUNDREDS = {"", "сот", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот", "восьмисот", "девятисот"};
    private static final String[] HARD = {"ый", "ого", "ому", "ым", "ом", "ая", "ой", "ую", "ое", "ые", "ых", "ым", "ыми"};
    private static final String[] STRESSED = HARD.clone(), THIRD = {"ий", "ьего", "ьему", "ьим", "ьем", "ья", "ьей", "ью", "ье", "ьи", "ьих", "ьим", "ьими"};
    static { STRESSED[0] = "ой"; }

    public static String ordinal(long n, Ord form) {
        if (n == Long.MIN_VALUE) return cardinal(n, Case.NOM, M);
        if (n < 0) return "минус " + ordinal(-n, form);
        long prefix; String stem;
        String[] endings = HARD;
        if (n % 1000 != 0 || n == 0) {
            // Only the last word becomes an ordinal: hundreds, tens, or 0..19.
            int r = (int) (n % 1000), last2 = r % 100, last;
            if (n != 0 && last2 == 0) { last = r; stem = ORD_HUNDREDS[r / 100]; }
            else if (last2 >= 20 && last2 % 10 == 0) { last = last2; stem = ORD_TENS[last2 / 10]; if (last2 == 40) endings = STRESSED; }
            else {
                last = last2 < 20 ? last2 : last2 % 10;
                stem = ORD_SMALL[last];
                if (last == 0 || last == 2 || last == 6 || last == 7 || last == 8) endings = STRESSED;
                if (last == 3) endings = THIRD;
            }
            prefix = n - last;
        } else {
            int k = 1; long scale = 1000;
            while (n / scale % 1000 == 0) { k++; scale *= 1000; }
            int g = (int) (n / scale % 1000);
            stem = compound(g) + SCALE_ORD[k];
            prefix = n - g * scale;
        }
        String head = prefix == 0 ? "" : cardinal(prefix, Case.NOM, M) + " ";
        return head + stem + endings[form.ordinal()];
    }

    /** Genitive-based combining form of a scale multiplier: 2 → "двух", 21 → "двадцатиодно", 1 → "". */
    private static String compound(int g) {
        if (g == 1) return "";
        StringBuilder out = new StringBuilder();
        if (g >= 100) out.append(g / 100 == 1 ? "сто" : HUNDRED[g / 100][1]);
        g %= 100;
        if (g >= 20) { out.append(TEN[g / 10][1]); g %= 10; }
        if (g > 0) out.append(g == 1 ? "одно" : UNIT[g][1]);
        return out.toString();
    }

    public static Ord ord(Case c, char gender, boolean plural) {
        if (plural) return c == Case.NOM ? Ord.P_NOM : c == Case.DAT ? Ord.P_DAT : c == Case.INS ? Ord.P_INS : Ord.P_GEN;
        if (gender == F) return c == Case.NOM ? Ord.F_NOM : Ord.F_OBL;
        return c == Case.NOM ? (gender == N ? Ord.N_NOM : Ord.M_NOM) : Ord.values()[c.ordinal()];
    }

    private static final String[] DENOMINATORS = {"", "десят", "сот", "тысячн", "десятитысячн", "стотысячн", "миллионн"};

    /** "3,14" → "три целых четырнадцать сотых"; over six fractional digits → "... запятая" digit by digit. */
    public static String decimal(String whole, String fraction, Case c) {
        String intWords = whole.length() > 18 ? digitGroups(whole) : cardinal(Long.parseLong(whole), c, F);
        if (fraction.length() > 6 || whole.length() > 18) return intWords + " запятая " + digits(fraction);
        long w = Long.parseLong(whole), f = Long.parseLong(fraction);
        boolean one = f % 10 == 1 && f % 100 != 11;
        Ord denominator = one ? (c == Case.NOM ? Ord.F_NOM : Ord.F_OBL) : c == Case.NOM ? Ord.P_GEN : ord(c, F, true);
        return intWords + " " + WHOLE.agree(w, c) + " " + cardinal(f, c, F) + " " + DENOMINATORS[fraction.length()] + HARD[denominator.ordinal()];
    }

    /** Each digit as its nominative name. */
    public static String digits(String digits) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) if (Character.isDigit(digits.charAt(i))) add(out, SMALL[Character.digit(digits.charAt(i), 10)]);
        return out.toString();
    }

    /** IDs and over-long numbers: groups of three (last groups 2+2 when four remain), leading-zero groups digit by digit. */
    public static String digitGroups(String digits) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = digits.length();
        while (i < n) {
            int left = n - i, take = left == 4 ? 2 : Math.min(3, left);
            String g = digits.substring(i, i + take);
            add(out, g.length() > 1 && g.charAt(0) == '0' ? digits(g) : cardinal(Long.parseLong(g), Case.NOM, M));
            i += take;
        }
        return out.toString();
    }

    private static void add(StringBuilder out, String word) { if (out.length() != 0) out.append(' '); out.append(word); }
}
