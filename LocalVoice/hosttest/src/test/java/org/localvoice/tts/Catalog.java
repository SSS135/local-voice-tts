package org.localvoice.tts;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.io.*;
import android.content.Context;
/** Tiny pinned test catalog: production ModelStore is compiled unchanged against this boundary. */
final class Catalog {
    static final class ModelFile {final String path,url,sha256;final long bytes;ModelFile(String path,long bytes,String hash){this.path=path;this.bytes=bytes;sha256=hash;url="https://example.invalid/"+path;}}
    static final class VoiceSpec {final String id,modelId;VoiceSpec(String id,String model){this.id=id;modelId=model;}}
    static final class Model {final String id,revision="r1";final boolean network,available=true;final List<ModelFile> files;final List<VoiceSpec> voices;final Map<String,String> config=new HashMap<>();Model(List<ModelFile> files){this("fixture",false,files);}Model(String id,boolean network,List<ModelFile> files){this.id=id;this.network=network;this.files=files;voices=Collections.singletonList(new VoiceSpec(id+":voice",id));}long bytes(){long n=0;for(ModelFile f:files)n+=f.bytes;return n;}}
    static List<Model> MODELS=Collections.emptyList();
    static Model findModel(String id){for(Model m:MODELS)if(m.id.equals(id))return m;return null;}
    static VoiceSpec findVoice(String id){for(Model m:MODELS)for(VoiceSpec v:m.voices)if(v.id.equals(id))return v;return null;}
}
final class VoicePreferences {private static final Map<String,Boolean> ONLINE=new java.util.concurrent.ConcurrentHashMap<>();VoicePreferences(Context c){}String selected(){return "";}void select(String id){}boolean onlineEnabled(String id){return Boolean.TRUE.equals(ONLINE.get(id));}void enableOnline(String id,boolean value){ONLINE.put(id,value);}}
final class EdgeCatalogRepository {
    static int initialized;
    static CompletableFuture<EdgeCatalogRepository> initialize(Context c){initialized++;return CompletableFuture.completedFuture(new EdgeCatalogRepository());}
    static EdgeCatalogRepository get(Context c){return new EdgeCatalogRepository();}
}
/** Host fallback deliberately has no invented Android nanosecond identity. */
final class AndroidFileStamp {
    static ModelFiles.Stamp read(File f)throws IOException{return ModelFiles.portableStamp(f);}
    // This host fixture has no Android NOFOLLOW/fstat descriptor proof. Exercise fallback.
    static ModelFiles.HeldFile open(File f)throws IOException{return null;}
}
