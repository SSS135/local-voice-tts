package org.localvoice.tts;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class CatalogDataTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** One local model row; each placeholder lets a test break exactly one field. */
    private static String row(String id, String backend, String url, long bytes, String sha) {
        return "{\"id\":\"" + id + "\",\"label\":\"L\",\"backend\":\"" + backend + "\",\"revision\":\"r1\",\"network\":false,\"available\":true,"
            + "\"languages\":[],\"license\":\"\",\"licenseUrl\":\"\",\"licenseText\":\"\",\"licenseAsset\":\"\",\"description\":\"\",\"config\":{},"
            + "\"voices\":[{\"name\":\"v\",\"label\":\"V\",\"language\":\"en-US\",\"speaker\":0}],"
            + "\"files\":[{\"path\":\"a.bin\",\"url\":\"" + url + "\",\"bytes\":" + bytes + ",\"sha256\":\"" + sha + "\"}]}";
    }

    private static String catalog(String... rows) {
        return "{\"models\":[" + String.join(",", rows) + "]}";
    }

    private static void rejected(String json, String model) {
        try {
            Catalog.parse(json);
            fail("accepted invalid catalog for " + model);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(model));
        }
    }

    @Test public void bundledCatalogMatchesPinnedData() {
        List<Catalog.Model> models = Catalog.MODELS;
        assertEquals(7, models.size());
        int files = 0;
        for (Catalog.Model model : models) files += model.files.size();
        assertEquals(421, files);
        Catalog.Model kokoro = Catalog.findModel("kokoro-en");
        assertEquals(28, kokoro.voices.size());
        assertEquals("kokoro-en:af_heart", kokoro.voices.get(0).id);
        assertEquals(3, kokoro.voices.get(0).speakerId);
        assertEquals("Kokoro v1.0 · English", kokoro.label);
        assertEquals(172731932L, kokoro.bytes());
        assertEquals(20, Catalog.findModel("supertonic-3").voices.size());
        assertEquals("ru-RU", Catalog.findVoice("supertonic-3:ru-RU-9").languageTag);
        Catalog.Model edge = Catalog.findModel("edge");
        assertTrue(edge.network);
        assertTrue(edge.files.isEmpty());
        assertFalse(Catalog.findModel("shtorm-pocket-ru-fast").config.containsKey("importArchiveBytes"));
        assertEquals("sherpa-silero", Catalog.findModel("silero-ru").backend);
        assertEquals(4, Catalog.findVoice("silero-ru:xenia").speakerId);
        Catalog.Model neutts = Catalog.findModel("neutts-nano-en");
        assertEquals("llama-neutts", neutts.backend);
        assertEquals(6, neutts.voices.size());
        assertEquals("en-GB", Catalog.findVoice("neutts-nano-en:dave").languageTag);
        assertEquals(410290022L, neutts.bytes());
    }

    @Test public void malformedRowsAreRejected() {
        assertEquals(1, Catalog.parse(catalog(row("ok", "edge", "https://x/a", 1, SHA))).size());
        rejected(catalog(row("bad-url", "edge", "http://x/a", 1, SHA)), "bad-url");
        rejected(catalog(row("bad-size", "edge", "https://x/a", 0, SHA)), "bad-size");
        rejected(catalog(row("bad-sha", "edge", "https://x/a", 1, "ABC")), "bad-sha");
        rejected(catalog(row("bad-backend", "nope", "https://x/a", 1, SHA)), "bad-backend");
        rejected(catalog(row("Bad_Id", "edge", "https://x/a", 1, SHA)), "Bad_Id");
        rejected(catalog(row("dup", "edge", "https://x/a", 1, SHA), row("dup", "edge", "https://x/a", 1, SHA)), "dup");
    }
}
