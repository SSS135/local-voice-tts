package org.localvoice.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run with java -ea. Real file fixtures and injected streams; no model download or device required. */
public final class StorageSelfTest {
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); } catch(IOException expected) {}
    }
    static void check(boolean value,String detail) { if(!value) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("localvoice-storage-test").toFile();
        try {
            for(String unsafe:new String[]{"../escape","/absolute","C:/absolute","folder/../escape","folder\\escape","folder//empty","."})
                rejects(()->ModelFiles.child(root,unsafe));
            File staging=new File(root,"model.download"); check(staging.mkdir(),"staging creation");
            File part=ModelFiles.child(staging,"weight.onnx.part");
            byte[] payload="pinned model fixture".getBytes(StandardCharsets.UTF_8);
            AtomicBoolean cancel=new AtomicBoolean(false);
            InputStream interrupted=new InputStream() {
                int count;
                public int read() throws IOException { if(count++>3) throw new IOException("injected disconnect"); return 42; }
            };
            rejects(()->DownloadFile.copy(interrupted,part,payload.length,()->false,n->{}));
            check(!ModelFiles.receiptMatches(staging,"v1"),"interrupted data is never installed");
            DownloadFile.copy(new ByteArrayInputStream(payload),part,payload.length,()->false,n->{});
            String hash=ModelFiles.sha256(part);
            check(ModelFiles.verified(part,payload.length,hash),"retry replaces partial stream");
            rejects(()->DownloadFile.copy(new ByteArrayInputStream(payload),part,payload.length,()->true,n->{}));
            check(part.length()==0,"cancel before copy writes nothing");
            rejects(()->DownloadFile.copy(new ByteArrayInputStream(payload),part,payload.length,()->cancel.get(),n->cancel.set(true)));
            check(!ModelFiles.receiptMatches(staging,"v1"),"mid-stream cancellation has no installation receipt");
            rejects(()->DownloadFile.copy(new ByteArrayInputStream(payload),part,payload.length-1,()->false,n->{}));
            rejects(()->DownloadFile.copy(new ByteArrayInputStream(payload),part,payload.length+1,()->false,n->{}));
            DownloadFile.copy(new ByteArrayInputStream(payload),part,payload.length,()->false,n->{});
            File local=ModelFiles.child(staging,"weight.onnx"); check(part.renameTo(local),"verified cache rename");
            check(ModelFiles.verified(local,payload.length,hash),"cached verified file reusable");
            try(RandomAccessFile corrupt=new RandomAccessFile(local,"rw")) { corrupt.seek(0); corrupt.write(0); }
            check(!ModelFiles.verified(local,payload.length,hash),"same-size corruption rejected");
            DownloadFile.copy(new ByteArrayInputStream(payload),local,payload.length,()->false,n->{});
            ModelFiles.writeReceipt(staging,"v1");
            check(!ModelFiles.receiptMatches(staging,"v2"),"different pinned revision rejected");
            File installed=new File(root,"model-v1"); ModelFiles.activate(staging,installed);
            check(!staging.exists() && ModelFiles.receiptMatches(installed,"v1"),"atomic activation publishes complete directory");
            check(ModelFiles.verified(ModelFiles.child(installed,"weight.onnx"),payload.length,hash),"activated file preserves checksum");
            File other=new File(root,"other.download"); check(other.mkdir(),"other staging creation");
            rejects(()->ModelFiles.activate(other,installed));
            DownloadFile.requireSpace(132,100,32);
            rejects(()->DownloadFile.requireSpace(131,100,32));
            rejects(()->DownloadFile.requireSpace(100,101,0));
            Set<String> voiceIds=new HashSet<>();
            int licenseAssets=0;
            for(Catalog.Model model:Catalog.MODELS) {
                check(!model.network || model.files.isEmpty(),"online model contains no weights");
                check(model.available || model.voices.isEmpty(),"unsupported model advertises no voices");
                if(args.length>0&&!model.licenseAsset.isEmpty()){
                    File notice=ModelFiles.child(new File(args[0],"licenses"),model.licenseAsset);
                    check(notice.isFile()&&notice.length()>0,"catalog license asset exists at AboutActivity path: "+model.id);
                    licenseAssets++;
                }
                Set<String> paths=new HashSet<>();
                for(Catalog.ModelFile file:model.files) {
                    ModelFiles.child(root,file.path);
                    check(paths.add(file.path),"duplicate model file path");
                    check(!file.url.contains("/main/"),"download is immutable");
                }
                for(Catalog.VoiceSpec voice:model.voices) {
                    check(voiceIds.add(voice.id),"unique Android voice id");
                    check(Catalog.findVoice(voice.id)==voice,"voice lookup identity");
                    check(voice.modelId.equals(model.id),"voice belongs to model");
                }
            }
            check(Catalog.findModel("kokoro-en").bytes()==172731932L,"truthful Kokoro total");
            check(Catalog.findModel("supertonic-3").bytes()==145310775L,"truthful Supertonic total includes upstream license");
            if(args.length>0)System.out.println("PASS "+licenseAssets+" catalog model license assets at licenses/ paths used by AboutActivity");
            System.out.println("PASS: path boundaries, interrupted stream/retry, cancel, length bounds, cache reuse, same-size corruption, revision receipts, atomic activation, storage limits, pinned catalog/voice consistency");
        } finally { ModelFiles.remove(root); }
    }
}
