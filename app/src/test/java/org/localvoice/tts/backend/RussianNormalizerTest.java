package org.localvoice.tts.backend;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** RussianNormalizer: expected spoken forms, source offset map, and a never-throw fuzz over web-like text. */
public final class RussianNormalizerTest {
    /** Characters the Shtorm fixtures prove are vocabulary pieces (tests/fixtures/shtorm-tokenizer-parity.json), plus
     *  '…' (NFKC "...") and Silero's '+' stress marker, which Shtorm's encodeSpeakable drops. */
    static final String SHTORM_PUNCT = "!(),-.:;?«»—…+ \ń";

    @Before public void strict() { RussianNormalizer.strict = true; }
    @After public void lenient() { RussianNormalizer.strict = false; }

    static final String[][] CASES = {
        {"0", "ноль"},
        {"7", "семь"},
        {"21", "двадцать один"},
        {"-12", "минус двенадцать"},
        {"1000", "тысяча"},
        {"1001", "тысяча один"},
        {"2026", "две тысячи двадцать шесть"},
        {"1000000", "один миллион"},
        {"1 000 000", "один миллион"},
        {"1 234 567", "один миллион двести тридцать четыре тысячи пятьсот шестьдесят семь"},
        {"12 345,67", "двенадцать тысяч триста сорок пять целых шестьдесят семь сотых"},
        {"999999999999", "девятьсот девяносто девять миллиардов девятьсот девяносто девять миллионов девятьсот девяносто девять тысяч девятьсот девяносто девять"},
        {"1000000000000000", "один квадриллион"},
        {"12345678901234567890", "сто двадцать три четыреста пятьдесят шесть семьсот восемьдесят девять ноль один два триста сорок пять шестьсот семьдесят восемь девяносто"},
        {"007", "ноль ноль семь"},
        {"+5", "плюс пять"},
        {"−7", "минус семь"},
        {"3,14", "три целых четырнадцать сотых"},
        {"2.5", "две целых пять десятых"},
        {"0,05", "ноль целых пять сотых"},
        {"1,001", "одна целая одна тысячная"},
        {"-3,5", "минус три целых пять десятых"},
        {"3,1415926", "три запятая один четыре один пять девять два шесть"},
        {"15%", "пятнадцать процентов"},
        {"2,5%", "две целых пять десятых процента"},
        {"5–7", "от пяти до семи"},
        {"5-7 дней", "от пяти до семи дней"},
        {"в 5–7 раз", "в пять — семь раз"},
        {"1990-1995", "тысяча девятьсот девяностый — тысяча девятьсот девяносто пятый"},
        {"3-5%", "от трёх до пяти процентов"},
        {"1-й", "первый"},
        {"2-го", "второго"},
        {"21-я", "двадцать первая"},
        {"3-му", "третьему"},
        {"на 3-й странице", "на третьей странице"},
        {"в 1990-х", "в тысяча девятьсот девяностых"},
        {"90-е годы", "девяностые годы"},
        {"2-х", "двух"},
        {"5-ти", "пяти"},
        {"100-й", "сотый"},
        {"2000-го", "двухтысячного"},
        {"XX век", "двадцатый век"},
        {"в XIX веке", "в девятнадцатом веке"},
        {"XIX–XX вв.", "девятнадцатые — двадцатые века."},
        {"Пётр I", "Пётр первый"},
        {"Екатерина II", "Екатерина вторая"},
        {"глава XII", "глава двенадцатая"},
        {"19.10.2026", "девятнадцатое октября две тысячи двадцать шестого года"},
        {"19.10.26", "девятнадцатое октября две тысячи двадцать шестого года"},
        {"2026-10-19", "девятнадцатое октября две тысячи двадцать шестого года"},
        {"до 19.10.2026", "до девятнадцатого октября две тысячи двадцать шестого года"},
        {"19 октября 2026 г.", "девятнадцатое октября две тысячи двадцать шестого года."},
        {"1 января", "первое января"},
        {"до 1 сентября", "до первого сентября"},
        {"к 8 марта", "к восьмому марта"},
        {"с 1 по 5 мая", "с первого по пятое мая"},
        {"5–7 мая", "с пятого по седьмое мая"},
        {"в 2026 году", "в две тысячи двадцать шестом году"},
        {"в 2026 г.", "в две тысячи двадцать шестом году."},
        {"2026 г.", "две тысячи двадцать шестой год."},
        {"с 2020 года", "с две тысячи двадцатого года"},
        {"в октябре 2026", "в октябре две тысячи двадцать шестого года"},
        {"до 31.12", "до тридцать первого декабря"},
        {"12:30", "двенадцать тридцать"},
        {"7:05", "семь ноль пять"},
        {"12:30:15", "двенадцать часов тридцать минут пятнадцать секунд"},
        {"до 12:30", "до двенадцати тридцати"},
        {"в 12.30", "в двенадцать тридцать"},
        {"с 9:00 до 18:00", "с девяти ноль ноль до восемнадцати ноль ноль"},
        {"100 руб.", "сто рублей."},
        {"1 рубль", "один рубль"},
        {"2 рубля", "два рубля"},
        {"5 рублей", "пять рублей"},
        {"$5", "пять долларов"},
        {"5 $", "пять долларов"},
        {"10 €", "десять евро"},
        {"1 999,99 ₽", "тысяча девятьсот девяносто девять рублей девяносто девять копеек"},
        {"5 тыс. руб.", "пять тысяч рублей."},
        {"3 млн долларов", "три миллиона долларов"},
        {"150 млрд", "сто пятьдесят миллиардов"},
        {"5 кг", "пять килограммов"},
        {"1 км", "один километр"},
        {"2,5 км", "две целых пять десятых километра"},
        {"до 300 км", "до трёхсот километров"},
        {"60 км/ч", "шестьдесят километров в час"},
        {"-5°C", "минус пять градусов Цельсия"},
        {"+25 °C", "плюс двадцать пять градусов Цельсия"},
        {"80 %", "восемьдесят процентов"},
        {"5 мин.", "пять минут."},
        {"2 ч 30 мин", "два часа тридцать минут"},
        {"5 с половиной", "пять с половиной"},
        {"1 ГБ", "один гигабайт"},
        {"+7 (999) 123-45-67", "плюс семь, девятьсот девяносто девять, сто двадцать три, сорок пять, шестьдесят семь"},
        {"8 (800) 555-35-35", "восемь, восемьсот, пятьсот пятьдесят пять, тридцать пять, тридцать пять"},
        {"123-45-67", "сто двадцать три, сорок пять, шестьдесят семь"},
        {"1.13.8", "один точка тринадцать точка восемь"},
        {"192.168.1.1", "сто девяносто два точка сто шестьдесят восемь точка один точка один"},
        {"Версия 2.0", "Версия два точка ноль"},
        {"https://www.google.com/search?q=x", "гугл точка ком"},
        {"www.example.org", "эксампл точка орг"},
        {"ya.ru", "я точка ру"},
        {"info@example.ru", "инфо собака эксампл точка ру"},
        {"Google", "Гугл"},
        {"Microsoft Edge", "Майкрософт Эдж"},
        {"iPhone 15", "айфон пятнадцать"},
        {"YouTube", "Ютуб"},
        {"USA", "ю-эс-эй"},
        {"COVID-19", "ковид девятнадцать"},
        {"Wi-Fi", "Вайфай"},
        {"macOS", "мак-о-эс"},
        {"mp3", "эм-пи три"},
        {"Hello world", "Хеллоу уорлд"},
        {"the quick brown fox", "зе квик браун фокс"},
        {"A & B", "эй и би"},
        {"x + y = z", "экс плюс уай равно зед"},
        {"№ 5", "номер пять"},
        {"§ 3", "параграф три"},
        {"Привет 😀 мир", "Привет мир"},
        {"У меня 21 книга.", "У меня двадцать одна книга."},
        {"Он выпил 1 чашку.", "Он выпил одну чашку."},
        {"Было 2 часа и 2 минуты.", "Было два часа и две минуты."},
        {"с 5 до 7", "с пяти до семи"},
        {"до 10 человек", "до десяти человек"},
        {"около 300 км", "около трёхсот километров"},
        {"к 5 часам", "к пяти часам"},
        {"о 3 книгах", "о трёх книгах"},
        {"с 2 друзьями", "с двумя друзьями"},
        {"в 5 странах", "в пяти странах"},
        {"свыше 21 тысячи", "свыше двадцати одной тысячи"},
        {"т.е. так", "то есть так"},
        {"и т.д.", "и так далее."},
        {"и др.", "и другие."},
        {"1/2 стакана", "одна вторая стакана"},
        {"3/4", "три четвёртых"},
        {"24/7", "двадцать четыре дробь семь"},
        {"1920x1080", "тысяча девятьсот двадцать на тысяча восемьдесят"},
        {"α и β", "альфа и бета"},
        {"Мocквa", "Москва"},
        {"Київ", "Кийив"},
        {"«Цитата» и \"кавычки\" и “лапки”", "«Цитата» и «кавычки» и «лапки»"},
        {"Слово — слово – слово", "Слово — слово — слово"},
        {"кто-то", "кто-то"},
        {"Ну...", "Ну…"},
        {"Что?!", "Что?!"},
        {"з+амок", "з+амок"},
        {"Приве́т", "Приве́т"},
        {"ёлка", "ёлка"},
        {"в21веке", "в двадцать первом веке"},
    };

    @Test public void table() {
        assertTrue(CASES.length >= 80);
        List<String> failures = new ArrayList<>();
        for (String[] c : CASES) {
            String actual = RussianNormalizer.apply(c[0]);
            if (!actual.equals(c[1])) failures.add(c[0] + " => [" + actual + "] expected [" + c[1] + "]");
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test public void cardinalsDeclineAndOrdinalsInflect() {
        assertEquals("двухсот пятидесяти трёх", RussianCardinals.cardinal(253, RussianCardinals.Case.GEN, RussianCardinals.M));
        assertEquals("пятьюдесятью двумя", RussianCardinals.cardinal(52, RussianCardinals.Case.INS, RussianCardinals.F));
        assertEquals("двадцать третьего", RussianCardinals.ordinal(23, RussianCardinals.Ord.M_GEN));
        assertEquals("сороковой", RussianCardinals.ordinal(40, RussianCardinals.Ord.M_NOM));
        assertEquals("трёхсотой", RussianCardinals.ordinal(300, RussianCardinals.Ord.F_OBL));
        assertEquals("миллионный", RussianCardinals.ordinal(1_000_000, RussianCardinals.Ord.M_NOM));
        assertEquals("нулевой", RussianCardinals.ordinal(0, RussianCardinals.Ord.M_NOM));
        assertEquals("пяти целых двадцати пяти сотых", RussianCardinals.decimal("5", "25", RussianCardinals.Case.GEN));
        assertEquals("одной целой одной десятой", RussianCardinals.decimal("1", "1", RussianCardinals.Case.DAT));
    }

    @Test public void offsetsPointAtOriginalTokens() {
        String text = "В 2026 году Google поднял цены на 5%.";
        RussianNormalizer.Result r = RussianNormalizer.normalize(text);
        assertSource(text, r, "две тысячи двадцать шестом", "2026");
        assertSource(text, r, "году", "году");
        assertSource(text, r, "Гугл", "Google");
        assertSource(text, r, "пять", "5");
        assertSource(text, r, "процентов", "%");

        text = "😀 Цена $5, дата 19.10.2026 г.";
        r = RussianNormalizer.normalize(text);
        assertSource(text, r, "пять", "5");
        assertSource(text, r, "долларов", "$");
        assertSource(text, r, "девятнадцатое октября две тысячи двадцать шестого года", "19.10.2026");
        assertArrayEquals(new int[]{-1, -1}, r.source(3, 3));
    }

    private static void assertSource(String text, RussianNormalizer.Result r, String spoken, String original) {
        int at = r.text.indexOf(spoken);
        assertTrue(spoken + " in " + r.text, at >= 0);
        int[] range = r.source(at, at + spoken.length());
        assertEquals(spoken, original, text.substring(range[0], range[1]));
    }

    private static final String[] WEB = {"19.10.2026", "2026-10-19", "12:30", "7:05:09", "3,14", "1 000 000", "15%", "−5°C", "$9.99", "€10", "5 тыс. руб.",
        "https://example.com/a?b=c", "info@mail.ru", "+7 (999) 123-45-67", "1.13.8", "XIX", "COVID-19", "iPhone", "macOS", "Wi-Fi", "№", "§", "&", "=",
        "1990-х", "3-й", "21-я", "5–7", "г.", "в.", "т.е.", "😀", "👍🏽", "李明", "مرحبا", "α", "ß", "é", "́", "\uD800", "\uDC00", "​", "\t", "\n", "\r\n",
        " ", " ", "…", "...", "?!", "«", "»", "\"", "'", "—", "-", "‑", "+", "(", ")", "[", "]", "/", "\\", "|", "*", "#", "@", "^", "_", "~", "`",
        "0", "00", "007", "9999999999999999999999", "-0", "1/2", "1920x1080", "2:1", "99.99", "31.12", "ё", "Ё", "й", "й", "ё", "+а", "з+амок"};

    @Test public void fuzzNeverThrowsAndStaysInShtormAlphabet() throws Exception {
        Random random = new Random(20261019);
        ShtormBpeFrontend shtorm = shtorm();
        for (int round = 0; round < 20000; round++) {
            StringBuilder b = new StringBuilder();
            int parts = 1 + random.nextInt(12);
            for (int p = 0; p < parts; p++) {
                switch (random.nextInt(5)) {
                    case 0: b.append(WEB[random.nextInt(WEB.length)]); break;
                    case 1: b.appendCodePoint(random.nextInt(0x3000)); break;
                    case 2: b.append(random.nextInt(2) == 0 ? ' ' : '.'); break;
                    case 3: for (int k = random.nextInt(8); k > 0; k--) b.append((char) ('0' + random.nextInt(10))); break;
                    default: for (int k = random.nextInt(6); k > 0; k--) b.append("абвгдеёжзийклмнопрстуфхцчшщъыьэюяABCxyz".charAt(random.nextInt(39)));
                }
                if (random.nextInt(3) == 0) b.append(' ');
            }
            String input = b.toString();
            RussianNormalizer.Result r = RussianNormalizer.normalize(input);
            for (int k = 0; k < r.text.length(); k++) {
                char c = r.text.charAt(k);
                assertTrue("char U+" + Integer.toHexString(c) + " from [" + input + "] -> [" + r.text + "]", RussianNormalizer.russian(c) || SHTORM_PUNCT.indexOf(c) >= 0);
                assertTrue(r.sourceStart(k) >= 0 && r.sourceStart(k) < r.sourceEnd(k) && r.sourceEnd(k) <= input.length());
            }
            SileroFrontend.pieces(input);
            if (shtorm != null) for (String chunk : PocketPrompt.boundedChunks(r.text)) assertTrue(shtorm.encodeSpeakable(PocketPrompt.prepare(chunk)).length <= 256);
        }
    }

    /** The genuine tokenizer when its untracked files are present (tools/models/shtorm/onnx), else null. */
    static ShtormBpeFrontend shtorm() throws Exception {
        File dir = new File("../tools/models/shtorm/onnx");
        if (!new File(dir, "vocab.json").isFile()) return null;
        return new ShtormBpeFrontend(new File(dir, "vocab.json"), new File(dir, "token_scores.json"), new File(dir, "normalizer.bin"));
    }
}
