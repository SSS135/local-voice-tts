package org.localvoice.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure utterance planning: synthesis segments, word ranges for {@code SynthesisCallback.rangeStart}, and
 * silence shaping. All offsets index the original request text so markers survive trimming and chunking.
 */
final class SpeechPlan {
    /** First segment budget: short enough for fast first audio, cut at a clause where possible. */
    static final int FIRST_LIMIT = 60;
    /**
     * Later segments pack whole sentences, growing 1.5x per segment up to this length: each must render within the
     * audio already queued, and the cap bounds the uninterruptible native work a Stop may wait for.
     */
    static final int LIMIT = 140;
    /** A first-sentence cut must leave at least this much text, or the sentence is rendered whole. */
    static final int MIN_REST = 24;
    /** Pause kept or padded after a segment, by how that segment ends. */
    static final int SENTENCE_PAUSE_MS = 300, PARAGRAPH_PAUSE_MS = 450, CLAUSE_PAUSE_MS = 150, WORD_PAUSE_MS = 40;
    /** Silence kept before the first sound and after the last sound of an utterance. */
    static final int LEAD_MS = 20, TAIL_MS = 100;
    /** About -40 dBFS: model silence is far below, speech onsets/decays above. */
    static final float SILENCE = 0.01f;

    private static final String SENTENCE_END = ".!?…。！？", CLAUSE_END = ",;:–—", CLOSERS = "\"')]}»”’";

    private SpeechPlan() {}

    static final class Segment {
        final int start, end, pauseMs;
        Segment(int start, int end, int pauseMs) { this.start = start; this.end = end; this.pauseMs = pauseMs; }
        @Override public String toString() { return start + ".." + end + "/" + pauseMs; }
    }

    static final class Word {
        final int start, end;
        Word(int start, int end) { this.start = start; this.end = end; }
    }

    /** One rangeStart call: frame offset into the utterance audio and the original-text word range. */
    static final class Marker {
        final long frame; final int start, end;
        Marker(long frame, int start, int end) { this.frame = frame; this.start = start; this.end = end; }
        @Override public String toString() { return frame + ":" + start + "-" + end; }
    }

    /**
     * Splits text into synthesis segments. {@code splitSentences=false} (adapter-owned preprocessing) keeps every
     * sentence whole; otherwise the first sentence is cut at a clause for fast first audio and overlong sentences
     * are cut at clauses, then spaces.
     */
    static List<Segment> segments(String text, boolean splitSentences) {
        List<int[]> sentences = sentences(text);
        List<Segment> out = new ArrayList<>();
        int s = 0;
        while (s < sentences.size()) {
            int start = sentences.get(s)[0], end = sentences.get(s)[1];
            int limit = limit(out.size());
            if (splitSentences && end - start > limit + MIN_REST) {
                int cut = cut(text, start, end, limit, out.isEmpty());
                out.add(new Segment(start, trimEnd(text, start, cut), pause(text, start, cut)));
                sentences.get(s)[0] = skipSpace(text, cut, end);
                continue;
            }
            // Pack following whole sentences (never on the first segment: it must be fast).
            while (!out.isEmpty() && s + 1 < sentences.size() && sentences.get(s + 1)[1] - start <= limit
                    && !paragraph(text, end, sentences.get(s + 1)[0])) {
                end = sentences.get(++s)[1];
            }
            s++;
            out.add(new Segment(start, end, s < sentences.size() && paragraph(text, end, sentences.get(s)[0]) ? PARAGRAPH_PAUSE_MS : pause(text, start, end)));
        }
        return out;
    }

    /** Sentence ranges with surrounding whitespace removed; a line break always ends a sentence. */
    static List<int[]> sentences(String text) {
        List<int[]> out = new ArrayList<>();
        int at = skipSpace(text, 0, text.length());
        while (at < text.length()) {
            int end = at;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '\n' || c == '\r') break;
                end++;
                if (SENTENCE_END.indexOf(c) >= 0) {
                    while (end < text.length() && (SENTENCE_END.indexOf(text.charAt(end)) >= 0 || CLOSERS.indexOf(text.charAt(end)) >= 0)) end++;
                    if (end == text.length() || space(text.charAt(end))) break;
                }
            }
            int trimmed = trimEnd(text, at, end);
            if (trimmed > at) out.add(new int[]{at, trimmed});
            at = skipSpace(text, end, text.length());
        }
        return out;
    }

    static int limit(int index) { return (int) Math.min(LIMIT, FIRST_LIMIT * Math.pow(1.5, index)); }

    private static int cut(String text, int start, int end, int limit, boolean first) {
        int max = Math.min(end - MIN_REST, start + limit), min = start + (first ? 12 : limit / 3);
        // Prefer a clause boundary followed by a space, then any space; never split a surrogate pair.
        for (int i = max - 1; i > min; i--)
            if (CLAUSE_END.indexOf(text.charAt(i - 1)) >= 0 && space(text.charAt(i))) return i;
        for (int i = max - 1; i > min; i--) if (space(text.charAt(i))) return i;
        return Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
    }

    private static int pause(String text, int start, int end) {
        int core = end;
        while (core > start && CLOSERS.indexOf(text.charAt(core - 1)) >= 0) core--;
        char last = core > start ? text.charAt(core - 1) : ' ';
        if (SENTENCE_END.indexOf(last) >= 0) return SENTENCE_PAUSE_MS;
        if (CLAUSE_END.indexOf(last) >= 0) return CLAUSE_PAUSE_MS;
        return WORD_PAUSE_MS;
    }

    private static boolean paragraph(String text, int from, int to) {
        for (int i = from; i < to; i++) if (text.charAt(i) == '\n' || text.charAt(i) == '\r') return true;
        return false;
    }

    /** Speakable words of text[start,end): runs of non-space with leading/trailing punctuation removed. */
    static List<Word> words(String text, int start, int end) {
        List<Word> out = new ArrayList<>();
        int at = start;
        while (at < end) {
            while (at < end && space(text.charAt(at))) at++;
            int tokenEnd = at;
            while (tokenEnd < end && !space(text.charAt(tokenEnd))) tokenEnd++;
            int a = at, b = tokenEnd;
            while (a < b && !Character.isLetterOrDigit(text.codePointAt(a))) a += Character.charCount(text.codePointAt(a));
            while (b > a && !Character.isLetterOrDigit(text.codePointBefore(b))) b -= Character.charCount(text.codePointBefore(b));
            if (b > a) out.add(new Word(a, b));
            at = tokenEnd;
        }
        return out;
    }

    /**
     * Estimated word onsets: distributes the segment's voiced span over its words by a spoken-length weight
     * (letters, digits read as longer, punctuation pauses). Frames are relative to the segment's first frame.
     */
    static List<Marker> estimate(String text, Segment segment, long voicedStart, long voicedFrames) {
        List<Word> words = words(text, segment.start, segment.end);
        List<Marker> out = new ArrayList<>(words.size());
        if (words.isEmpty()) return out;
        double[] at = new double[words.size()];
        double total = 0;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            at[i] = total;
            total += weight(text, w.start, w.end);
            if (i + 1 < words.size()) total += pauseWeight(text, w.end, words.get(i + 1).start);
        }
        for (int i = 0; i < words.size(); i++)
            out.add(new Marker(voicedStart + Math.round(voicedFrames * at[i] / total), words.get(i).start, words.get(i).end));
        return out;
    }

    /** Spoken-length weight of a whole segment, the unit {@link #estimate} distributes frames over. */
    static double weight(String text, Segment segment) {
        List<Word> words = words(text, segment.start, segment.end);
        double total = 0;
        for (int i = 0; i < words.size(); i++) {
            total += weight(text, words.get(i).start, words.get(i).end);
            if (i + 1 < words.size()) total += pauseWeight(text, words.get(i).end, words.get(i + 1).start);
        }
        return total;
    }

    private static double weight(String text, int start, int end) {
        double weight = 1.5;
        for (int i = start; i < end; ) {
            int c = text.codePointAt(i); i += Character.charCount(c);
            weight += Character.isDigit(c) ? 2.5 : Character.isLetter(c) ? 1 : 0.3;
        }
        return weight;
    }

    private static double pauseWeight(String text, int from, int to) {
        double weight = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (SENTENCE_END.indexOf(c) >= 0 || c == '\n') weight = Math.max(weight, 6);
            else if (CLAUSE_END.indexOf(c) >= 0 || c == '(' || c == ')') weight = Math.max(weight, 3);
        }
        return weight;
    }

    /** Count of leading samples whose magnitude stays below {@link #SILENCE}. */
    static int leadingSilence(float[] pcm) {
        int i = 0;
        while (i < pcm.length && Math.abs(pcm[i]) < SILENCE) i++;
        return i;
    }

    static int trailingSilence(float[] pcm) {
        int i = pcm.length;
        while (i > 0 && Math.abs(pcm[i - 1]) < SILENCE) i--;
        return pcm.length - i;
    }

    /**
     * Shapes one segment's PCM: leading silence capped at {@link #LEAD_MS} for the utterance's first segment,
     * trailing silence set to exactly the segment pause (padded or cut) inside the utterance and capped at
     * {@link #TAIL_MS} at its end. Returns {start offset into pcm, kept length, appended zero samples}.
     */
    static int[] shape(float[] pcm, int sampleRate, boolean first, boolean last, int pauseMs) {
        int lead = leadingSilence(pcm);
        if (lead == pcm.length) return new int[]{0, 0, 0};
        int trail = trailingSilence(pcm);
        int keepLead = first ? Math.min(lead, ms(LEAD_MS, sampleRate)) : Math.min(lead, ms(WORD_PAUSE_MS, sampleRate));
        int wantTrail = ms(last ? TAIL_MS : pauseMs, sampleRate);
        int keepTrail = Math.min(trail, wantTrail);
        int from = lead - keepLead, length = pcm.length - from - (trail - keepTrail);
        return new int[]{from, length, last ? 0 : wantTrail - keepTrail};
    }

    static int ms(int millis, int sampleRate) { return (int) ((long) millis * sampleRate / 1000); }

    private static int skipSpace(String text, int from, int to) { while (from < to && space(text.charAt(from))) from++; return from; }
    private static int trimEnd(String text, int start, int end) { while (end > start && space(text.charAt(end - 1))) end--; return end; }
    private static boolean space(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85; }
}
