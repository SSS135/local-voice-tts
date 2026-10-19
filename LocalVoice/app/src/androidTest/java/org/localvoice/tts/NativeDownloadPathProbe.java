package org.localvoice.tts;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.system.Os;
import android.system.ErrnoException;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;

/** SDK observer only. Real app-UID paths with bounded in-memory HTTPS, never Internet. */
public final class NativeDownloadPathProbe {
    interface Io {void run()throws Exception;}
    static final byte[] PAYLOAD=new byte[4096],SENTINEL=new byte[]{9,8,7};
    static volatile Trial active;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void rejects(Io operation)throws Exception{try{operation.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
    static String hash(byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static void link(File target,File name)throws IOException{try{Os.symlink(target.getAbsolutePath(),name.getAbsolutePath());}catch(ErrnoException error){throw new IOException(error);}}
    static void removePrivate(File root)throws IOException{
        Path boundary=root.toPath().toAbsolutePath().normalize();if(!Files.exists(boundary,LinkOption.NOFOLLOW_LINKS))return;
        try(java.util.stream.Stream<Path> walk=Files.walk(boundary)){for(Path path:(Iterable<Path>)walk.sorted(Comparator.reverseOrder())::iterator){check(path.toAbsolutePath().normalize().startsWith(boundary),"private fixture cleanup boundary");Files.delete(path);}}
    }
    static final class Trial {
        final String boundary,kind;final File home,models,staging,outside,sentinel;boolean mutated;
        Trial(File home,String id,String boundary,String kind)throws IOException{this.home=home;this.boundary=boundary;this.kind=kind;models=new File(home,"models");staging=new File(models,id+"-fd1.download");outside=new File(home,"outside");check(outside.mkdirs(),"private outside sentinel directory");sentinel=new File(outside,"payload.bin");Files.write(sentinel.toPath(),SENTINEL);}
        void mutate(String at)throws IOException{
            if(mutated||!at.equals(boundary))return;mutated=true;
            if(kind.equals("root-link")){File saved=new File(home,"saved-models");check(models.renameTo(saved),"move only private models fixture");link(outside,models);}
            else if(kind.equals("stage-link")){File saved=new File(models,"saved-stage");check(staging.renameTo(saved),"move only private stage fixture");link(outside,staging);}
            else if(kind.equals("parent-link")){link(outside,new File(staging,"nested"));}
            else if(kind.equals("parent-file")){Files.write(new File(staging,"nested").toPath(),SENTINEL);}
            else {File parent=new File(staging,"nested");check(parent.mkdir(),"controlled test parent creation");File leaf=new File(parent,"payload.bin");if(kind.equals("leaf-link"))link(sentinel,leaf);else Files.write(leaf.toPath(),SENTINEL);}
        }
    }
    static void transport(){URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
        @Override protected URLConnection openConnection(URL url)throws IOException{
            check(url.getHost().equals("fixture.invalid"),"no external fixture request");Trial trial=active;check(trial!=null,"active HTTP fixture");
            return new HttpURLConnection(url){public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public int getResponseCode()throws IOException{trial.mutate("response");return 200;}public long getContentLengthLong(){return PAYLOAD.length;}public InputStream getInputStream()throws IOException{trial.mutate("input");return new ByteArrayInputStream(PAYLOAD);}};
        }
    }:null);}
    static JSONObject direct(File home)throws Exception{
        File models=new File(home,"models"),stage=new File(models,"direct.download"),outside=new File(home,"outside");check(stage.mkdirs()&&outside.mkdir(),"direct private fixtures");
        ModelFiles.DownloadPaths paths=new ModelFiles.DownloadPaths(models,stage);StorageMetrics.start();for(int i=0;i<100;i++)paths.plan("missing/file"+i);long lookupStats=StorageMetrics.FILE_STATS.value.get(),lookupCanonical=StorageMetrics.CANONICAL_CALLS.value.get();StorageMetrics.finish();check(lookupStats==0&&lookupCanonical==0,"planning has no FS I/O");
        for(String invalid:new String[]{"","/x","../x","a/../b","a//b","a/./b","a\\b","C:/x"})rejects(()->paths.plan(invalid));
        File payload=paths.prepareWrite("nested/payload.bin");check(payload.getParentFile().isDirectory()&&!payload.exists(),"only controlled missing parents created");Files.write(payload.toPath(),PAYLOAD);check(paths.checked("nested/payload.bin").equals(payload),"existing regular checked");
        File block=new File(stage,"block");Files.write(block.toPath(),SENTINEL);rejects(()->paths.prepareWrite("block/file"));rejects(()->paths.checked("block/file"));
        File external=new File(outside,"payload.bin");Files.write(external.toPath(),SENTINEL);File leafLink=new File(stage,"leaf-link");link(external,leafLink);rejects(()->paths.prepareWrite("leaf-link"));rejects(()->paths.checked("leaf-link"));
        File externalParent=new File(stage,"external-parent");link(outside,externalParent);rejects(()->paths.prepareWrite("external-parent/new"));rejects(()->paths.checked("external-parent/payload.bin"));
        File internalParent=new File(stage,"internal-parent");link(stage,internalParent);rejects(()->paths.prepareWrite("internal-parent/new"));rejects(()->paths.checked("internal-parent/nested/payload.bin"));
        File rootLink=new File(home,"models-link");link(models,rootLink);rejects(()->new ModelFiles.DownloadPaths(rootLink,new File(rootLink,"direct.download")));
        File stageLink=new File(models,"stage-link");link(stage,stageLink);rejects(()->new ModelFiles.DownloadPaths(models,stageLink));
        // Replacing an earlier checked parent must still fail at the later final boundary.
        File oldParent=payload.getParentFile(),saved=new File(stage,"saved-parent");check(oldParent.renameTo(saved),"move only private checked parent");link(outside,oldParent);rejects(()->paths.checked("nested/payload.bin"));check(Arrays.equals(Files.readAllBytes(external.toPath()),SENTINEL),"no outside sentinel write");
        return new JSONObject().put("actualAndroidOsSymlinks",true).put("purePlanCalls",100).put("planMetadataCalls",lookupStats).put("planCanonicalCalls",lookupCanonical).put("grammarRejected",true).put("controlledParentsCreated",true).put("nondirectoryParentRejected",true).put("rootAndStageLinksRejected",true).put("leafInternalExternalAncestorLinksRejected",true).put("earlierParentReplacementRejectedAtFinalBoundary",true).put("sentinelUnchanged",true);
    }
    public static JSONObject run(Instrumentation instrumentation,Context target)throws Exception{
        EdgeCatalogRepository.get(target); // Complete offline catalog bootstrap before temporary fixture models.
        new Random(1114).nextBytes(PAYLOAD);File root=new File(target.getNoBackupFilesDir(),"patch-path-gates-"+System.nanoTime());check(root.mkdir(),"unique private probe root");
        List<Catalog.Model> original=Catalog.MODELS;JSONArray rows=new JSONArray();
        try{
            JSONObject direct=direct(new File(root,"direct"));transport();String pin=hash(PAYLOAD);
            Field field=ModelStore.class.getDeclaredField("WORK");field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(null);
            for(String boundary:new String[]{"none","response","input"})for(String kind:boundary.equals("none")?new String[]{"clean"}:new String[]{"root-link","stage-link","parent-link","leaf-link","parent-file","unexpected-leaf"}){
                String id="path-"+boundary+"-"+kind+"-"+System.nanoTime();File home=new File(root,id);check(home.mkdir(),"trial private home");Trial trial=new Trial(home,id,boundary,kind);active=trial;
                Catalog.Model model=new Catalog.Model(id,"Path fixture","fixture","fd1",false,true,Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new Catalog.ModelFile("nested/payload.bin","https://fixture.invalid/payload.bin",PAYLOAD.length,pin)),Collections.emptyMap(),"test","","","","");List<Catalog.Model> withFixture=new ArrayList<>(original);withFixture.add(model);Catalog.MODELS=Collections.unmodifiableList(withFixture);
                Context wrapper=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public File getNoBackupFilesDir(){return home;}};
                ModelStore store=new ModelStore(wrapper);worker.submit(()->{}).get(10,TimeUnit.SECONDS);store.download(id);
                long deadline=SystemClock.elapsedRealtime()+10000;ModelStore.Progress terminal;
                do{Thread.sleep(5);terminal=store.progress(id);}while(terminal.running&&SystemClock.elapsedRealtime()<deadline);
                boolean clean=kind.equals("clean");File installed=new File(trial.models,id+"-fd1");
                if(clean){check(!trial.mutated&&!terminal.running&&terminal.state.equals("Installed")&&store.isInstalled(id),"clean nested control installation must succeed");check(ModelFiles.receiptMatches(installed,"fd1")&&ModelFiles.sha256(new File(installed,"nested/payload.bin")).equals(pin),"clean control exact pin and receipt");}
                else{check(trial.mutated,"network boundary fault actually injected");check(!terminal.running&&terminal.state.equals("Download failed"),"unexpected terminal after path fault: "+terminal.state);check(!store.isInstalled(id)&&!installed.exists(),"fault cannot activate/discover a model");}
                check(Arrays.equals(Files.readAllBytes(trial.sentinel.toPath()),SENTINEL),"outside sentinel unchanged");check(!new File(trial.outside,"installed.manifest").exists(),"no outside receipt");
                if(kind.equals("unexpected-leaf"))check(Arrays.equals(Files.readAllBytes(new File(trial.staging,"nested/payload.bin").toPath()),SENTINEL),"unexpected fresh file preserved");
                worker.submit(()->{}).get(10,TimeUnit.SECONDS);
                rows.put(new JSONObject().put("boundary",clean?"clean positive control":boundary.equals("response")?"getResponseCode before returning HTTP200":"getInputStream after HTTP200 before prepareWrite").put("mutation",kind).put("faultInjected",!clean).put("terminal",terminal.state).put("installed",clean).put("cleanControlPinAndReceipt",clean).put("outsideSentinelUnchanged",true).put("outsideReceiptAbsent",true));active=null;
            }
            return new JSONObject().put("direct",direct).put("actualStoreTrials",rows).put("transport","In-memory pinned HTTPS handler; no sockets or Internet").put("freshUniqueRoots",true).put("passed",true);
        }finally{active=null;Catalog.MODELS=original;removePrivate(root);}
    }
}
