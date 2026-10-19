package org.localvoice.tts.backend;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Silero text frontend without weights; stress parity against the Python package when SILERO_FRONTEND_DIR is set. */
public final class SileroFrontendTest {
    private static final File FIXTURE = new File("../tools/models/silero-ru/frontend-parity.json");

    @Test public void prepareMatchesUpstreamTextCleanup() {
        assertEquals("привет, мир. как дела?", SileroFrontend.prepare("  Привет, Мир\nКак дела?  "));
        assertEquals("раз, два", SileroFrontend.prepare("Раз,\nдва"));
        assertEquals("эй а – б – в", SileroFrontend.prepare("A а — б ‑ в"));
        assertEquals("ёлка квотед стоит", SileroFrontend.prepare("Ёлка «quoted» стоит"));
        assertEquals("з+амок", SileroFrontend.prepare("з+амок^"));
    }

    @Test public void numbersAreSpokenNotDropped() {
        assertEquals("в две тысячи двадцать шестом году", SileroFrontend.prepare("В 2026 году"));
        assertEquals("двенадцать тридцать", SileroFrontend.prepare("12:30"));
        assertEquals("ноль ноль семь агент", SileroFrontend.prepare("007 агент"));
        assertEquals("сорок два человека", SileroFrontend.prepare("42 человека"));
        assertEquals("в двадцать первом веке", SileroFrontend.prepare("в21веке"));
        assertEquals("скидка от пяти до семи процентов до тридцать первого декабря.", SileroFrontend.prepare("Скидка 5–7% до 31.12."));
    }

    @Test public void sentenceTypesFollowClassifySentence() {
        assertEquals(0, SileroFrontend.classify("мама мыла раму."));
        assertEquals(1, SileroFrontend.classify("где ты был вчера?"));
        assertEquals(1, SileroFrontend.classify("ну а скажи, куда идём?"));
        assertEquals(2, SileroFrontend.classify("ты придёшь?"));
        assertEquals(3, SileroFrontend.classify("чай или кофе?"));
        assertEquals(4, SileroFrontend.classify("это правда, не так ли?"));
        assertEquals(4, SileroFrontend.classify("красиво, правда?"));
        assertEquals(5, SileroFrontend.classify("какой день!"));
        assertEquals(2, SileroFrontend.classify("правда?!"));
    }

    @Test public void piecesAreTypedBoundedAndEncoded() {
        List<SileroFrontend.Piece> pieces = SileroFrontend.pieces("Привет! Как дела? 123");
        assertEquals("[exclam:привет!, wh_q:как дела?, st:сто двадцать три]", pieces.toString());
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) longText.append("слово, ");
        for (SileroFrontend.Piece p : SileroFrontend.pieces(longText + "конец?")) {
            assertTrue(p.text.length() <= SileroFrontend.MAX_PIECE);
            assertEquals(2, p.type);
        }
        assertTrue(SileroFrontend.pieces("😀 ... 李明").isEmpty());
        assertEquals("[st:хеллоу…]", SileroFrontend.pieces("Hello 😀 ...").toString());
        assertEquals("те да~", SileroFrontend.encode("т.е. да?!"));
        for (char c : SileroFrontend.encode(SileroFrontend.pieces("Ой: а? Нет.").get(0).text).toCharArray())
            assertTrue(".:?!".indexOf(c) < 0);
    }

    @Test public void stressMatchesPythonPackage() throws Exception {
        String dir = System.getenv("SILERO_FRONTEND_DIR");
        Assume.assumeTrue("set SILERO_FRONTEND_DIR to a directory with frontend.bin/json", dir != null && FIXTURE.isFile());
        long started = System.nanoTime();
        SileroStress stress = new SileroStress(new File(dir, "frontend.bin"), new File(dir, "frontend.json"));
        System.out.println("Silero frontend load ms=" + (System.nanoTime() - started) / 1_000_000);
        JSONObject fixture = new JSONObject(new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8));
        JSONArray ids = fixture.getJSONObject("bert").getJSONArray("ids");
        int[] tokens = new int[ids.length()];
        for (int i = 0; i < tokens.length; i++) tokens[i] = ids.getInt(i);
        assertArrayEquals(tokens, stress.encode("мама [HOMO] замок [/HOMO] на двери."));
        JSONArray rows = fixture.getJSONArray("sentences");
        int mismatches = 0;
        started = System.nanoTime();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String text = row.getString("text");
            String homo = stress.homosolver(text), accented = stress.accentuate(text);
            if (!homo.equals(row.getString("homosolver")) || !accented.equals(row.getString("accented"))) {
                mismatches++;
                System.out.println("MISMATCH " + text + "\n  java   " + accented + "\n  python " + row.getString("accented"));
            }
            assertEquals(row.getString("type"), SileroFrontend.TYPES[SileroFrontend.classify(text)]);
        }
        System.out.println("Silero stress " + rows.length() + " sentences ms=" + (System.nanoTime() - started) / 1_000_000);
        assertEquals(0, mismatches);
    }
}
