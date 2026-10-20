package org.localvoice.tts;

/**
 * Paragraph handoff. Chromium/Edge Read Aloud send the next paragraph only after onDone, and Android fires onDone
 * only once all audio handed to the framework has played, so the next request's first-audio latency is an audible
 * gap. The service therefore withholds the last {@link #holdMs} of a request's audio, ends the request, and plays
 * that tail at the start of the next request while the next paragraph renders. A tail nobody continues (end of the
 * article) plays on the service's own AudioTrack; a tail the user stopped or seeked away from is dropped.
 */
final class TailHandoff {
    /** Preference key (SharedPreferences "voices"); request param of the same name ("false") disables it per request. */
    static final String PREFERENCE = "paragraph_handoff";
    static final int MIN_HOLD_MS = 400, MAX_HOLD_MS = 2000, MARGIN_MS = 300;
    /** After the handed audio should have finished: how long a continuation may take before the service plays the tail itself. */
    static final int GRACE_MS = 700;
    /** A request this much before the handed audio ended cannot be onDone-driven: it is a user seek. */
    static final int EARLY_MS = 300;
    /** A stop counts as the next speak()'s QUEUE_FLUSH only when that request arrives within this. */
    static final int FLUSH_MS = 250;
    /** Silent run that may host the audible track switch between requests: a clause or sentence pause, not a stop consonant. */
    static final int SPLIT_SILENCE_MS = 100;
    /** Audio held beyond the tail to find such a pause. */
    static final int SEARCH_MS = 2000;

    private TailHandoff() {}

    /** Audio to hold back: the model's learned first-audio latency plus margin (unknown: the minimum). */
    static int holdMs(double firstAudioMs) {
        return (int) Math.max(MIN_HOLD_MS, Math.min(MAX_HOLD_MS, firstAudioMs + MARGIN_MS));
    }

    /**
     * Where held PCM16 splits into the part played now and the tail, leaving at least {@code minTail} samples: the end
     * of the latest silent run of {@link #SPLIT_SILENCE_MS}, else of the longest silent run, so the track switch
     * lands in a pause; else the point leaving exactly {@code minTail}.
     */
    static int split(short[] pcm, int length, int rate, int minTail) {
        int latest = Math.max(0, length - minTail), need = Math.max(1, rate * SPLIT_SILENCE_MS / 1000);
        int best = latest, bestRun = 0, run = 0;
        short threshold = (short) (SpeechPlan.SILENCE * 32767);
        for (int i = latest - 1; i >= -1; i--) {
            if (i >= 0 && Math.abs(pcm[i]) < threshold) { run++; continue; }
            // A run ending at i+1+run (exclusive) just closed.
            if (run >= need) return i + 1 + run;
            if (run > bestRun) { bestRun = run; best = i + 1 + run; }
            run = 0;
        }
        return best;
    }

    /** One withheld tail: PCM16 at {@code rate}, the request that produced it, and when its handed audio ends. */
    static final class Tail {
        final short[] pcm; final int rate; final String text, model; final int uid; final long playEnd;
        final android.os.Bundle params;
        /** Uptime of the stop that ended the producing request, 0 while none. */
        volatile long stoppedAt;
        /** Set when the request that was playing this tail was stopped before its own words: only a resume of that text continues it. */
        String pausedText;
        Tail(short[] pcm, int rate, String text, String model, int uid, long playEnd, android.os.Bundle params) {
            this.pcm = pcm; this.rate = rate; this.text = text; this.model = model; this.uid = uid; this.playEnd = playEnd; this.params = params;
        }
        /** Whether a request for {@code next} at {@code now} continues reading after this tail. */
        boolean continuedBy(String next, int uid, long now) {
            String trimmed = next.strip();
            if (trimmed.isEmpty() || uid != this.uid) return false;
            if (pausedText != null) return pausedText.strip().endsWith(trimmed);
            // A user stop()/seek arrives while the handed audio still plays, or is a stop no request follows;
            // a re-speak of (part of) the same paragraph is a seek inside it. No stop at all: a queued request.
            return (stoppedAt == 0 || now - stoppedAt <= FLUSH_MS) && now >= playEnd - EARLY_MS && !text.strip().endsWith(trimmed);
        }
    }

    /** Linear resampling of a tail to the next request's rate (different voice). */
    static short[] resample(short[] pcm, int from, int to) {
        if (from == to || pcm.length == 0) return pcm;
        short[] out = new short[(int) ((long) pcm.length * to / from)];
        for (int i = 0; i < out.length; i++) {
            double at = (double) i * from / to;
            int a = (int) at, b = Math.min(pcm.length - 1, a + 1);
            out[i] = (short) Math.round(pcm[a] + (pcm[b] - pcm[a]) * (at - a));
        }
        return out;
    }

    /**
     * Plays a tail nobody continued on the service's own AudioTrack, with the request's audio attributes. Returns
     * the unplayed remainder when {@code stopped} fires first (a late continuation can still claim it), else null.
     * No audio focus request: the client owns focus, and taking it would pause the client.
     */
    static short[] play(Tail tail, java.util.function.BooleanSupplier stopped) {
        android.media.AudioAttributes attributes = null;
        if (tail.params != null) try { attributes = tail.params.getParcelable("audioAttributes"); } catch (RuntimeException ignored) {}
        if (attributes == null) attributes = new android.media.AudioAttributes.Builder()
            .setLegacyStreamType(tail.params == null ? android.media.AudioManager.STREAM_MUSIC : tail.params.getInt("streamType", android.media.AudioManager.STREAM_MUSIC)).build();
        android.media.AudioTrack track = new android.media.AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(tail.rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(Math.max(2, tail.pcm.length * 2)).build();
        try {
            track.write(tail.pcm, 0, tail.pcm.length);
            track.play();
            while (track.getPlaybackHeadPosition() < tail.pcm.length) {
                if (stopped.getAsBoolean()) {
                    int played = Math.min(tail.pcm.length, track.getPlaybackHeadPosition());
                    track.pause();
                    return java.util.Arrays.copyOfRange(tail.pcm, played, tail.pcm.length);
                }
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
            return null;
        } finally { track.release(); }
    }
}
