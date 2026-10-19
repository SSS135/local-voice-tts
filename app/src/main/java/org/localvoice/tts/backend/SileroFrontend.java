package org.localvoice.tts.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Silero v5_6_ru plain-text frontend (PartTTSModelMultiAcc_v3.apply_tts without SSML/emphasis), split into
 * sentence pieces that each become one sherpa-onnx VITS call. Upstream drops digits and Latin; this app first
 * runs {@link RussianNormalizer}.
 */
final class SileroFrontend {
    /** TYPE2ID order: st, wh_q, general_q, alternative_q, tag_q, exclam. */
    static final String[] TYPES = {"st", "wh_q", "general_q", "alternative_q", "tag_q", "exclam"};
    /** Characters the model accepts after lower-casing (symbols[3:], '^' emphasis marker excluded). */
    static final String ALPHABET = "!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… ";
    /** Upper bound per synthesis call: keeps decoder frames far below the 5000-row positional table at speed 0.5. */
    static final int MAX_PIECE = 200;
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern WORD = Pattern.compile("[а-яёa-z]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TAG = Pattern.compile(
        "(?:[,\\s]+(правда|верно|да)\\s*\\?$)|(?:[,\\s]+(не\\s+так\\s+ли|не\\s+правда\\s+ли|разве\\s+не\\s+так|ведь\\s+так)\\s*\\?$)|(?:[,\\s]+ведь\\s*\\?$)|(?:[,\\s]+а\\s*\\?$)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OR = Pattern.compile("(?<![\\p{L}\\p{N}_])или(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final java.util.Set<String> WH = set("кто кого кому кем ком что чего чему чем чём чё чо где куда откуда когда почему зачем как почём отчего насколько "
        + "сколько скольких скольким сколькими какой какая какое какие какого каких какому каким какими каком какую чей чья чьё чье чьи чьего чьей чьих "
        + "чьему чьим чьими чьём чьем чью который которая которое которые которого которой которых которому которым которыми котором которую каков какова каково каковы");
    private static final java.util.Set<String> FILLERS = set("а ну и так вот слышь слушай скажите скажи пожалуйста вобще вообще типа короче");

    static final class Piece {
        final String text;
        final int type;
        Piece(String text, int type) { this.text = text; this.type = type; }
        @Override public String toString() { return TYPES[type] + ":" + text; }
    }

    private SileroFrontend() {}

    /** Prepared, typed, length-bounded pieces in reading order; stress is not yet applied. */
    static List<Piece> pieces(String text) {
        String prepared = prepare(text);
        List<Piece> out = new ArrayList<>();
        for (String sentence : SENTENCE_SPLIT.split(prepared)) {
            if (!hasText(sentence)) continue;
            int type = classify(sentence);
            for (String piece : bound(sentence, MAX_PIECE)) if (hasText(piece)) out.add(new Piece(piece, type));
        }
        return out;
    }

    /** prepare_tts_model_input + prepare_text_input for plain text, after app-owned {@link RussianNormalizer}. */
    static String prepare(String text) {
        text = text.trim();
        StringBuilder lines = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') lines.append(i > 0 && ",;:.!?".indexOf(text.charAt(i - 1)) >= 0 ? " " : ". ");
            else lines.append(c);
        }
        text = RussianNormalizer.apply(lines.toString()).toLowerCase(Locale.ROOT)
            .replace('—', '–').replace('‑', '-');
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (ALPHABET.indexOf(c) >= 0) kept.append(c);
            else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) kept.append(' ');
        }
        return kept.toString().replaceAll(" +", " ").trim();
    }

    /** The upstream has_text check: some Cyrillic (а-я), hyphen or space content besides spaces. */
    static boolean hasText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'а' && c <= 'я') || c == '-') return true;
        }
        return false;
    }

    /** Split an over-long sentence at the last pause mark, else space, inside the bound. */
    static List<String> bound(String sentence, int limit) {
        List<String> out = new ArrayList<>();
        String rest = sentence;
        while (rest.length() > limit) {
            int cut = -1;
            for (int i = limit - 1; i > limit / 2 && cut < 0; i--) if (",;:–…".indexOf(rest.charAt(i)) >= 0) cut = i + 1;
            for (int i = limit - 1; i > limit / 3 && cut < 0; i--) if (rest.charAt(i) == ' ') cut = i;
            if (cut < 0) cut = limit;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** classify_sentence on the prepared (lower-case, quote-free) sentence; returns a TYPE2ID index. */
    static int classify(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        String tail = s;
        if (tail.endsWith("?!") || tail.endsWith("?..") || tail.endsWith("?...")) {
            int end = tail.length();
            while (end > 0 && ".!".indexOf(tail.charAt(end - 1)) >= 0) end--;
            tail = tail.substring(0, end);
        }
        if (tail.endsWith("?")) {
            String q = tail.replace("+", "").replace('ё', 'е').replace('Ё', 'Е').replaceAll("\\s+", " ").trim();
            if (TAG.matcher(q).find()) return 4;
            if (whPrefix(q)) return 1;
            if (OR.matcher(q).find()) return 3;
            return 2;
        }
        return s.endsWith("!") ? 5 : 0;
    }

    private static boolean whPrefix(String text) {
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        int words = 0, content = 0;
        while (m.find() && words++ < 8) {
            String w = m.group();
            if (FILLERS.contains(w)) continue;
            if (WH.contains(w)) return true;
            if (++content >= 4) break;
        }
        return false;
    }

    /** sherpa character-frontend text: sentence punctuation as private-use aliases, explicit eos. */
    static String encode(String accented) {
        StringBuilder out = new StringBuilder(accented.length() + 1);
        for (int i = 0; i < accented.length(); i++) {
            char c = accented.charAt(i);
            int alias = ".:?!".indexOf(c);
            out.append(alias >= 0 ? (char) (0xE000 + alias) : c);
        }
        return out.append('~').toString();
    }

    private static java.util.Set<String> set(String words) {
        return new java.util.HashSet<>(java.util.Arrays.asList(words.split(" ")));
    }
}
