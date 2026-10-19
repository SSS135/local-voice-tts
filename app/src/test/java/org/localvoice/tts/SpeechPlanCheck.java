package org.localvoice.tts;

import java.util.List;
import org.junit.Test;

/** Segmenting, word ranges in original-text offsets, marker estimation and silence shaping. */
public final class SpeechPlanCheck {
    private static final String TEXT = "  The old lighthouse stood at the edge of the cliff, its white paint peeling after decades of salt and wind. "
        + "Every evening the keeper climbed the stairs. He lit the lamp!\n\nShips watched for its beam.";

    @Test public void segments() {
        List<SpeechPlan.Segment> segments = SpeechPlan.segments(TEXT, true);
        SpeechPlan.Segment first = segments.get(0);
        check(first.start == 2, "leading whitespace must stay an offset, not be removed from the text");
        check(TEXT.substring(first.start, first.end).equals("The old lighthouse stood at the edge of the cliff,"), "first segment cut at a clause: " + segments);
        check(first.pauseMs == SpeechPlan.CLAUSE_PAUSE_MS, "clause pause");
        check(TEXT.substring(segments.get(1).start, segments.get(1).end).equals("its white paint peeling after decades of salt and wind."),
            "second segment limit grows only 1.5x: " + segments);
        check(segments.get(1).pauseMs == SpeechPlan.SENTENCE_PAUSE_MS, "sentence pause");
        check(TEXT.substring(segments.get(2).start, segments.get(2).end).equals("Every evening the keeper climbed the stairs. He lit the lamp!"),
            "later sentences pack up to the limit: " + segments);
        check(segments.get(2).pauseMs == SpeechPlan.PARAGRAPH_PAUSE_MS, "paragraph pause");
        check(TEXT.substring(segments.get(3).start, segments.get(3).end).equals("Ships watched for its beam."), "paragraph starts a segment");
        for (int i = 0; i < segments.size(); i++) check(segments.get(i).end - segments.get(i).start <= SpeechPlan.limit(i), "segment over limit");
        String shortRest = "Nobody in the village remembered a night when the light had failed.";
        check(SpeechPlan.segments(shortRest, true).size() == 1, "no cut that leaves a tiny remainder");

        List<SpeechPlan.Segment> whole = SpeechPlan.segments(TEXT, false);
        check(TEXT.substring(whole.get(0).start, whole.get(0).end).startsWith("The old") && TEXT.charAt(whole.get(0).end - 1) == '.', "backend chunking keeps sentences whole");

        String run = "word ".repeat(100).trim();
        int previous = 0;
        List<SpeechPlan.Segment> pieces = SpeechPlan.segments(run, true);
        for (int i = 0; i < pieces.size(); i++) {
            SpeechPlan.Segment s = pieces.get(i);
            check(s.start >= previous && s.end - s.start <= SpeechPlan.limit(i), "unpunctuated text bounded");
            check(!run.substring(s.start, s.end).startsWith(" ") && run.substring(s.start, s.end).endsWith("word"), "cut at spaces");
            previous = s.end;
        }
        check(previous == run.length(), "all text covered");
        check(SpeechPlan.segments("3.14 and v1.2 stay whole.", true).size() == 1, "decimal points are not sentence ends");
        check(SpeechPlan.segments(" \n ", true).isEmpty(), "blank text has no segments");
    }

    @Test public void words() {
        String text = "\"Hello,\" she said — (it's 42%)!";
        List<SpeechPlan.Word> words = SpeechPlan.words(text, 0, text.length());
        String[] expected = {"Hello", "she", "said", "it's", "42"};
        check(words.size() == expected.length, "word count");
        for (int i = 0; i < expected.length; i++) check(text.substring(words.get(i).start, words.get(i).end).equals(expected[i]), "word " + i);
    }

    @Test public void markers() {
        SpeechPlan.Segment second = new SpeechPlan.Segment(TEXT.indexOf("its white"), TEXT.indexOf("lamp!") + 5, SpeechPlan.PARAGRAPH_PAUSE_MS);
        List<SpeechPlan.Marker> markers = SpeechPlan.estimate(TEXT, second, 480, 240000);
        check(markers.get(0).frame == 480, "first word starts at the voiced start");
        check(TEXT.substring(markers.get(0).start, markers.get(0).end).equals("its"), "offsets index the original text");
        long last = -1;
        for (SpeechPlan.Marker m : markers) { check(m.frame > last && m.frame < 480 + 240000, "monotonic inside the voiced span"); last = m.frame; }
        SpeechPlan.Marker every = markers.stream().filter(m -> TEXT.startsWith("Every", m.start)).findFirst().get();
        SpeechPlan.Marker wind = markers.stream().filter(m -> TEXT.startsWith("wind", m.start)).findFirst().get();
        check(every.frame - wind.frame > 240000 * 4 / 110, "sentence pause inside a segment delays the next word");
    }

    @Test public void shape() {
        int rate = 24000;
        float[] pcm = new float[rate];
        for (int i = 4800; i < 14400; i++) pcm[i] = 0.5f;
        int[] first = SpeechPlan.shape(pcm, rate, true, false, SpeechPlan.SENTENCE_PAUSE_MS);
        check(first[0] == 4800 - SpeechPlan.ms(SpeechPlan.LEAD_MS, rate), "utterance lead capped");
        check(first[0] + first[1] == 14400 + SpeechPlan.ms(SpeechPlan.SENTENCE_PAUSE_MS, rate) && first[2] == 0, "long trail cut to the pause");
        float[] tight = new float[1000];
        java.util.Arrays.fill(tight, 0.5f);
        int[] padded = SpeechPlan.shape(tight, rate, false, false, SpeechPlan.SENTENCE_PAUSE_MS);
        check(padded[0] == 0 && padded[1] == 1000 && padded[2] == SpeechPlan.ms(SpeechPlan.SENTENCE_PAUSE_MS, rate), "missing pause padded");
        int[] last = SpeechPlan.shape(pcm, rate, false, true, SpeechPlan.SENTENCE_PAUSE_MS);
        check(last[0] + last[1] == 14400 + SpeechPlan.ms(SpeechPlan.TAIL_MS, rate) && last[2] == 0, "utterance tail capped, never padded");
        check(SpeechPlan.shape(new float[100], rate, true, true, 0)[1] == 0, "silent segment dropped");
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
