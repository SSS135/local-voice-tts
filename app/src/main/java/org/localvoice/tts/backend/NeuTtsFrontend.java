package org.localvoice.tts.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeuTTS phoneme prompt, ported from neutts ac69851f ({@code NeuTTS._ggml_prompt}, {@code BasePhonemizer}) and
 * phonemizer 3.4.0 ({@code EspeakBackend(preserve_punctuation=True, with_stress=True,
 * language_switch="remove-flags")}, {@code Punctuation.preserve/restore}). eSpeak NG itself is native.
 * Deviations from upstream: text chunks are cut at the punctuation match position instead of the first textual
 * occurrence of the mark (upstream drops words after a decimal like "4.5"), and every restored line is kept
 * instead of only the first.
 */
final class NeuTtsFrontend {
    static final String PROMPT_START = "user: Convert the text to speech:<|TEXT_PROMPT_START|>";
    static final String PROMPT_END = "<|TEXT_PROMPT_END|>\nassistant:<|SPEECH_GENERATION_START|>";
    // Python str.isspace() (re's Unicode \s): Android regex lacks UNICODE_CHARACTER_CLASS and host Java's \s is ASCII.
    private static final String WS = "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]";
    // phonemizer default marks ;:,.!?¡¿—…"«»“”(){}[] ; ',' and '.' between two digits are not punctuation.
    private static final Pattern MARKS = Pattern.compile(
        "(" + WS + "*(?:[;:!?¡¿—…\"«»“”(){}\\[\\]]|(?<![0-9])[,.]|[,.](?![0-9]))+" + WS + "*)+");
    private static final Pattern FLAGS = Pattern.compile("\\(.+?\\)");
    private static final Pattern SPACES = Pattern.compile(WS + "+");
    private static final Pattern EDGES = Pattern.compile("^" + WS + "+|" + WS + "+$");

    private NeuTtsFrontend() {}

    /** Full prompt; the generated continuation is speech tokens until {@code <|SPEECH_GENERATION_END|>}. */
    static String prompt(String referencePhones, String phones, int[] referenceCodes) {
        StringBuilder out = new StringBuilder(PROMPT_START.length() + referencePhones.length() + phones.length()
            + PROMPT_END.length() + referenceCodes.length * 14);
        out.append(PROMPT_START).append(referencePhones).append(' ').append(phones).append(PROMPT_END);
        for (int code : referenceCodes) out.append("<|speech_").append(code).append("|>");
        return out.toString();
    }

    /** NeuTTS {@code _to_phones}: phonemized text with whitespace runs collapsed to one space. */
    static String phones(String text, UnaryOperator<String> espeak) {
        List<String> chunks = new ArrayList<>();
        List<String[]> marks = new ArrayList<>();  // {mark, position B|I|E|A}
        preserve(text, chunks, marks);
        List<String> phonemized = new ArrayList<>(chunks.size());
        for (String chunk : chunks) phonemized.add(postprocess(espeak.apply(chunk)));
        String joined = strip(String.join(" ", restore(phonemized, marks)));
        return joined.isEmpty() ? "" : SPACES.matcher(joined).replaceAll(" ");
    }

    private static void preserve(String line, List<String> chunks, List<String[]> marks) {
        List<MatchResult> matches = new ArrayList<>();
        Matcher m = MARKS.matcher(line);
        while (m.find()) matches.add(new MatchResult(m.start(), m.end(), m.group()));
        if (matches.isEmpty()) { if (!line.isEmpty()) chunks.add(line); return; }
        if (matches.size() == 1 && matches.get(0).text.equals(line)) { marks.add(new String[]{line, "A"}); return; }
        int from = 0;
        for (int i = 0; i < matches.size(); i++) {
            MatchResult match = matches.get(i);
            String position = "I";
            if (i == 0 && line.startsWith(match.text)) position = "B";
            else if (i == matches.size() - 1 && line.endsWith(match.text)) position = "E";
            marks.add(new String[]{match.text, position});
            if (match.start > from) chunks.add(line.substring(from, match.start));
            from = match.end;
        }
        if (from < line.length()) chunks.add(line.substring(from));
    }

    /** EspeakBackend._postprocess_line with strip=False, tie=None, phone separator "", word separator " ". */
    static String postprocess(String raw) {
        String line = strip(raw).replace("\n", " ").replace("  ", " ");
        line = line.replaceAll("_+", "_").replace("_ ", " ");
        line = FLAGS.matcher(line).replaceAll("");
        if (line.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String word : line.split(" ", -1)) out.append(strip(word).replace("_", "")).append(' ');
        return out.toString();
    }

    /** Punctuation.restore with word separator " " and strip=False. */
    private static List<String> restore(List<String> phonemized, List<String[]> marks) {
        List<String> text = new ArrayList<>(phonemized), out = new ArrayList<>();
        int pos = 0, mark = 0;
        while (!text.isEmpty() || mark < marks.size()) {
            if (mark == marks.size()) {
                for (String line : text) out.add(line.endsWith(" ") ? line : line + " ");
                text.clear();
            } else if (text.isEmpty()) {
                StringBuilder rest = new StringBuilder();
                while (mark < marks.size()) rest.append(marks.get(mark++)[0]);
                out.add(rest.toString());
            } else if (pos == 0) {  // a single input line: every mark has line index 0
                String value = marks.get(mark)[0], position = marks.get(mark++)[1];
                if (text.get(0).endsWith(" ")) text.set(0, text.get(0).substring(0, text.get(0).length() - 1));
                String gap = value.endsWith(" ") ? "" : " ";
                switch (position) {
                    case "B": text.set(0, value + text.get(0)); break;
                    case "E": out.add(text.remove(0) + value + gap); pos++; break;
                    case "A": out.add(value + gap); pos++; break;
                    default:
                        if (text.size() == 1) text.set(0, text.get(0) + value);
                        else { String first = text.remove(0); text.set(0, first + value + text.get(0)); }
                }
            } else {
                out.add(text.remove(0));
                pos++;
            }
        }
        return out;
    }

    /** Python str.strip(). */
    private static String strip(String text) { return EDGES.matcher(text).replaceAll(""); }

    private static final class MatchResult {
        final int start, end; final String text;
        MatchResult(int start, int end, String text) { this.start = start; this.end = end; this.text = text; }
    }
}
