package org.localvoice.tts;

import org.junit.Test;

/** Paragraph handoff: tail sizing, the cut point, and which next request continues a tail. */
public final class TailHandoffCheck {
    @Test public void holdAndSplit() {
        check(TailHandoff.holdMs(0) == TailHandoff.MIN_HOLD_MS && TailHandoff.holdMs(5000) == TailHandoff.MAX_HOLD_MS, "hold bounds");
        check(TailHandoff.holdMs(900) == 900 + TailHandoff.MARGIN_MS, "hold follows first-audio latency");
        int rate = 1000;
        short[] pcm = new short[3000];
        java.util.Arrays.fill(pcm, (short) 8000);
        for (int i = 1200; i < 1300; i++) pcm[i] = 0;   // a sentence pause
        for (int i = 700; i < 720; i++) pcm[i] = 0;     // a stop consonant: only a fallback
        for (int i = 2700; i < 2720; i++) pcm[i] = 0;   // inside the tail
        check(TailHandoff.split(pcm, pcm.length, rate, 1000) == 1300, "cut at the end of the latest pause leaving the tail: " + TailHandoff.split(pcm, pcm.length, rate, 1000));
        check(TailHandoff.split(pcm, pcm.length, rate, 1850) == 720, "no pause early enough: the longest short gap");
        check(TailHandoff.split(pcm, pcm.length, rate, 2500) == 500, "no gap at all: cut leaves exactly the tail");
    }

    @Test public void continuation() {
        String paragraph = "The gate creaked open. She stepped inside.";
        TailHandoff.Tail tail = new TailHandoff.Tail(new short[10], 24000, paragraph, "edge", 7, 10_000, null);
        check(tail.continuedBy("Nobody greeted her.", 7, 10_050), "no stop: a request queued behind this one (QUEUE_ADD) continues");
        tail.stoppedAt = 10_040;
        check(tail.continuedBy("Nobody greeted her.", 7, 10_050), "next paragraph right after onDone continues");
        check(!tail.continuedBy("Nobody greeted her.", 8, 10_050), "another app never gets the tail");
        check(!tail.continuedBy("She stepped inside.", 7, 10_050), "re-speaking the end of the same paragraph is a seek");
        check(!tail.continuedBy("Nobody greeted her.", 7, 10_040 + TailHandoff.FLUSH_MS + 1), "stop without an immediate request is a user stop");
        tail.stoppedAt = 9_000;
        check(!tail.continuedBy("Nobody greeted her.", 7, 9_010), "request long before the handed audio ended is a tap-to-seek");
        tail.pausedText = "Nobody greeted her. The lanterns were dark.";
        check(tail.continuedBy("Nobody greeted her. The lanterns were dark.", 7, 60_000) && tail.continuedBy("The lanterns were dark.", 7, 60_000),
            "a resume of the paused paragraph replays the tail, whenever it comes");
        check(!tail.continuedBy("Somewhere above, a shutter banged.", 7, 60_000), "resume elsewhere drops it");
    }

    @Test public void resample() {
        short[] pcm = {0, 100, 200, 300};
        check(TailHandoff.resample(pcm, 24000, 24000) == pcm, "same rate untouched");
        short[] up = TailHandoff.resample(pcm, 1, 2);
        check(up.length == 8 && up[1] == 50 && up[2] == 100, "linear interpolation");
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
