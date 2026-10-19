package org.localvoice.tts.backend;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based English-to-Russian practical transcription for Latin words in Russian text. Dictionary words and
 * brands first; all-caps tokens of up to five letters and vowel-less tokens are spelled with English letter names
 * joined by hyphens ("USA" → "ю-эс-эй"); camelCase parts are read separately; everything else goes through
 * grapheme rules. Output is lower-case Cyrillic, capitalized when the source word was.
 */
final class LatinTranslit {
    private LatinTranslit() {}

    private static final String[] LETTERS = {"эй", "би", "си", "ди", "и", "эф", "джи", "эйч", "ай", "джей", "кей", "эл", "эм", "эн", "оу", "пи", "кью", "ар", "эс", "ти",
        "ю", "ви", "дабл-ю", "экс", "уай", "зед"};
    private static final Map<String, String> WORDS = new HashMap<>();
    static {
        String table = "google=гугл microsoft=майкрософт iphone=айфон ipad=айпад android=андроид windows=виндоус youtube=ютуб ok=окей okay=окей "
            + "apple=эппл facebook=фейсбук twitter=твиттер instagram=инстаграм telegram=телеграм whatsapp=вотсап chrome=хром edge=эдж linux=линукс "
            + "github=гитхаб amazon=амазон netflix=нетфликс samsung=самсунг xiaomi=сяоми huawei=хуавэй intel=интел nvidia=энвидиа openai=опенэйай "
            + "chatgpt=чатджипити wifi=вайфай wi-fi=вайфай bluetooth=блютус email=имейл e-mail=имейл online=онлайн offline=офлайн internet=интернет "
            + "web=веб app=эпп apps=эппс the=зе and=энд of=оф to=ту a=э an=эн is=из are=ар in=ин for=фор on=он with=уиз you=ю your=ёр i=ай it=ит "
            + "this=зис that=зэт new=нью york=йорк hello=хеллоу world=уорлд love=лав music=мьюзик news=ньюс video=видео game=гейм games=геймз "
            + "pro=про max=макс mini=мини plus=плюс ultra=ультра home=хоум office=офис word=ворд excel=эксель powerpoint=пауэрпойнт skype=скайп "
            + "zoom=зум tiktok=тикток spotify=спотифай steam=стим playstation=плейстейшн xbox=иксбокс nintendo=нинтендо tesla=тесла "
            + "mercedes=мерседес toyota=тойота python=пайтон java=джава javascript=джаваскрипт covid=ковид coronavirus=коронавирус macbook=макбук "
            + "mac=мак ios=ай-о-эс macos=мак-о-эс wikipedia=википедия yandex=яндекс mail=мейл gmail=джимейл browser=браузер reader=ридер read=рид "
            + "aloud=элауд voice=войс local=локал speech=спич text=текст file=файл open=оупен source=сорс free=фри update=апдейт download=даунлоуд "
            + "login=логин password=пассворд user=юзер server=сервер cloud=клауд smart=смарт phone=фон mobile=мобайл play=плей store=стор "
            + "market=маркет shop=шоп black=блэк friday=фрайдей team=тим star=стар wars=ворс one=уан two=ту three=фри nasa=наса nato=нато "
            + "unesco=юнеско bmw=бэ-эм-вэ gif=гиф jpeg=джейпег png=пи-эн-джи wiki=вики blog=блог chat=чат bot=бот start=старт stop=стоп "
            + "like=лайк post=пост site=сайт page=пейдж click=клик link=линк google's=гуглс iphone's=айфонс sony=сони lg=эл-джи hp=эйч-пи "
            + "dell=делл lenovo=леново asus=асус acer=асер opera=опера firefox=файрфокс safari=сафари edge's=эджс kotlin=котлин rust=раст "
            + "docker=докер git=гит vs=версус feat=фит ft=фит mr=мистер mrs=миссис ms=мисс dr=доктор inc=инкорпорейтед ltd=лимитед llc=эл-эл-си "
            + "co=ко ya=я yes=йес no=ноу not=нот what=вот how=хау why=вай who=ху my=май me=ми we=ви he=хи she=ши they=зэй "
            + "be=би do=ду go=гоу so=соу all=олл by=бай up=ап out=аут about=эбаут more=мор time=тайм day=дэй good=гуд best=бест big=биг "
            + "people=пипл thank=сенк thanks=сенкс please=плиз sorry=сорри world's=уорлдс";
        for (String pair : table.split(" ")) { int eq = pair.indexOf('='); WORDS.put(pair.substring(0, eq), pair.substring(eq + 1)); }
    }

    /** One Latin token (letters, optionally with internal hyphens or apostrophes) as spoken Cyrillic. */
    static String word(String raw) {
        String plain = fold(raw);
        if (plain.isEmpty()) return "";
        String lower = plain.toLowerCase(Locale.ROOT);
        if (plain.length() == 1 && Character.isUpperCase(plain.charAt(0))) return capital(raw, spell(lower));
        String known = WORDS.get(lower);
        if (known != null) return capital(raw, known);
        if (plain.indexOf('-') >= 0) {
            StringBuilder out = new StringBuilder();
            for (String part : plain.split("-+")) if (!part.isEmpty()) { if (out.length() > 0) out.append(' '); out.append(word(part)); }
            return out.toString();
        }
        lower = lower.replace("'", "").replace("’", "");
        plain = plain.replace("'", "").replace("’", "");
        if (plain.isEmpty()) return "";
        // camelCase and CAPSword parts: "macOS", "PlayStation", "XMLHttp".
        StringBuilder out = new StringBuilder();
        int start = 0;
        for (int i = 1; i <= plain.length(); i++) {
            boolean cut = i == plain.length()
                || Character.isLowerCase(plain.charAt(i - 1)) && Character.isUpperCase(plain.charAt(i))
                || i + 1 < plain.length() && Character.isUpperCase(plain.charAt(i - 1)) && Character.isUpperCase(plain.charAt(i)) && Character.isLowerCase(plain.charAt(i + 1));
            if (!cut) continue;
            String part = plain.substring(start, i);
            if (out.length() > 0) out.append(' ');
            out.append(part(part, start == 0 && i == plain.length()));
            start = i;
        }
        return capital(raw, out.toString());
    }

    private static String part(String part, boolean whole) {
        String lower = part.toLowerCase(Locale.ROOT);
        String known = WORDS.get(lower);
        if (known != null && (whole || lower.length() > 1)) return known;
        boolean caps = part.equals(part.toUpperCase(Locale.ROOT));
        if (caps && part.length() <= 5 || !hasVowel(lower) || part.length() == 1) return spell(lower);
        return rules(lower);
    }

    /** "usa" → "ю-эс-эй". */
    static String spell(String letters) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < letters.length(); i++) {
            char c = Character.toLowerCase(letters.charAt(i));
            if (c < 'a' || c > 'z') continue;
            if (out.length() > 0) out.append('-');
            out.append(LETTERS[c - 'a']);
        }
        return out.toString();
    }

    /** ASCII letters, hyphens and apostrophes only: diacritics stripped, ß/æ/ø/œ expanded, other letters dropped. */
    static String fold(String raw) {
        String d = Normalizer.normalize(raw, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c < 128 && (Character.isLetter(c) || c == '-' || c == '\'')) out.append(c);
            else if (c == '’') out.append('\'');
            else if (c == 'ß') out.append("ss");
            else if (c == 'æ' || c == 'Æ') out.append(c == 'æ' ? "ae" : "Ae");
            else if (c == 'ø' || c == 'Ø') out.append(c == 'ø' ? 'o' : 'O');
            else if (c == 'œ' || c == 'Œ') out.append(c == 'œ' ? "oe" : "Oe");
            else if (c == 'ł' || c == 'Ł') out.append(c == 'ł' ? 'l' : 'L');
            else if (c == 'đ' || c == 'Đ') out.append(c == 'đ' ? 'd' : 'D');
        }
        return out.toString();
    }

    private static boolean vowel(char c) { return "aeiouy".indexOf(c) >= 0; }
    private static boolean hasVowel(String w) { for (int i = 0; i < w.length(); i++) if (vowel(w.charAt(i))) return true; return false; }
    private static boolean at(String w, int i, String s) { return w.startsWith(s, i); }

    /** Grapheme rules on a lower-case ASCII word. */
    static String rules(String w) {
        StringBuilder o = new StringBuilder();
        int n = w.length();
        boolean[] silent = new boolean[n + 1];
        for (int i = 0; i < n; ) {
            char c = w.charAt(i);
            char next = i + 1 < n ? w.charAt(i + 1) : 0, prev = i > 0 ? w.charAt(i - 1) : 0;
            if (silent[i]) { i++; continue; }
            String r; int k = 1;
            if (at(w, i, "tch")) { r = "ч"; k = 3; }
            else if (at(w, i, "tion")) { r = "шн"; k = 4; }
            else if (at(w, i, "sion")) { r = i > 0 && vowel(prev) ? "жн" : "шн"; k = 4; }
            else if (at(w, i, "ture")) { r = "чер"; k = 4; }
            else if (at(w, i, "eigh")) { r = "ей"; k = 4; }
            else if (at(w, i, "igh")) { r = "ай"; k = 3; }
            else if (at(w, i, "ough") || at(w, i, "augh")) { r = "о"; k = 4; }
            else if (at(w, i, "sh")) { r = "ш"; k = 2; }
            else if (at(w, i, "ch")) { r = "ч"; k = 2; }
            else if (at(w, i, "ph")) { r = "ф"; k = 2; }
            else if (at(w, i, "th")) { r = "т"; k = 2; }
            else if (at(w, i, "ck")) { r = "к"; k = 2; }
            else if (at(w, i, "qu")) { r = "кв"; k = 2; }
            else if (at(w, i, "wh")) { r = "у"; k = 2; }
            else if (i == 0 && at(w, i, "kn")) { r = "н"; k = 2; }
            else if (i == 0 && at(w, i, "wr")) { r = "р"; k = 2; }
            else if (at(w, i, "gh")) { r = i == 0 ? "г" : ""; k = 2; }
            else if (at(w, i, "ee") || at(w, i, "ea")) { r = "и"; k = 2; }
            else if (at(w, i, "oo")) { r = "у"; k = 2; }
            else if (at(w, i, "ou")) { r = "ау"; k = 2; }
            else if (at(w, i, "ow")) { r = i + 2 == n ? "оу" : "ау"; k = 2; }
            else if (at(w, i, "oa")) { r = "оу"; k = 2; }
            else if (at(w, i, "ai") || at(w, i, "ay") || at(w, i, "ei")) { r = "ей"; k = 2; }
            else if (at(w, i, "ey")) { r = i + 2 == n ? "и" : "ей"; k = 2; }
            else if (at(w, i, "oi") || at(w, i, "oy")) { r = "ой"; k = 2; }
            else if (at(w, i, "au") || at(w, i, "aw")) { r = "о"; k = 2; }
            else if (at(w, i, "ew")) { r = "ью"; k = 2; }
            else if (at(w, i, "ue")) { r = "ю"; k = 2; }
            else if (at(w, i, "ie") || at(w, i, "ui")) { r = "и"; k = 2; }
            else if (i > 0 && c == prev && !vowel(c)) r = "";
            else if (c == 'd' && next == 'g' && i + 2 < n && "eiy".indexOf(w.charAt(i + 2)) >= 0) r = "";
            else {
                boolean magic = "aiou".indexOf(c) >= 0 && i + 2 < n && !vowel(next) && "wx".indexOf(next) < 0 && w.charAt(i + 2) == 'e'
                    && (i + 3 == n || i + 4 == n && w.charAt(i + 3) == 's');
                if (magic) silent[i + 2] = true;
                switch (c) {
                    case 'a': r = magic || at(w, i + 1, "tion") ? "ей" : "а"; break;
                    case 'e': r = i == n - 1 && n > 2 && hasVowel(w.substring(0, i)) ? "" : i == 0 || vowel(prev) ? "э" : "е"; break;
                    case 'i': r = magic ? "ай" : "и"; break;
                    case 'o': r = magic ? "оу" : "о"; break;
                    // Short (closed-syllable) u is "а": bus, jump, number.
                    case 'u': r = magic ? "ю" : i == 0 ? (i + 2 < n && !vowel(next) && vowel(w.charAt(i + 2)) ? "ю" : "а")
                        : next != 0 && !vowel(next) && (i + 2 >= n || !vowel(w.charAt(i + 2))) ? "а" : "у"; break;
                    case 'h': r = i == 0 || vowel(prev) && vowel(next) ? "х" : ""; break;
                    case 'y': r = i == 0 && vowel(next) ? "й" : i == n - 1 && n <= 3 && !hasVowel(w.substring(0, i)) ? "ай" : vowel(next) ? "й" : "и"; break;
                    case 'c': r = "eiy".indexOf(next) >= 0 && next != 0 ? "с" : "к"; break;
                    case 'g': r = "eiy".indexOf(next) >= 0 && next != 0 && !(i == 0 && (at(w, 0, "get") || at(w, 0, "giv"))) ? "дж" : "г"; break;
                    case 's': r = i > 0 && i < n - 1 && vowel(prev) && vowel(next) ? "з" : "с"; break;
                    case 'x': r = i == 0 ? "з" : "кс"; break;
                    default: r = consonant(c);
                }
            }
            o.append(r);
            i += k;
        }
        return o.toString();
    }

    private static String consonant(char c) {
        int i = "bdfhjklmnpqrtvwz".indexOf(c);
        return i < 0 ? "" : new String[]{"б", "д", "ф", "х", "дж", "к", "л", "м", "н", "п", "к", "р", "т", "в", "в", "з"}[i];
    }

    private static String capital(String raw, String spoken) {
        // Title-case source words stay capitalized; acronyms and lower-case words do not.
        if (spoken.isEmpty() || raw.length() < 2 || !Character.isUpperCase(raw.charAt(0)) || !Character.isLowerCase(raw.charAt(raw.length() - 1))) return spoken;
        return spoken.substring(0, 1).toUpperCase(Locale.ROOT) + spoken.substring(1);
    }
}
