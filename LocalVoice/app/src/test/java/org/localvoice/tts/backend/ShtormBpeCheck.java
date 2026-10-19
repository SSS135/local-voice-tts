package org.localvoice.tts.backend;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

/** Expected tokens come from the pinned genuine SentencePiece processor, not this implementation. */
public final class ShtormBpeCheck {
    // Model files are untracked research inputs; see research/shtorm/REPRODUCE.md.
    @org.junit.Test public void check() throws Exception { String onnx="../../research/shtorm/onnx";org.junit.Assume.assumeTrue("missing untracked input "+onnx,new java.io.File(onnx).isDirectory());main(new String[]{onnx,"../tests/fixtures/shtorm-tokenizer-parity.json"}); }
    interface Action{void run()throws Exception;}
    static int checks;
    static void check(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
    static void reject(Action action,String detail)throws Exception{try{action.run();throw new AssertionError(detail);}catch(IllegalArgumentException expected){checks++;}}
    static String sha256(Path file)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    public static void main(String[] args)throws Exception{
        Path folder=Paths.get(args[0]);JSONObject report=new JSONObject(new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8));
        JSONObject provenance=new JSONObject(new String(Files.readAllBytes(Paths.get(args[1]).resolveSibling("shtorm-tokenizer-provenance.json")),StandardCharsets.UTF_8));
        if(!sha256(Paths.get(args[1])).equals(provenance.getString("fixtureSha256"))||!sha256(folder.resolve("normalizer.bin")).equals(provenance.getString("normalizerSha256")))throw new AssertionError("Pinned fixture/normalizer provenance mismatch");
        Path original=folder.resolveSibling("tokenizer.model");if(Files.exists(original)&&!sha256(original).equals(provenance.getString("tokenizerModelSha256")))throw new AssertionError("Pinned original SentencePiece model hash mismatch");
        ShtormBpeFrontend frontend=new ShtormBpeFrontend(folder.resolve("vocab.json").toFile(),folder.resolve("token_scores.json").toFile(),folder.resolve("normalizer.bin").toFile());
        check(report.getString("modelPin").equals("33f6c328d62c4da5c1227bbe081e89b574a6183a"),"Pinned genuine tokenizer fixture provenance");
        JSONArray fixtures=report.getJSONArray("fixtures");int exact=0,rejected=0;
        for(int index=0;index<fixtures.length();index++){
            JSONObject fixture=fixtures.getJSONObject(index);String text=fixture.getString("text"),normalized=fixture.getString("normalized");
            check(frontend.normalize(text).equals(normalized),"SentencePiece normalized bytes differ at case "+index);
            boolean latin=normalized.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN);
            if(fixture.getBoolean("hasUnknown")||latin){reject(()->frontend.encode(text),"Unsupported input accepted at case "+index);rejected++;continue;}
            JSONArray expected=fixture.getJSONArray("ids");int[] ids=frontend.encode(text);check(ids.length==expected.length(),"Token count differs at case "+index);
            String bridge=frontend.encodeBridge(text);check(bridge.length()==ids.length,"Bridge emits exactly one symbol per ID");
            for(int n=0;n<ids.length;n++){check(ids[n]==expected.getInt(n),"Actual SentencePiece token mismatch case "+index+" token "+n);check(bridge.charAt(n)==0xE000+ids[n],"Bridge added or changed token ID");}
            exact++;
        }
        reject(()->frontend.encode("N"),"Known Latin token must still be rejected by Russian-only frontend policy");
        reject(()->frontend.encode("Привет🙂"),"Emoji must fail before native synthesis");
        reject(()->frontend.encode("Привет\uD800"),"Malformed UTF16 must be rejected");
        check(!Arrays.equals(frontend.encode("Привет"),frontend.encode("ПРИВЕТ")),"Tokenizer must not case fold");
        check(!Arrays.equals(frontend.encode("Привет"),frontend.encode("Приве\u0301т")),"Russian stress accent must be preserved");
        Path corrupt=Files.createTempFile("shtorm-normalizer-invalid",".bin");
        try{Files.write(corrupt,new byte[1029]);try{new ShtormBpeFrontend(folder.resolve("vocab.json").toFile(),folder.resolve("token_scores.json").toFile(),corrupt.toFile());throw new AssertionError("Corrupt map accepted");}catch(IOException expected){checks++;}}finally{Files.delete(corrupt);}
        System.out.println("PASS: "+fixtures.length()+" genuine SentencePiece fixtures, "+exact+" exact token-ID/PUA cases, "+rejected+" unsupported cases rejected; "+checks+" assertions including normalization, case/stress, malformed Unicode and corrupt normalizer");
    }
}
