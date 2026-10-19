package org.localvoice.tts;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import android.os.Handler;

/** Runs production ModelStore repair control flow with real files and fake HTTPS transport only. */
final class ModelRepairCheck {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static void idle(ExecutorService executor)throws Exception{executor.submit(()->{}).get(10,TimeUnit.SECONDS);Handler.drain();}
    static Catalog.Model model(String id,byte[] bytes)throws Exception{return new Catalog.Model(id,false,Arrays.asList(new Catalog.ModelFile("first.bin",bytes.length,StorageStoreCheck.hash(bytes)),new Catalog.ModelFile("nested/last.bin",bytes.length,StorageStoreCheck.hash(bytes))));}
    static void seed(File directory,Catalog.Model model,byte[] bytes,boolean receipt)throws Exception{
        check(directory.mkdirs(),"fixture directory created");for(Catalog.ModelFile file:model.files){File local=ModelFiles.child(directory,file.path);local.getParentFile().mkdirs();Files.write(local.toPath(),bytes);}if(receipt)ModelFiles.writeReceipt(directory,model.revision);
    }
    static void corrupt(File file)throws Exception{long time=file.lastModified();try(RandomAccessFile output=new RandomAccessFile(file,"rw")){int first=output.read();output.seek(0);output.write(first^1);output.getFD().sync();}check(file.setLastModified(time),"same-size restored-mtime corruption fixture");}
    static void rejectInstalled(ModelStore store,Catalog.Model model,ExecutorService executor)throws Exception{
        AtomicBoolean rejected=new AtomicBoolean();executor.submit(()->{try{store.verifyInstalled(model.id);}catch(IOException expected){rejected.set(true);}}).get(10,TimeUnit.SECONDS);check(rejected.get(),"corruption rejected");check(!ModelFiles.receiptMatches(store.modelDirectory(model.id),model.revision),"bad receipt removed");
    }
    static void verifyPublished(ModelStore store,Catalog.Model model)throws Exception{
        File target=store.modelDirectory(model.id);check(store.progress(model.id).state.equals("Installed"),"repair installed");check(ModelFiles.receiptMatches(target,model.revision),"only complete receipt published");
        for(Catalog.ModelFile file:model.files)check(ModelFiles.verified(ModelFiles.child(target,file.path),file.bytes,file.sha256),"every repaired runtime pin verified");check(!new File(target.getPath()+".download").exists(),"single atomic directory publication");
    }
    static void run(ModelStore store,ExecutorService executor,byte[] bytes)throws Exception{
        FakeModelTransport.install();
        Catalog.Model repaired=model("repair-one",bytes);Catalog.MODELS=Collections.singletonList(repaired);File target=store.modelDirectory(repaired.id);seed(target,repaired,bytes,true);
        executor.submit(()->{try{store.verifyInstalled(repaired.id);}catch(IOException error){throw new UncheckedIOException(error);}}).get(10,TimeUnit.SECONDS); // warm a real verified cache first
        corrupt(new File(target,"first.bin"));rejectInstalled(store,repaired,executor);FakeModelTransport.reset(bytes);
        AtomicBoolean sawUnpublished=new AtomicBoolean();FakeModelTransport.beforeBody=()->{File stage=new File(target.getPath()+".download");check(!target.exists()&&stage.isDirectory(),"repair stages old directory before transfer");check(!new File(stage,"installed.manifest").exists(),"no failed receipt retained");sawUnpublished.set(true);};
        store.download(repaired.id);idle(executor);check(sawUnpublished.get(),"actual transfer boundary reached; state="+store.progress(repaired.id).state+" detail="+store.progress(repaired.id).detail);check(FakeModelTransport.requests.equals(Collections.singletonList("/first.bin")),"only bad file fetched; good file reused");verifyPublished(store,repaired);
        System.out.println("PASS repair salvages rejected installed files, fetches only corrupt file, rechecks all pins and publishes atomically");

        Catalog.Model cancelled=model("repair-cancel",bytes);Catalog.MODELS=Collections.singletonList(cancelled);File cancelTarget=store.modelDirectory(cancelled.id);seed(cancelTarget,cancelled,bytes,true);corrupt(new File(cancelTarget,"first.bin"));rejectInstalled(store,cancelled,executor);FakeModelTransport.reset(bytes);
        AtomicBoolean cancelledAfterMove=new AtomicBoolean();FakeModelTransport.beforeBody=()->{check(!cancelTarget.exists(),"old target moved before cancel");store.cancel(cancelled.id);cancelledAfterMove.set(true);};
        store.download(cancelled.id);idle(executor);File cancelStage=new File(cancelTarget.getPath()+".download");check(cancelledAfterMove.get(),"cancel happened after salvage");check(store.progress(cancelled.id).state.equals("Cancelled"),"repair cancellation terminal");check(!store.isInstalled(cancelled.id)&&!cancelTarget.exists(),"cancelled salvage stays unadvertised");check(cancelStage.isDirectory()&&!new File(cancelStage,"installed.manifest").exists(),"verified staging kept without receipt");check(ModelFiles.verified(new File(cancelStage,"nested/last.bin"),bytes.length,StorageStoreCheck.hash(bytes)),"good salvaged file retained");
        FakeModelTransport.reset(bytes);store.download(cancelled.id);idle(executor);check(FakeModelTransport.requests.equals(Collections.singletonList("/first.bin")),"explicit retry fetches only bad/missing file");verifyPublished(store,cancelled);
        System.out.println("PASS cancellation after salvage leaves unavailable reusable stage; explicit retry fetches only damaged file");

        Catalog.Model existing=model("repair-existing-stage",bytes);Catalog.MODELS=Collections.singletonList(existing);File existingTarget=store.modelDirectory(existing.id);seed(existingTarget,existing,bytes,true);corrupt(new File(existingTarget,"first.bin"));rejectInstalled(store,existing,executor);File existingStage=new File(existingTarget.getPath()+".download");check(existingStage.mkdirs(),"independent preexisting stage");File preserved=new File(existingStage,"first.bin");Files.write(preserved.toPath(),bytes);long preservedTime=preserved.lastModified();FakeModelTransport.reset(bytes);
        FakeModelTransport.beforeBody=()->{check(existingTarget.isDirectory(),"preexisting stage must not be replaced with old installed directory");check(preserved.lastModified()==preservedTime,"preexisting verified file untouched before transfer");};
        store.download(existing.id);idle(executor);check(FakeModelTransport.requests.equals(Collections.singletonList("/nested/last.bin")),"preexisting stage good file reused; missing file alone fetched");verifyPublished(store,existing);
        System.out.println("PASS existing staging directory is preserved rather than clobbered by rejected installation");

        Catalog.Model valid=model("repair-valid",bytes);Catalog.MODELS=Collections.singletonList(valid);File validTarget=store.modelDirectory(valid.id);seed(validTarget,valid,bytes,true);FakeModelTransport.reset(bytes);
        // Main snapshot is intentionally absent; force the real worker-side valid-installed recheck.
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);executor.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}});check(entered.await(5,TimeUnit.SECONDS),"worker held before valid queued request");
        try{store.download(valid.id);}finally{release.countDown();idle(executor);}
        check(FakeModelTransport.requests.isEmpty(),"valid installed model never downloaded");check(validTarget.isDirectory()&&!new File(validTarget.getPath()+".download").exists(),"valid installed model never staged");verifyPublished(store,valid);
        System.out.println("PASS valid installed model is rechecked and retained without move or HTTP");
        FakeModelTransport.beforeBody=null;
    }
}
