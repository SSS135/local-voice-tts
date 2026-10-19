package org.localvoice.tts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localvoice.tts.backend.BackendRegistry;

/** Pinned local models from the bundled models.json resource and an atomically replaced online voice metadata snapshot. */
public final class Catalog {
    private Catalog() {}
    public static final class ModelFile {
        public final String path, url, sha256;
        public final long bytes;
        ModelFile(String path, String url, long bytes, String sha256) {
            if (!sha256.matches("[a-f0-9]{64}") || bytes < 0 || !url.startsWith("https://")) throw new IllegalArgumentException("Invalid pinned file " + path);
            this.path=path; this.url=url; this.bytes=bytes; this.sha256=sha256;
        }
    }
    public static final class VoiceSpec {
        public final String id,label,languageTag,modelId;
        public final int speakerId;
        public final Map<String,String> config;
        /** Retired ids of this voice (e.g. after a model id change); clients and preferences may still hold them. */
        public final List<String> aliases;
        VoiceSpec(String modelId, String name, String label, String languageTag, int speakerId, Map<String,String> config) {
            this(modelId,name,label,languageTag,speakerId,config,Collections.emptyList());
        }
        VoiceSpec(String modelId, String name, String label, String languageTag, int speakerId, Map<String,String> config, List<String> aliases) {
            this.modelId=modelId; this.id=modelId+":"+name; this.label=label; this.languageTag=languageTag; this.speakerId=speakerId; this.config=config; this.aliases=aliases;
        }
    }
    public static final class Model {
        public final String id,label,backend,revision,license,licenseUrl,licenseText,description,licenseAsset;
        public final boolean network,available;
        /** android.speech.tts.Voice QUALITY_* / LATENCY_* values (100..500) advertised by onGetVoices. */
        public final int quality,latency;
        public final List<String> languages;
        public final List<VoiceSpec> voices;
        public final List<ModelFile> files;
        public final Map<String,String> config;
        Model(String id,String label,String backend,String revision,boolean network,boolean available,List<String> languages,List<VoiceSpec> voices,List<ModelFile> files,Map<String,String> config,String license,String licenseUrl,String licenseText,String licenseAsset,String description) {
            this(id,label,backend,revision,network,available,network ? 500 : 400,network ? 400 : 300,languages,voices,files,config,license,licenseUrl,licenseText,licenseAsset,description);
        }
        Model(String id,String label,String backend,String revision,boolean network,boolean available,int quality,int latency,List<String> languages,List<VoiceSpec> voices,List<ModelFile> files,Map<String,String> config,String license,String licenseUrl,String licenseText,String licenseAsset,String description) {
            if (!id.matches("[a-z0-9-]+") || !revision.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid model identity");
            if (quality < 100 || quality > 500 || latency < 100 || latency > 500) throw new IllegalArgumentException("Invalid voice quality/latency");
            this.quality=quality; this.latency=latency;
            this.id=id; this.label=label; this.backend=backend; this.revision=revision; this.network=network; this.available=available; this.languages=languages; this.voices=Collections.unmodifiableList(voices); this.files=files; this.config=config; this.license=license; this.licenseUrl=licenseUrl; this.licenseText=licenseText; this.licenseAsset=licenseAsset; this.description=description;
        }
        public long bytes() { long total=0; for(ModelFile file:files) total+=file.bytes; return total; }
    }
    private static Map<String,String> config(String... values) {
        Map<String,String> map = new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) map.put(values[i],values[i+1]);
        return Collections.unmodifiableMap(map);
    }
    public static synchronized void replaceEdgeVoices(org.localvoice.tts.backend.EdgeCatalogData.Snapshot snapshot) {
        List<VoiceSpec> voices=new ArrayList<>();Set<String> languages=new TreeSet<>();
        for(org.localvoice.tts.backend.EdgeCatalogData.Voice voice:snapshot.voices) {
            voices.add(new VoiceSpec("edge",voice.shortName,voice.friendlyName,voice.locale,0,
                config("voice",voice.shortName,"voiceName",voice.name,"locale",voice.locale,"localeName",voice.localeName,
                    "gender",voice.gender,"natural",Boolean.toString(voice.natural),"neural",Boolean.toString(voice.neural))));
            languages.add(voice.locale);
        }
        Model old=findModel("edge");List<Model> models=new ArrayList<>(MODELS);
        models.set(models.indexOf(old),new Model(old.id,old.label,old.backend,"catalog1",true,true,old.quality,old.latency,
            new ArrayList<>(languages),voices,old.files,old.config,old.license,old.licenseUrl,old.licenseText,old.licenseAsset,old.description));
        MODELS=Collections.unmodifiableList(models);
    }
    public static volatile List<Model> MODELS=load();

    private static List<Model> load() {
        try (InputStream in = Catalog.class.getResourceAsStream("models.json")) {
            if (in == null) throw new IllegalStateException("Bundled models.json resource is missing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Bundled models.json is unreadable", e);
        }
    }

    /** Parses and strictly validates catalog JSON; any bad row fails the whole catalog. */
    static List<Model> parse(String json) {
        JSONArray rows;
        try {
            JSONObject root = new JSONObject(json);
            if (root.length() != 1) throw new IllegalStateException("models.json allows only the \"models\" key");
            rows = root.getJSONArray("models");
        } catch (JSONException e) {
            throw new IllegalStateException("models.json is malformed: " + e.getMessage(), e);
        }
        List<Model> models = new ArrayList<>();
        Set<String> modelIds = new HashSet<>();
        Set<String> voiceIds = new HashSet<>();
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            String name = "#" + i;
            try {
                JSONObject row = rows.getJSONObject(i);
                name = row.optString("id", name);
                Model model = model(row);
                require(modelIds.add(model.id), "duplicate model id");
                for (VoiceSpec voice : model.voices) require(voiceIds.add(voice.id), "duplicate voice id " + voice.id);
                for (VoiceSpec voice : model.voices) for (String alias : voice.aliases) aliases.add(alias);
                models.add(model);
            } catch (JSONException | IllegalArgumentException e) {
                throw new IllegalStateException("Invalid catalog model " + name + ": " + e.getMessage(), e);
            }
        }
        for (String alias : aliases) if (voiceIds.contains(alias) || !voiceIds.add(alias)) throw new IllegalStateException("Voice alias collides: " + alias);
        return Collections.unmodifiableList(models);
    }

    private static Model model(JSONObject row) throws JSONException {
        String id = row.getString("id");
        String backend = row.getString("backend");
        boolean network = row.getBoolean("network");
        require(BackendRegistry.known(backend), "unknown backend " + backend);
        List<VoiceSpec> voices = new ArrayList<>();
        JSONArray voiceRows = row.getJSONArray("voices");
        for (int i = 0; i < voiceRows.length(); i++) {
            JSONObject voice = voiceRows.getJSONObject(i);
            List<String> aliases = new ArrayList<>();
            JSONArray aliasRows = voice.optJSONArray("aliases");
            if (aliasRows != null) for (int j = 0; j < aliasRows.length(); j++) aliases.add(aliasRows.getString(j));
            voices.add(new VoiceSpec(id, voice.getString("name"), voice.getString("label"), voice.getString("language"), voice.getInt("speaker"), map(voice.optJSONObject("config")), Collections.unmodifiableList(aliases)));
        }
        List<ModelFile> files = new ArrayList<>();
        JSONArray fileRows = row.getJSONArray("files");
        for (int i = 0; i < fileRows.length(); i++) {
            JSONObject file = fileRows.getJSONObject(i);
            String path = file.getString("path");
            long bytes = file.getLong("bytes");
            require(!path.isEmpty() && bytes > 0, "file " + path + " needs a path and a positive size");
            files.add(new ModelFile(path, file.getString("url"), bytes, file.getString("sha256")));
        }
        require(network == files.isEmpty(), network ? "network model must not have files" : "local model needs files");
        List<String> languages = new ArrayList<>();
        JSONArray languageRows = row.getJSONArray("languages");
        for (int i = 0; i < languageRows.length(); i++) languages.add(languageRows.getString(i));
        // Defaults: neural local voices are high quality; network voices are very high quality but add round-trip latency.
        return new Model(id, row.getString("label"), backend, row.getString("revision"), network, row.getBoolean("available"),
            row.optInt("quality", network ? 500 : 400), row.optInt("latency", network ? 400 : 300),
            Collections.unmodifiableList(languages), voices, Collections.unmodifiableList(files), map(row.getJSONObject("config")),
            row.getString("license"), row.getString("licenseUrl"), row.getString("licenseText"), row.getString("licenseAsset"), row.getString("description"));
    }

    private static Map<String,String> map(JSONObject object) throws JSONException {
        Map<String,String> map = new LinkedHashMap<>();
        if (object == null) return Collections.unmodifiableMap(map);
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            map.put(key, object.getString(key));
        }
        return Collections.unmodifiableMap(map);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static Model findModel(String id) { for(Model model:MODELS) if(model.id.equals(id)) return model; return null; }
    public static VoiceSpec findVoice(String id) { for(Model model:MODELS) for(VoiceSpec voice:model.voices) if(voice.id.equals(id)) return voice; return null; }
    /** Current id for a voice id or one of its retired aliases; unknown ids are returned unchanged. */
    public static String canonicalVoiceId(String id) {
        if (id == null || findVoice(id) != null) return id;
        for(Model model:MODELS) for(VoiceSpec voice:model.voices) if(voice.aliases.contains(id)) return voice.id;
        return id;
    }
    public static final String ATTRIBUTION="LocalVoice: Android system TTS engine.\n\nSherpa-ONNX runtime by k2-fsa: Apache-2.0. https://github.com/k2-fsa/sherpa-onnx\n\nKokoro by hexgrad: Apache-2.0 model weights. https://huggingface.co/hexgrad/Kokoro-82M\n\neSpeak NG frontend: GPL-3.0. https://github.com/espeak-ng/espeak-ng\n\nllama.cpp / ggml (NeuTTS runtime): MIT. https://github.com/ggml-org/llama.cpp\n\nNeuTTS Nano by Neuphonic: NeuTTS Open License 1.0 model weights. https://github.com/neuphonic/neutts\n\nSupertonic 3 by Supertone: OpenRAIL-M model weights. https://huggingface.co/Supertone/supertonic-3\nONNX conversions by csukuangfj / csukuangfj2.\n\nMicrosoft online speech: Microsoft services agreement. This project is independent of Microsoft.\n\nApplication and reused VN Reader Edge protocol source: GPL-3.0. Source and exact notices accompany this release.";
}
