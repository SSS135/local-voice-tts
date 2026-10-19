package org.localvoice.tts.backend;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    @Override public void cancel() {
        cancelled = true;
        WebSocket socket = activeSocket;
        if (socket != null) socket.cancel();
    }
    @Override public void close() { cancel(); voice = null; }

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
        int start = 0;
        long emitted = 0;
        // Bound individual server turns after XML escaping, including non-ASCII/surrogate pairs.
        while (start < text.length() && !stopped(cancellation)) {
            int end = start, bytes = 0, boundary = -1;
            while (end < text.length()) {
                int next = end + Character.charCount(text.codePointAt(end));
                int size = EdgeProtocol.xml(text.substring(end, next)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > 3000) break;
                bytes += size; end = next;
                if (Character.isWhitespace(text.codePointBefore(end)) || ".!?。！？".indexOf(text.codePointBefore(end)) >= 0) boundary = end;
            }
            if (end < text.length() && boundary > start) end = boundary;
            String chunk = text.substring(start, end);
            java.util.List<EdgeProtocol.Boundary> boundaries = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Options request = options;
            EdgeAudio.Pcm pcm = EdgeRetry.run(() -> {
                boundaries.clear();
                byte[] mp3 = request(chunk, speed, request.pitch, request.networkTimeoutMs, cancellation, boundaries);
                if (mp3 == null || stopped(cancellation)) throw new EdgeRetry.CancelledException();
                EdgeAudio.validateMp3(mp3);
                return decode(mp3, cancellation);
            }, pending, network, observer, request.networkTimeoutMs, request.networkRetries);
            for (long[] word : EdgeProtocol.placeWords(chunk, boundaries, sampleRate()))
                if (word[0] < pcm.samples()) sink.word(emitted + word[0], start + (int) word[1], start + (int) word[2]);
            if (!pcm.commit(pending, sink)) return;
            emitted += pcm.samples();
            start = end;
        }
    }

    private byte[] request(String text, float speed, float pitch, long budgetMillis, Cancellation cancellation, java.util.List<EdgeProtocol.Boundary> boundaries) throws Exception {
        String ssml = voiceName==null ? EdgeProtocol.ssml(text, voice, speed, pitch, id())
                : EdgeProtocol.ssml(text,voice,voiceName,voiceLocale,speed,pitch,id());
        Request request = headers(endpoint == null ? url("/edge/v1").addQueryParameter("ConnectionId", id()).build().toString() : endpoint)
                .header("Pragma", "no-cache").header("Cache-Control", "no-cache").header("Origin", EdgeProtocol.ORIGIN).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean opened = new AtomicBoolean();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IOException[] error = new IOException[1];
        WebSocket socket = CLIENT.newWebSocket(request, new WebSocketListener() {
            private void fail(WebSocket ws, String message) {
                fail(ws, message, null);
            }
            private void fail(WebSocket ws, String message, Throwable cause) {
                if (terminal.compareAndSet(false, true)) {
                    error[0] = cause instanceof EdgeRetry.FatalException || cause instanceof EdgeRetry.TransientException
                            ? (IOException) cause : new IOException(message, cause);
                    ws.cancel(); done.countDown();
                }
            }
            @Override public void onOpen(WebSocket ws, Response response) {
                opened.set(true);
                if (stopped(cancellation)) { fail(ws, "Edge request cancelled"); return; }
                if (!ws.send(EdgeProtocol.config()) || !ws.send(ssml)) fail(ws, "Edge request send failed");
            }
            @Override public void onMessage(WebSocket ws, String message) {
                if (terminal.get() || stopped(cancellation)) return;
                String path = EdgeProtocol.path(message.split("\r\n\r\n", 2)[0]);
                if (path.equals("turn.end")) {
                    if (terminal.compareAndSet(false, true)) {
                        if (bytes.size() == 0) error[0] = new IOException("Edge returned no audio");
                        ws.close(1000, "complete"); done.countDown();
                    }
                } else if (path.equals("audio.metadata")) {
                    String[] parts = message.split("\r\n\r\n", 2);
                    if (parts.length == 2) boundaries.addAll(EdgeProtocol.wordBoundaries(parts[1]));
                } else if (!path.equals("turn.start") && !path.equals("response")) {
                    fail(ws, "Unexpected Edge response");
                }
            }
            @Override public void onMessage(WebSocket ws, ByteString message) {
                if (terminal.get() || stopped(cancellation)) return;
                try {
                    if (message.size() > EdgeProtocol.MAX_AUDIO_BYTES + 8194) throw new IOException("Edge audio frame too large");
                    byte[] data = EdgeProtocol.audioPayload(message.toByteArray());
                    if (bytes.size() + data.length > EdgeProtocol.MAX_AUDIO_BYTES) throw new IOException("Edge audio exceeds limit");
                    bytes.write(data);
                } catch (IOException e) { fail(ws, e.getMessage()); }
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                String reason = response == null ? "Edge connection failed" : "Edge HTTP " + response.code();
                int code = response == null ? 0 : response.code();
                long retryAfter = response == null ? 0 : EdgeProtocol.retryAfter(response.header("Retry-After"), System.currentTimeMillis());
                if (response != null) response.close();
                if (code>=300 && code<500 && code!=408 && code!=409 && code!=425 && code!=429) {
                    fail(ws, reason, new EdgeRetry.FatalException(reason)); return;
                }
                if (retryAfter > 0) {
                    fail(ws, reason, new EdgeRetry.TransientException(reason, retryAfter, t)); return;
                }
                // Transport exceptions contain connection diagnostics, never the outgoing SSML body.
                fail(ws, reason + " phase=" + (opened.get() ? "audio" : "handshake")
                        + " audioBytes=" + bytes.size() + " cause=" + t.getClass().getSimpleName(), t);
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                if (!terminal.get()) {
                    String message = "Edge closed before completing audio code=" + code;
                    if (code==1002 || code==1007 || code==1008) fail(ws,message,new EdgeRetry.FatalException(message));
                    else fail(ws,message);
                } else ws.close(code, reason);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if (!terminal.get()) fail(ws, "Edge closed before completing audio code=" + code);
            }
        });
        activeSocket = socket;
        try {
            long deadline = SystemClock.elapsedRealtime() + (budgetMillis > 0 ? Math.min(attemptTimeoutMillis, budgetMillis) : attemptTimeoutMillis);
            while (!done.await(25, TimeUnit.MILLISECONDS)) {
                if (stopped(cancellation)) return null;
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Edge request timed out");
            }
            if (stopped(cancellation)) return null;
            if (error[0] != null) throw error[0];
            return bytes.toByteArray();
        } finally {
            terminal.set(true); socket.cancel(); activeSocket = null;
        }
    }

    private EdgeAudio.Pcm decode(byte[] mp3, Cancellation cancellation) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(new MediaDataSource() {
                @Override public int readAt(long position, byte[] buffer, int offset, int size) {
                    if (position < 0 || position >= mp3.length) return -1;
                    int n = Math.min(size, mp3.length - (int) position);
                    System.arraycopy(mp3, (int) position, buffer, offset, n); return n;
                }
                @Override public long getSize() { return mp3.length; }
                @Override public void close() {}
            });
            if (extractor.getTrackCount() == 0) throw new IOException("Edge returned invalid MP3");
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!"audio/mpeg".equals(mime)) throw new EdgeRetry.FatalException("Unsupported Edge codec");
            if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 24000 || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                throw new EdgeRetry.FatalException("Unsupported Edge audio format");
            extractor.selectTrack(0);
            codec = MediaCodec.createDecoderByType(mime);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0); codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            EdgeAudio.Pcm pcm = new EdgeAudio.Pcm();
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (!stopped(cancellation)) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Edge audio decode timed out");
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), 0); extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 24000 || output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                        throw new EdgeRetry.FatalException("Unsupported decoded Edge format");
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                        throw new EdgeRetry.FatalException("Unsupported decoded Edge PCM");
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && !stopped(cancellation)) {
                            ByteBuffer output = codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                            output.position(info.offset); output.limit(info.offset + info.size);
                            int width = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                            if (info.size % width != 0) throw new IOException("Invalid decoded Edge PCM");
                            if(info.size/width>EdgeAudio.MAX_PCM_SAMPLES-pcm.samples()) throw new IOException("Edge decoded audio exceeds limit");
                            float[] block = new float[info.size / width];
                            for (int i=0;i<block.length;i++) block[i] = width == 4 ? output.getFloat() : output.getShort()/32768f;
                            pcm.add(block);
                        }
                    } finally { codec.releaseOutputBuffer(index, false); }
                    if (stopped(cancellation)) throw new EdgeRetry.CancelledException();
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return pcm.complete();
                    }
                }
            }
            throw new EdgeRetry.CancelledException();
        } catch (RuntimeException malformedAudio) {
            throw new IOException("Edge audio decode failed", malformedAudio);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) {} codec.release(); }
            extractor.release();
        }
    }
}
