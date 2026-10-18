package org.localvoice.tts.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Run with javac/java -ea; no network or Android runtime required. */
public final class EdgeProtocolCheck {
    public static void main(String[] args) throws Exception {
        assert EdgeGec.value(0).equals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6");
        assert EdgeGec.value(1700000000).equals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2");
        assert EdgeGec.value(1792320000).equals("A099E9A2B70A1217-5F9470D2E9608CAA6DEBADF3D500E3B323B412CDFD363F6AFAAC6F1826973086");
        assert EdgeGec.value(1700000000).equals(EdgeGec.value(1700000099));
        assert EdgeProtocol.rate(0.5f).equals("-50%");
        assert EdgeProtocol.rate(1.5f).equals("+50%");
        assert EdgeProtocol.rate(Float.NaN).equals("+0%");
        assert EdgeProtocol.xml("<&>'\"\u0001").equals("&lt;&amp;&gt;&apos;&quot; ");
        String ssml = EdgeProtocol.ssml("Привет <world>", "ru-RU-SvetlanaNeural", 1.5f, "test");
        assert ssml.contains("ru-RU, SvetlanaNeural") && ssml.contains("&lt;world&gt;") && ssml.contains("rate='+50%'");
        String aria = EdgeProtocol.ssml("Voice switch", "en-US-AriaNeural", 1, "same-model");
        String guy = EdgeProtocol.ssml("Voice switch", "en-US-GuyNeural", 1, "same-model");
        assert aria.contains("en-US, AriaNeural") && !aria.contains("GuyNeural");
        assert guy.contains("en-US, GuyNeural") && !guy.contains("AriaNeural");
        try { EdgeProtocol.ssml("&".repeat(1000), "en-US-AriaNeural", 1, "test"); throw new AssertionError(); } catch (IOException expected) {}
        byte[] header = "Content-Type:audio/mpeg\r\nPath:audio\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[header.length+6];
        frame[0]=(byte)(header.length>>8); frame[1]=(byte)header.length;
        System.arraycopy(header,0,frame,2,header.length);
        System.arraycopy(new byte[]{1,2,3,4},0,frame,header.length+2,4);
        assert Arrays.equals(EdgeProtocol.audioPayload(frame),new byte[]{1,2,3,4});
        for (byte[] bad : new byte[][]{ {}, {0}, {1,0,7}, {0,1,1} }) {
            try { EdgeProtocol.audioPayload(bad); throw new AssertionError(); } catch (IOException expected) {}
        }
        assert EdgeVoices.BASELINE.size()==4;
        long now=java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        assert EdgeProtocol.retryAfter(null,now)==0;
        assert EdgeProtocol.retryAfter(" 60 ",now)==60000;
        assert EdgeProtocol.retryAfter("-1",now)==0;
        assert EdgeProtocol.retryAfter("0",now)==0;
        assert EdgeProtocol.retryAfter("Mon, 01 Jan 2024 00:02:00 GMT",now)==120000;
        assert EdgeProtocol.retryAfter("Sun, 31 Dec 2023 23:59:00 GMT",now)==0;
        assert EdgeProtocol.retryAfter("not a retry date",now)==0;
        assert EdgeProtocol.retryAfter("9223372036854775807",now)==0;
        System.out.println("Edge protocol/GEC/rate/XML/frame/catalog/Retry-After checks passed");
    }
}
