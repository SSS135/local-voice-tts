package org.localvoice.tts.backend;

import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Bounded all-or-nothing catalog parsing and atomic cache storage. No network access. */
public final class EdgeCatalogData {
    public static final int MAX_BYTES=1024*1024, MAX_VOICES=5000;
    public static final class Voice {
        public final String name,shortName,friendlyName,locale,localeName,gender,status,suggestedCodec;
        public final boolean natural,neural;
        private Voice(JSONObject row) throws IOException,JSONException {
            name=field(row,"Name",512); shortName=field(row,"ShortName",128);
            friendlyName=field(row,"FriendlyName",512); locale=field(row,"Locale",64);
            localeName=optional(row,"LocaleName",512);gender=optional(row,"Gender",32);
            status=optional(row,"Status",32);suggestedCodec=optional(row,"SuggestedCodec",128);
            if(!locale.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){1,3}") || Locale.forLanguageTag(locale).getLanguage().isEmpty()
                    || !shortName.startsWith(locale+"-") || !shortName.substring(locale.length()+1).matches("[A-Za-z0-9-]+"))
                throw new IOException("Invalid Edge locale/voice identity");
            try {
                if(!new Locale.Builder().setLanguageTag(locale).build().toLanguageTag().equalsIgnoreCase(locale))
                    throw new IOException("Edge locale cannot be represented faithfully");
            } catch(IllformedLocaleException invalid) { throw new IOException("Invalid Edge locale",invalid); }
            String expected="Microsoft Server Speech Text to Speech Voice ("+locale+", "+shortName.substring(locale.length()+1)+")";
            if(!name.equals(expected)) throw new IOException("Edge voice name and locale disagree");
            natural=friendlyName.toLowerCase(Locale.ROOT).contains("(natural)");neural=shortName.endsWith("Neural");
        }
    }
    public static final class Snapshot {
        public final List<Voice> voices;
        public final long updatedAtMillis;
        public final String source;
        private final String raw;
        private Snapshot(List<Voice> voices,long updated,String source,String raw) {
            this.voices=Collections.unmodifiableList(voices);this.updatedAtMillis=updated;this.source=source;this.raw=raw;
        }
    }
    private static String field(JSONObject row,String key,int max) throws IOException,JSONException {
        Object value=row.get(key);
        if(!(value instanceof String)) throw new IOException("Invalid Edge catalog field: "+key);
        String text=(String)value;
        if(text.isEmpty() || text.length()>max) throw new IOException("Invalid Edge catalog field length: "+key);
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isISOControl(c) || Character.isLowSurrogate(c)) throw new IOException("Invalid Edge catalog text");
            if(Character.isHighSurrogate(c) && (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))))
                throw new IOException("Invalid Edge catalog Unicode");
        }
        return text;
    }
    private static String optional(JSONObject row,String key,int max) throws IOException,JSONException {
        return row.has(key)?field(row,key,max):"";
    }
    private static Object json(String raw) throws JSONException,IOException {
        JSONTokener tokener=new JSONTokener(raw);Object value=tokener.nextValue();
        if(tokener.nextClean()!=0) throw new IOException("Trailing Edge catalog data");
        return value;
    }
    public static Snapshot parse(String raw,long updated,String source,SpeechBackend.Cancellation cancellation) throws IOException {
        check(cancellation);
        if(raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || updated<=0) throw new IOException("Invalid Edge catalog size/date");
        try {
            Object value=json(raw);
            if(!(value instanceof JSONArray)) throw new IOException("Edge catalog must be an array");
            JSONArray rows=(JSONArray)value;
            if(rows.length()==0 || rows.length()>MAX_VOICES) throw new IOException("Invalid Edge voice count");
            List<Voice> voices=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(int i=0;i<rows.length();i++) {
                check(cancellation);Voice voice=new Voice(rows.getJSONObject(i));
                if(!ids.add(voice.shortName)) throw new IOException("Duplicate Edge voice identity");
                voices.add(voice);
            }
            voices.sort(Comparator.comparing((Voice voice)->!voice.natural).thenComparing(voice->voice.locale).thenComparing(voice->voice.friendlyName).thenComparing(voice->voice.shortName));
            return new Snapshot(voices,updated,source,raw);
        } catch(JSONException|StackOverflowError invalid) { throw new IOException("Malformed Edge catalog",invalid); }
    }
    public static String read(InputStream input,int limit,SpeechBackend.Cancellation cancellation) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=input.read(block))!=-1) {
            check(cancellation);if(n>limit-bytes.size()) throw new IOException("Edge catalog exceeds size limit");bytes.write(block,0,n);
        }
        check(cancellation);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch(CharacterCodingException invalid) { throw new IOException("Invalid Edge catalog encoding",invalid); }
    }
    public static Snapshot load(File file,SpeechBackend.Cancellation cancellation) throws IOException {
        try(InputStream input=new FileInputStream(file)) { return load(input,"cache",cancellation); }
    }
    public static Snapshot load(InputStream input,String source,SpeechBackend.Cancellation cancellation) throws IOException {
        try {
            Object value=json(read(input,MAX_BYTES+4096,cancellation));
            if(!(value instanceof JSONObject)) throw new IOException("Invalid Edge catalog cache");
            JSONObject cache=(JSONObject)value;
            if(cache.getInt("schema")!=1) throw new IOException("Unsupported Edge catalog cache");
            return parse(cache.getJSONArray("voices").toString(),cache.getLong("updatedAtMillis"),source,cancellation);
        } catch(JSONException|StackOverflowError invalid) { throw new IOException("Invalid Edge catalog cache",invalid); }
    }
    public static void save(File file,Snapshot snapshot,SpeechBackend.Cancellation cancellation) throws IOException {
        check(cancellation);
        String data="{\"schema\":1,\"updatedAtMillis\":"+snapshot.updatedAtMillis+",\"voices\":"+snapshot.raw+"}";
        byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES+4096) throw new IOException("Edge catalog cache exceeds limit");
        File parent=file.getParentFile();if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create Edge catalog cache");
        File pending=File.createTempFile("edge-catalog-",".part",parent);
        try {
            try(FileOutputStream output=new FileOutputStream(pending)) { output.write(bytes);output.getFD().sync(); }
            check(cancellation);
            Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { if(pending.exists()) pending.delete(); }
    }
    private static void check(SpeechBackend.Cancellation cancellation) throws InterruptedIOException {
        if(cancellation.isCancelled()) throw new InterruptedIOException("Edge catalog refresh cancelled");
    }
    private EdgeCatalogData() {}
}
