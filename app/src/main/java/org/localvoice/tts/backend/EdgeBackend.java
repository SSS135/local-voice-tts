package org.localvoice.tts.backend;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Explicit online backend; recoverable failures retain the uncommitted turn until stop/recovery. */
public final class EdgeBackend implements SpeechBackend {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build();
    /** Shared by all instances so the per-voice decline streak survives requests and adapter reloads. */
    static final EdgeRetry.DeclineGuard DECLINES = new EdgeRetry.DeclineGuard();
    private volatile WebSocket activeSocket;
    private volatile boolean cancelled;
    private String voice;
    private String voiceName,voiceLocale;
    private Host host;
    private final String endpoint;
    private final long attemptTimeoutMillis;
    public EdgeBackend() { this(null, 45000); }
    /** Constructor injection for the instrumentation fixture; production factory uses the default. */
    public EdgeBackend(String endpoint, long attemptTimeoutMillis) {
        if (attemptTimeoutMillis < 100 || attemptTimeoutMillis > 45000) throw new IllegalArgumentException("Invalid Edge attempt timeout");
        if (endpoint != null && !endpoint.matches("https?://(127\\.0\\.0\\.1|localhost)(:[0-9]{1,5})?(/[^?#]*)?"))
            throw new IllegalArgumentException("Edge fixture endpoint must be loopback");
        this.endpoint = endpoint; this.attemptTimeoutMillis = attemptTimeoutMillis;
    }
    @Override public void setHost(Host host) { this.host = host; }
    private volatile Options options = Options.DEFAULT;
    /** Pitch goes into SSML prosody; network timeout/retry counts bound the retry loop of each server turn. */
    @Override public void options(Options options) { this.options = options == null ? Options.DEFAULT : options; }

    @Override public void load(File directory, Map<String,String> config) throws IOException {
        voice = config.get("voice");
        voiceName=config.get("voiceName");voiceLocale=config.get("locale");
        if (voice == null || !voice.matches("[a-z]{2,3}(?:-[A-Za-z0-9]+){2,6}") || voice.length()>128)
            throw new EdgeRetry.FatalException("An explicit Edge voice is required");
        if((voiceName==null)!=(voiceLocale==null)) throw new EdgeRetry.FatalException("Incomplete Edge voice metadata");
        if(voiceLocale!=null && (!voiceLocale.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){1,3}") || !voice.startsWith(voiceLocale+"-")
                || !voiceName.equals("Microsoft Server Speech Text to Speech Voice ("+voiceLocale+", "+voice.substring(voiceLocale.length()+1)+")")))
            throw new EdgeRetry.FatalException("Invalid Edge voice metadata");
    }
    @Override public int sampleRate() { return 24000; }
    @Override public boolean streams() { return true; }
    @Override public boolean timesWords() { return true; }
    @Override public void cancel() {
        cancelled = true;
        WebSocket socket = activeSocket;
        if (socket != null) socket.cancel();
    }
    @Override public void close() {
        cancel(); voice = null;
        Relay idle; synchronized (this) { idle = spare; spare = null; }
        if (idle != null) idle.socket.cancel();
    }
    /**
     * Connection opened ahead for the next turn: saves its TLS/WebSocket handshake (0.75-0.9 s on a Pixel 7 Pro).
     * Opened only once a turn of an explicitly requested online voice is playing; it carries no text until the next turn.
     */
    private Relay spare;
    private static final long SPARE_MAX_AGE_MS = 30_000;
    private Request connection() {
        return headers(endpoint == null ? url("/edge/v1").addQueryParameter("ConnectionId", id()).build().toString() : endpoint)
                .header("Pragma", "no-cache").header("Cache-Control", "no-cache").header("Origin", EdgeProtocol.ORIGIN).build();
    }
    private void prewarm() {
        synchronized (this) { if (spare != null || voice == null) return; }
        Relay relay = new Relay();
        relay.socket = CLIENT.newWebSocket(connection(), relay);
        synchronized (this) { if (spare == null) { spare = relay; return; } }
        relay.socket.cancel();
    }
    /** Forwards a socket's events to the turn that uses it; until then it only records whether the socket opened or died. */
    private static final class Relay extends WebSocketListener {
        volatile WebSocket socket;
        private final long created = SystemClock.elapsedRealtime();
        private WebSocketListener target;
        private boolean open, dead;
        synchronized boolean usable() { return !dead && SystemClock.elapsedRealtime() - created < SPARE_MAX_AGE_MS; }
        void attach(WebSocketListener turn) {
            boolean opened;
            synchronized (this) { target = turn; opened = open; }
            if (opened) turn.onOpen(socket, null);
        }
        private synchronized WebSocketListener target() { return target; }
        @Override public void onOpen(WebSocket ws, Response response) {
            WebSocketListener turn;
            synchronized (this) { open = true; turn = target; }
            if (turn != null) turn.onOpen(ws, response);
        }
        @Override public void onMessage(WebSocket ws, String message) { WebSocketListener turn = target(); if (turn != null) turn.onMessage(ws, message); }
        @Override public void onMessage(WebSocket ws, ByteString message) { WebSocketListener turn = target(); if (turn != null) turn.onMessage(ws, message); }
        @Override public void onClosing(WebSocket ws, int code, String reason) {
            WebSocketListener turn;
            synchronized (this) { dead = true; turn = target; }
            if (turn != null) turn.onClosing(ws, code, reason); else ws.close(1000, null);
        }
        @Override public void onClosed(WebSocket ws, int code, String reason) {
            WebSocketListener turn;
            synchronized (this) { dead = true; turn = target; }
            if (turn != null) turn.onClosed(ws, code, reason);
        }
        @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
            WebSocketListener turn;
            synchronized (this) { dead = true; turn = target; }
            if (turn != null) turn.onFailure(ws, t, response); else if (response != null) response.close();
        }
    }

    private boolean stopped(Cancellation cancellation) { return cancelled || cancellation.isCancelled(); }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    static Request.Builder headers(String url) {
        return new Request.Builder().url(url).header("User-Agent", EdgeProtocol.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9").header("Cookie", "muid=" + id().toUpperCase(java.util.Locale.ROOT) + ";");
    }
    static HttpUrl.Builder url(String suffix) {
        return HttpUrl.get(EdgeProtocol.BASE + suffix).newBuilder()
                .addQueryParameter("TrustedClientToken", EdgeProtocol.CLIENT_TOKEN)
                .addQueryParameter("Sec-MS-GEC", EdgeGec.value())
                .addQueryParameter("Sec-MS-GEC-Version", EdgeProtocol.VERSION);
    }

    @Override public void synthesize(String text, String languageTag, int speakerId, float speed,
                                     Cancellation cancellation, PcmSink sink) throws Exception {
        if (voice == null) throw new EdgeRetry.FatalException("Edge voice is not loaded");
        cancelled = false;
        Cancellation pending = () -> stopped(cancellation);
        Host currentHost = host;
        EdgeRetry.Network network = currentHost == null ? EdgeRetry.DEFAULT_NETWORK : new EdgeRetry.Network() {
            @Override public boolean isAvailable() { return currentHost.isNetworkAvailable(); }
            @Override public void await(long millis, Cancellation cancellation) throws InterruptedException {
                currentHost.awaitNetworkOrDelay(millis, cancellation);
            }
        };
        EdgeRetry.Observer observer = currentHost == null ? EdgeRetry.NO_OBSERVER
                : (waiting, attempts) -> currentHost.reportStatus(waiting ? (currentHost.isNetworkAvailable() ? "retrying" : "waiting_network") : "ready", attempts);
        // A turn that fails after its audio started playing resumes at the word being spoken (start[0]), not at the turn start.
        int[] start = {0};
        long[] emitted = {0};
        // Bound individual server turns after XML escaping, including non-ASCII/surrogate pairs.
        while (start[0] < text.length() && !stopped(cancellation)) {
            int end = start[0], bytes = 0, boundary = -1;
            while (end < text.length()) {
                int next = end + Character.charCount(text.codePointAt(end));
                int size = EdgeProtocol.xml(text.substring(end, next)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > 3000) break;
                bytes += size; end = next;
                if (Character.isWhitespace(text.codePointBefore(end)) || ".!?。！？".indexOf(text.codePointBefore(end)) >= 0) boundary = end;
            }
            if (end < text.length() && boundary > start[0]) end = boundary;
            int turnEnd = end;
            Options request = options;
            String voiceId = voice;
            boolean accepted;
            try {
                accepted = EdgeRetry.run(() -> {
                    try {
                        return turn(text, start, turnEnd, speed, request, cancellation, sink, emitted);
                    } catch (EdgeRetry.DeclinedException declined) {
                        if (DECLINES.skip(voiceId)) throw declined;
                        throw new EdgeRetry.OutageException(declined);
                    }
                }, pending, network, observer, request.networkTimeoutMs, request.networkRetries, request.boundOffline);
            } catch (EdgeRetry.DeclinedException declined) {
                // Never fail on content: this fragment stays silent and reading continues. The message has no text.
                android.util.Log.w("LocalVoice", "Edge declined fragment voice=" + voice + " " + declined.getMessage());
                start[0] = turnEnd;
                continue;
            }
            DECLINES.audio(voiceId);
            if (!accepted) return;
            start[0] = turnEnd;
        }
    }

    /**
     * One server turn for text[start[0], end), streamed: MP3 frames are decoded as they arrive and each decoded
     * buffer leaves at once, preceded by the WordBoundary markers it covers, so first audio does not wait for the
     * turn to finish. A failure after audio left moves start[0] to the word then being spoken; the retry resumes
     * there. Returns false when the sink stopped accepting audio.
     */
    private Boolean turn(String text, int[] start, int end, float speed, Options request, Cancellation cancellation,
                         PcmSink sink, long[] emitted) throws Exception {
        int from = start[0];
        String chunk = text.substring(from, end);
        String ssml = voiceName==null ? EdgeProtocol.ssml(chunk, voice, speed, request.pitch, id())
                : EdgeProtocol.ssml(chunk,voice,voiceName,voiceLocale,speed,request.pitch,id());
        long began = SystemClock.elapsedRealtime(), base = emitted[0], samples = 0;
        Turn turn = new Turn(ssml, cancellation);
        Relay relay;
        synchronized (this) { relay = spare; spare = null; }
        boolean warm = relay != null && relay.usable();
        if (!warm) {
            if (relay != null) relay.socket.cancel();
            relay = new Relay();
            relay.socket = CLIENT.newWebSocket(connection(), relay);
        }
        WebSocket socket = relay.socket;
        relay.attach(turn);
        activeSocket = socket;
        MediaCodec codec = null;
        int spoken = -1;
        try {
            codec = MediaCodec.createDecoderByType("audio/mpeg");
            MediaFormat format = MediaFormat.createAudioFormat("audio/mpeg", 24000, 1);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0); codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            java.util.ArrayDeque<long[]> words = new java.util.ArrayDeque<>();
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int[] placed = {0, 0};
            long frames = 0, firstAudio = 0;
            byte[] frame = null;
            boolean inputDone = false;
            // Inactivity bound, not a whole-turn bound: a paragraph turn streams for as long as its audio keeps coming
            // (a slow link or decoder), and fails only after this long without a new frame or decoded block.
            long idleLimit = request.networkTimeoutMs > 0 ? Math.min(attemptTimeoutMillis, request.networkTimeoutMs) : attemptTimeoutMillis;
            long progress = began;
            while (true) {
                if (stopped(cancellation)) throw new EdgeRetry.CancelledException();
                if (SystemClock.elapsedRealtime() - progress > idleLimit) throw new IOException("Edge request timed out");
                turn.check();
                if (!inputDone) {
                    if (frame == null) frame = turn.frame();
                    if (frame != null || turn.ended()) {
                        int index = codec.dequeueInputBuffer(0);
                        if (index >= 0 && frame != null) {
                            codec.getInputBuffer(index).put(frame);
                            // MPEG-2 layer III at 24 kHz: 576 samples (24 ms) per frame.
                            codec.queueInputBuffer(index, 0, frame.length, frames++ * 24000L, 0); frame = null;
                            progress = SystemClock.elapsedRealtime();
                        } else if (index >= 0) {
                            codec.queueInputBuffer(index, 0, 0, frames * 24000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true;
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, frame == null && !turn.ended() ? 10000 : 1000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 24000 || output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                        throw new EdgeRetry.FatalException("Unsupported decoded Edge format");
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                        throw new EdgeRetry.FatalException("Unsupported decoded Edge PCM");
                } else if (index >= 0) {
                    float[] block = null;
                    try {
                        if (info.size > 0) {
                            ByteBuffer output = codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                            output.position(info.offset); output.limit(info.offset + info.size);
                            int width = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                            if (info.size % width != 0) throw new IOException("Invalid decoded Edge PCM");
                            if (info.size / width > EdgeAudio.MAX_PCM_SAMPLES - samples) throw new IOException("Edge decoded audio exceeds limit");
                            block = new float[info.size / width];
                            for (int i = 0; i < block.length; i++) block[i] = width == 4 ? output.getFloat() : output.getShort() / 32768f;
                            for (float value : block) if (!Float.isFinite(value)) throw new IOException("Invalid Edge decoded PCM");
                        }
                    } finally { codec.releaseOutputBuffer(index, false); }
                    if (block != null) {
                        place(turn, chunk, placed, words);
                        for (long[] word; (word = words.peek()) != null && word[0] < samples + block.length; words.poll()) {
                            sink.word(base + word[0], from + (int) word[1], from + (int) word[2]);
                            spoken = (int) word[1];
                        }
                        if (firstAudio == 0) { firstAudio = SystemClock.elapsedRealtime(); prewarm(); }
                        if (stopped(cancellation) || !sink.write(block)) return Boolean.FALSE;
                        samples += block.length; emitted[0] += block.length; progress = SystemClock.elapsedRealtime();
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            if (samples == 0) throw new IOException("Edge decoded no audio");
            place(turn, chunk, placed, words);
            for (long[] word : words) sink.word(base + word[0], from + (int) word[1], from + (int) word[2]);
            android.util.Log.i("LocalVoiceTiming", "edge turn chars=" + chunk.length() + " warm=" + warm + " openMs=" + (turn.openedAt - began)
                    + " firstAudioMs=" + (firstAudio - began) + " totalMs=" + (SystemClock.elapsedRealtime() - began) + " audioMs=" + samples * 1000 / 24000);
            return Boolean.TRUE;
        } catch (IOException failure) {
            if (samples > 0) start[0] = from + Math.max(0, spoken);
            throw failure;
        } catch (RuntimeException malformedAudio) {
            if (samples > 0) start[0] = from + Math.max(0, spoken);
            throw new IOException("Edge audio decode failed", malformedAudio);
        } finally {
            turn.finish(); socket.cancel(); activeSocket = null;
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) {} codec.release(); }
        }
    }

    /** Places newly received boundaries in the turn text, in reading order: {sample, start, end}; placed = {count, cursor}. */
    private void place(Turn turn, String chunk, int[] placed, java.util.ArrayDeque<long[]> words) {
        for (EdgeProtocol.Boundary word; (word = turn.boundary(placed[0])) != null; placed[0]++) {
            int at = chunk.indexOf(word.text, placed[1]);
            // A word missing from the remaining text (normalized by the service) is skipped.
            if (at < 0) continue;
            placed[1] = at + word.text.length();
            words.add(new long[]{word.ticks * sampleRate() / 10_000_000L, at, placed[1]});
        }
    }

    /** Listener side of one server turn: MP3 payload split into whole frames, word boundaries, end or failure. */
    private final class Turn extends WebSocketListener {
        private final String ssml;
        private final Cancellation cancellation;
        private final java.util.List<EdgeProtocol.Boundary> boundaries = new java.util.ArrayList<>();
        private byte[] data = new byte[16384];
        private int head, tail, audioBytes;
        private boolean opened, ended, terminal, tagChecked;
        private IOException error;
        volatile long openedAt;
        Turn(String ssml, Cancellation cancellation) { this.ssml = ssml; this.cancellation = cancellation; }

        synchronized void check() throws IOException { if (error != null) throw error; }
        synchronized boolean ended() { return ended; }
        synchronized EdgeProtocol.Boundary boundary(int index) { return index < boundaries.size() ? boundaries.get(index) : null; }
        /** Next complete MPEG frame, or null until more bytes arrive; an incomplete remainder at turn end is dropped. */
        synchronized byte[] frame() throws IOException {
            if (!tagChecked) {
                int tag = EdgeAudio.id3Length(data, head, tail - head);
                if (tag < 0 || tag > tail - head) return null;
                head += tag; tagChecked = true;
            }
            int size = EdgeAudio.frameLength(data, head, tail - head);
            if (size == 0) return null;
            head += size;
            return java.util.Arrays.copyOfRange(data, head - size, head);
        }
        synchronized void finish() { terminal = true; }
        private synchronized void fail(WebSocket ws, IOException failure) {
            if (terminal) return;
            terminal = true; error = failure; ws.cancel();
        }
        private synchronized void append(byte[] bytes) {
            if (tail + bytes.length > data.length) {
                System.arraycopy(data, head, data, 0, tail - head); tail -= head; head = 0;
                if (tail + bytes.length > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, tail + bytes.length));
            }
            System.arraycopy(bytes, 0, data, tail, bytes.length); tail += bytes.length;
        }
        @Override public void onOpen(WebSocket ws, Response response) {
            openedAt = SystemClock.elapsedRealtime();
            synchronized (this) { opened = true; }
            if (stopped(cancellation)) { fail(ws, new IOException("Edge request cancelled")); return; }
            if (!ws.send(EdgeProtocol.config()) || !ws.send(ssml)) fail(ws, new IOException("Edge request send failed"));
        }
        @Override public void onMessage(WebSocket ws, String message) {
            if (stopped(cancellation)) return;
            String path = EdgeProtocol.path(message.split("\r\n\r\n", 2)[0]);
            synchronized (this) {
                if (terminal) return;
                if (path.equals("turn.end")) {
                    terminal = true; ended = true;
                    if (audioBytes == 0) error = failure(EdgeProtocol.classify(0, 0, true), "Edge turn ended without audio", 0, null);
                    ws.close(1000, "complete");
                } else if (path.equals("audio.metadata")) {
                    String[] parts = message.split("\r\n\r\n", 2);
                    if (parts.length == 2) boundaries.addAll(EdgeProtocol.wordBoundaries(parts[1]));
                } else if (!path.equals("turn.start") && !path.equals("response")) {
                    fail(ws, new IOException("Unexpected Edge response"));
                }
            }
        }
        @Override public void onMessage(WebSocket ws, ByteString message) {
            if (stopped(cancellation)) return;
            try {
                if (message.size() > EdgeProtocol.MAX_AUDIO_BYTES + 8194) throw new IOException("Edge audio frame too large");
                byte[] payload = EdgeProtocol.audioPayload(message.toByteArray());
                synchronized (this) {
                    if (terminal) return;
                    if (audioBytes + payload.length > EdgeProtocol.MAX_AUDIO_BYTES) throw new IOException("Edge audio exceeds limit");
                    audioBytes += payload.length; append(payload);
                }
            } catch (IOException e) { fail(ws, e); }
        }
        @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
            String reason = response == null ? "Edge connection failed" : "Edge HTTP " + response.code();
            int code = response == null ? 0 : response.code();
            long now = System.currentTimeMillis();
            long retryAfter = response == null ? 0 : EdgeProtocol.retryAfter(response.header("Retry-After"), now);
            EdgeProtocol.Outcome outcome = EdgeProtocol.classify(code, 0, false);
            if (outcome == EdgeProtocol.Outcome.AUTH) EdgeGec.syncClock(response.header("Date"), now);
            if (response != null) response.close();
            // Transport exceptions contain connection diagnostics, never the outgoing SSML body.
            fail(ws, failure(outcome, reason + " phase=" + (opened() ? "audio" : "handshake")
                    + " audioBytes=" + audioBytes() + " cause=" + t.getClass().getSimpleName(), retryAfter, t));
        }
        @Override public void onClosing(WebSocket ws, int code, String reason) { closed(ws, code); ws.close(code, reason); }
        @Override public void onClosed(WebSocket ws, int code, String reason) { closed(ws, code); }
        private synchronized boolean opened() { return opened; }
        private synchronized int audioBytes() { return audioBytes; }
        private void closed(WebSocket ws, int code) {
            fail(ws, failure(EdgeProtocol.classify(0, code, false), "Edge closed before completing audio code=" + code
                    + " phase=" + (opened() ? "audio" : "handshake") + " audioBytes=" + audioBytes(), 0, null));
        }
    }

    private static IOException failure(EdgeProtocol.Outcome outcome, String message, long retryAfter, Throwable cause) {
        switch (outcome) {
            case DECLINED: return new EdgeRetry.DeclinedException(message);
            case AUTH: return new EdgeRetry.AuthException(message);
            case FATAL: return new EdgeRetry.FatalException(message, cause);
            default: return retryAfter > 0 ? new EdgeRetry.TransientException(message, retryAfter, cause) : new IOException(message, cause);
        }
    }

}
