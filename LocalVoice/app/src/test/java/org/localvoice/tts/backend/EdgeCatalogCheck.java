package org.localvoice.tts.backend;

import org.localvoice.tts.Catalog;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual parser/cache/catalog/SSML checks against the dated complete Microsoft response. */
public final class EdgeCatalogCheck {
    @org.junit.Test public void check() throws Exception { main(new String[]{"src/main/assets/edge-voices-snapshot.json",System.getProperty("java.io.tmpdir")}); }
    interface Checked { void run() throws Exception; }
    static int passed;
    static final SpeechBackend.Cancellation LIVE=()->false;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static void bad(Checked action) throws Exception { try { action.run();throw new AssertionError("invalid catalog accepted"); } catch(IOException expected) {} }
    static void test(String label,Checked action) throws Exception { action.run();passed++;System.out.println("PASS "+label); }
    public static void main(String[] args) throws Exception {
        String bundle=new String(Files.readAllBytes(Path.of(args[0])),StandardCharsets.UTF_8);
        EdgeCatalogData.Snapshot snapshot=EdgeCatalogData.load(new ByteArrayInputStream(bundle.getBytes(StandardCharsets.UTF_8)),"bundled",LIVE);
        JSONObject envelope=new JSONObject(bundle);String raw=envelope.getJSONArray("voices").toString();
        JSONObject first=envelope.getJSONArray("voices").getJSONObject(0);
        long updated=snapshot.updatedAtMillis;
        test("complete dated catalog includes322 voices and142 locales",()->{
            check(snapshot.voices.size()==322,"voice count");Set<String> locales=new HashSet<>();
            for(EdgeCatalogData.Voice voice:snapshot.voices) locales.add(voice.locale);
            check(locales.size()==142 && updated==1792349174277L,"locale count/date");
        });
        test("every voice uses exact service Name and Locale in SSML",()->{
            int scripts=0;
            for(EdgeCatalogData.Voice voice:snapshot.voices) {
                String ssml=EdgeProtocol.ssml("test",voice.shortName,voice.name,voice.locale,1,"catalog");
                check(ssml.contains("xml:lang='"+voice.locale+"'") && ssml.contains(EdgeProtocol.xml(voice.name)),"voice metadata changed");
                if(voice.locale.split("-").length>2) scripts++;
            }
            check(scripts>=6,"actual script/region cases absent");
        });
        test("stable old four IDs migrate into full dynamic Catalog",()->{
            Catalog.replaceEdgeVoices(snapshot);
            for(String id:new String[]{"en-US-AriaNeural","en-US-GuyNeural","ru-RU-SvetlanaNeural","ru-RU-DmitryNeural"})
                check(Catalog.findVoice("edge:"+id)!=null,"legacy ID removed");
            check(Catalog.findModel("edge").voices.size()==322,"partial model publication");
            for(Catalog.VoiceSpec voice:Catalog.findModel("edge").voices) check(voice.config.get("locale").equals(voice.languageTag),"locale config missing");
        });
        test("malformed or truncated JSON cannot publish partial catalog",()->{
            bad(()->EdgeCatalogData.parse(raw.substring(0,raw.length()-1),updated,"network",LIVE));
            bad(()->EdgeCatalogData.parse("{}",updated,"network",LIVE));
            bad(()->EdgeCatalogData.parse(raw+" garbage",updated,"network",LIVE));
            bad(()->EdgeCatalogData.parse("[]",updated,"network",LIVE));
        });
        test("oversize payload and voice count fail closed",()->{
            bad(()->EdgeCatalogData.parse(" ".repeat(EdgeCatalogData.MAX_BYTES+1),updated,"network",LIVE));
            String tiny="["+String.join(",",Collections.nCopies(EdgeCatalogData.MAX_VOICES+1,"{}"))+"]";
            bad(()->EdgeCatalogData.parse(tiny,updated,"network",LIVE));
        });
        test("duplicates and locale/name mismatch reject entire response",()->{
            bad(()->EdgeCatalogData.parse("["+first+","+first+"]",updated,"network",LIVE));
            JSONObject wrong=new JSONObject(first.toString());wrong.put("Locale","en-US");
            bad(()->EdgeCatalogData.parse("["+wrong+"]",updated,"network",LIVE));
        });
        test("missing/wrong typed/bounded metadata rejects entire response",()->{
            JSONObject missing=new JSONObject(first.toString());missing.remove("FriendlyName");
            bad(()->EdgeCatalogData.parse("["+missing+"]",updated,"network",LIVE));
            JSONObject wrong=new JSONObject(first.toString());wrong.put("Gender",7);
            bad(()->EdgeCatalogData.parse("["+wrong+"]",updated,"network",LIVE));
            wrong.put("Gender","X".repeat(33));bad(()->EdgeCatalogData.parse("["+wrong+"]",updated,"network",LIVE));
        });
        test("invalid BCP47 tails and ISO control metadata fail closed",()->{
            for(String locale:new String[]{"en-12A","en-US-Hans"}) {
                JSONObject wrong=new JSONObject(first.toString());wrong.put("Locale",locale);wrong.put("ShortName",locale+"-TestNeural");
                wrong.put("Name","Microsoft Server Speech Text to Speech Voice ("+locale+", TestNeural)");
                bad(()->EdgeCatalogData.parse("["+wrong+"]",updated,"network",LIVE));
            }
            JSONObject wrong=new JSONObject(first.toString());wrong.put("FriendlyName","Invalid\u0085label");
            bad(()->EdgeCatalogData.parse("["+wrong+"]",updated,"network",LIVE));
        });
        test("Natural label is derived only from actual FriendlyName",()->{
            JSONObject plain=new JSONObject(first.toString());plain.put("FriendlyName","Plain catalog label");
            EdgeCatalogData.Voice voice=EdgeCatalogData.parse("["+plain+"]",updated,"network",LIVE).voices.get(0);
            check(!voice.natural && voice.neural,"fabricated quality label");
        });
        test("read limits and malformed UTF8 fail closed",()->{
            bad(()->EdgeCatalogData.read(new ByteArrayInputStream(new byte[]{(byte)0xC3,0x28}),10,LIVE));
            bad(()->EdgeCatalogData.read(new ByteArrayInputStream(new byte[11]),10,LIVE));
        });
        test("parse/read cancellation prevents publication",()->{
            AtomicInteger checks=new AtomicInteger();bad(()->EdgeCatalogData.parse(raw,updated,"network",()->checks.incrementAndGet()>20));
            bad(()->EdgeCatalogData.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),EdgeCatalogData.MAX_BYTES,()->true));
        });
        Path directory=Files.createTempDirectory(Path.of(args[1]),"edge-catalog-check-");File cache=directory.resolve("catalog.json").toFile();
        test("atomic cache retains full metadata/date for offline reload",()->{
            EdgeCatalogData.save(cache,snapshot,LIVE);EdgeCatalogData.Snapshot cached=EdgeCatalogData.load(cache,LIVE);
            check(cached.voices.size()==322 && cached.updatedAtMillis==updated && cached.source.equals("cache"),"offline cache lost metadata");
        });
        test("cancel before cache commit preserves previous valid file",()->{
            byte[] before=Files.readAllBytes(cache.toPath());AtomicInteger checks=new AtomicInteger();
            bad(()->EdgeCatalogData.save(cache,snapshot,()->checks.incrementAndGet()>=2));
            check(Arrays.equals(before,Files.readAllBytes(cache.toPath())),"cancel replaced previous cache");
            check(directory.toFile().list().length==1,"temporary cache leaked");
        });
        test("corrupt cache/schema fails rather than silently returning partial rows",()->{
            File corrupt=directory.resolve("corrupt.json").toFile();Files.write(corrupt.toPath(),("{\"schema\":2,\"voices\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bad(()->EdgeCatalogData.load(corrupt,LIVE));Files.write(corrupt.toPath(),(bundle.substring(0,bundle.length()/2)).getBytes(java.nio.charset.StandardCharsets.UTF_8));bad(()->EdgeCatalogData.load(corrupt,LIVE));
        });
        System.out.println("PASS: "+passed+" full Edge catalog checks");
    }
}
