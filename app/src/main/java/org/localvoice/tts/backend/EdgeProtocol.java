package org.localvoice.tts.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/** Adapted from VN Reader Next 3.4.4 / edge-tts 7.3.1; see THIRD_PARTY_EDGE_NOTICES.txt. */
final class EdgeProtocol {
    static final String CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    static final String BASE = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud";
    static final String VERSION = "2-143.0.3650.75";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";
    static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;

    static String rate(float speed) {
        int percent = Math.round((Math.max(0.5f, Math.min(2f, Float.isFinite(speed) ? speed : 1f)) - 1f) * 100);
        return (percent >= 0 ? "+" : "") + percent + "%";
    }
    /**
     * Pitch factor as relative SSML Hz, the prosody unit this endpoint is known to accept. 150 Hz approximates a
     * mid speaking voice, so 2.0 lands near an octave up for lower voices and a fifth for higher ones.
     */
    static String pitch(float pitch) {
        int hz = Math.round((Math.max(0.5f, Math.min(2f, Float.isFinite(pitch) ? pitch : 1f)) - 1f) * 150);
        return (hz >= 0 ? "+" : "") + hz + "Hz";
    }
    enum Outcome { DECLINED, TRANSIENT, AUTH, FATAL }
    /**
     * Class of a failed server turn. {@code http}: handshake HTTP status (0 = none); {@code close}: WebSocket close
     * code before turn.end (0 = none); {@code emptyTurn}: turn.end arrived with no audio.
     * Sources: edge-tts 7.3.1 communicate.py raises NoAudioReceived (no retry) when a turn yields no audio and retries
     * only HTTP 403, once, after DRM.handle_client_response_error resyncs the Sec-MS-GEC clock from the Date header;
     * RFC 6455 7.4.1 close codes; Microsoft Q&amp;A 2151150: Azure TTS closes with 1007 on SSML it cannot parse.
     * SSML is sent only after the upgrade, so any handshake 4xx is configuration, never content.
     */
    static Outcome classify(int http, int close, boolean emptyTurn) {
        if (emptyTurn || close == 1007 || close == 1008) return Outcome.DECLINED;
        if (http == 401 || http == 403) return Outcome.AUTH;
        if (http >= 300 && http < 500 && http != 408 && http != 409 && http != 425 && http != 429) return Outcome.FATAL;
        if (close == 1002) return Outcome.FATAL;
        return Outcome.TRANSIENT;
    }
    static long retryAfter(String value, long nowMillis) {
        if (value == null) return 0;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? 0 : Math.multiplyExact(seconds, 1000L);
        } catch (NumberFormatException | ArithmeticException invalidSeconds) {
            try {
                return Math.max(0, ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis);
            } catch (RuntimeException invalidDate) { return 0; }
        }
    }

    static String xml(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '\'': out.append("&apos;"); break;
                case '"': out.append("&quot;"); break;
                default: out.append((c < 32 && c != 9 && c != 10 && c != 13) ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                .withZone(ZoneOffset.UTC).format(Instant.now());
    }

    static String config() {
        return "X-Timestamp:" + timestamp() + "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
    }

    static String ssml(String text, String voice, float speed, String id) throws IOException { return ssml(text,voice,speed,1f,id); }
    static String ssml(String text, String voice, float speed, float pitch, String id) throws IOException {
        if (!voice.matches("[a-z]{2,3}-[A-Z]{2}-[A-Za-z0-9-]+")) throw new IOException("Invalid Edge voice");
        String escaped = xml(text);
        if (escaped.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IOException("Edge speech chunk too long");
        int dash = voice.indexOf('-', voice.indexOf('-') + 1);
        String locale = voice.substring(0, dash);
        String name = "Microsoft Server Speech Text to Speech Voice (" + locale + ", " + voice.substring(dash + 1) + ")";
        return ssml(text,voice,name,locale,speed,pitch,id);
    }
    static String ssml(String text,String voice,String name,String locale,float speed,String id) throws IOException { return ssml(text,voice,name,locale,speed,1f,id); }
    static String ssml(String text,String voice,String name,String locale,float speed,float pitch,String id) throws IOException {
        if(!locale.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){1,3}") || !voice.startsWith(locale+"-")
                || !voice.substring(locale.length()+1).matches("[A-Za-z0-9-]+")
                || !name.equals("Microsoft Server Speech Text to Speech Voice ("+locale+", "+voice.substring(locale.length()+1)+")"))
            throw new EdgeRetry.FatalException("Invalid Edge voice metadata");
        String escaped=xml(text);
        if(escaped.getBytes(StandardCharsets.UTF_8).length>4096) throw new IOException("Edge speech chunk too long");
        return "X-RequestId:" + id + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + timestamp() + "Z\r\nPath:ssml\r\n\r\n"
                + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + locale + "'>"
                + "<voice name='" + xml(name) + "'><prosody pitch='" + pitch(pitch) + "' rate='" + rate(speed) + "' volume='+0%'>"
                + escaped + "</prosody></voice></speak>";
    }

    /** Spoken word and its audio offset (100 ns ticks from the turn's audio start) from an audio.metadata body. */
    static final class Boundary {
        final long ticks; final String text;
        Boundary(long ticks,String text){this.ticks=ticks;this.text=text;}
    }
    static java.util.List<Boundary> wordBoundaries(String body) {
        java.util.List<Boundary> out=new java.util.ArrayList<>();
        try {
            org.json.JSONArray metadata=new org.json.JSONObject(body).getJSONArray("Metadata");
            for(int i=0;i<metadata.length();i++) {
                org.json.JSONObject item=metadata.getJSONObject(i);
                if(!"WordBoundary".equals(item.optString("Type")))continue;
                org.json.JSONObject data=item.getJSONObject("Data");
                long ticks=data.getLong("Offset");
                String text=data.getJSONObject("text").getString("Text");
                if(ticks>=0&&!text.isEmpty())out.add(new Boundary(ticks,text));
            }
        } catch(org.json.JSONException malformed) {
            // Highlighting metadata is optional; malformed entries never fail the audio turn.
        }
        return out;
    }
    /**
     * Places boundaries in the turn text in reading order: {sample offset, start, end} per word found. Microsoft
     * reports unescaped spoken words; one missing from the remaining text (normalized by the service) is skipped.
     */
    static java.util.List<long[]> placeWords(String text,java.util.List<Boundary> boundaries,int sampleRate) {
        java.util.List<long[]> out=new java.util.ArrayList<>();
        int cursor=0;
        for(Boundary boundary:boundaries) {
            int at=text.indexOf(boundary.text,cursor);
            if(at<0)continue;
            cursor=at+boundary.text.length();
            out.add(new long[]{boundary.ticks*sampleRate/10_000_000L,at,cursor});
        }
        return out;
    }

    static String path(String headers) {
        for (String line : headers.split("\r?\n")) if (line.startsWith("Path:")) return line.substring(5).trim();
        return "";
    }

    static byte[] audioPayload(byte[] frame) throws IOException {
        if (frame.length < 2) throw new IOException("Missing Edge audio header");
        int length = ((frame[0] & 255) << 8) | (frame[1] & 255);
        if (length == 0 || length > 8192 || 2 + length > frame.length) throw new IOException("Invalid Edge audio header length");
        String headers = new String(frame, 2, length, StandardCharsets.UTF_8);
        if (!path(headers).equals("audio")) throw new IOException("Unexpected Edge binary path");
        String type = null;
        for (String line : headers.split("\r?\n")) if (line.startsWith("Content-Type:")) type = line.substring(13).trim();
        byte[] body = Arrays.copyOfRange(frame, 2 + length, frame.length);
        if (!"audio/mpeg".equals(type) && !(type == null && body.length == 0)) throw new IOException("Unexpected Edge audio type");
        return body;
    }
}
