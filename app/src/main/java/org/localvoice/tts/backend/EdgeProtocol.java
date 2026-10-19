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
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
    }

    static String ssml(String text, String voice, float speed, String id) throws IOException {
        if (!voice.matches("[a-z]{2,3}-[A-Z]{2}-[A-Za-z0-9-]+")) throw new IOException("Invalid Edge voice");
        String escaped = xml(text);
        if (escaped.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IOException("Edge speech chunk too long");
        int dash = voice.indexOf('-', voice.indexOf('-') + 1);
        String locale = voice.substring(0, dash);
        String name = "Microsoft Server Speech Text to Speech Voice (" + locale + ", " + voice.substring(dash + 1) + ")";
        return ssml(text,voice,name,locale,speed,id);
    }
    static String ssml(String text,String voice,String name,String locale,float speed,String id) throws IOException {
        if(!locale.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){1,3}") || !voice.startsWith(locale+"-")
                || !voice.substring(locale.length()+1).matches("[A-Za-z0-9-]+")
                || !name.equals("Microsoft Server Speech Text to Speech Voice ("+locale+", "+voice.substring(locale.length()+1)+")"))
            throw new EdgeRetry.FatalException("Invalid Edge voice metadata");
        String escaped=xml(text);
        if(escaped.getBytes(StandardCharsets.UTF_8).length>4096) throw new IOException("Edge speech chunk too long");
        return "X-RequestId:" + id + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + timestamp() + "Z\r\nPath:ssml\r\n\r\n"
                + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + locale + "'>"
                + "<voice name='" + xml(name) + "'><prosody pitch='+0Hz' rate='" + rate(speed) + "' volume='+0%'>"
                + escaped + "</prosody></voice></speak>";
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
