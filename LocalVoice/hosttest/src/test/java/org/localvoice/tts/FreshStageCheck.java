package org.localvoice.tts;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual store classification/transport control flow; host stamps intentionally stay weak. */
final class FreshStageCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static File stage(ModelStore store,Catalog.Model model)throws Exception{return new File(store.modelDirectory(model.id).getPath()+".download");}
    static void select(Catalog.Model model,byte[] bytes){Catalog.MODELS=Collections.singletonList(model);FakeModelTransport.reset(bytes);}
    static AtomicLong firstBodyChecks(AtomicLong checks){FakeModelTransport.beforeBody=()->checks.compareAndSet(-1,StorageMetrics.VERIFY_CHECKS.value.get());return checks;}
    static long install(ModelStore store,ExecutorService worker,Catalog.Model model)throws Exception{
        StorageMetrics.start();store.download(model.id);ModelRepairCheck.idle(worker);long checks=StorageMetrics.VERIFY_CHECKS.value.get();StorageMetrics.finish();
        ModelRepairCheck.verifyPublished(store,model);return checks;
    }
    static void run(ModelStore store,ExecutorService worker,byte[] bytes)throws Exception{
        Catalog.Model fresh=ModelRepairCheck.model("fresh-new-leaf",bytes);select(fresh,bytes);check(!stage(store,fresh).exists(),"fresh leaf initially absent");
        AtomicLong atBody=firstBodyChecks(new AtomicLong(-1));long total=install(store,worker,fresh);
        check(atBody.get()==0,"exclusive newly created staging skips only initial reuse scan");check(total>=fresh.files.size(),"fresh model still reaches final SHA validation");check(FakeModelTransport.requests.size()==2,"both fresh files transferred");
        System.out.println("PASS new staging leaf skips initial reuse only; final pins and atomic receipt still required");

        Catalog.Model empty=ModelRepairCheck.model("fresh-old-empty-leaf",bytes);select(empty,bytes);check(stage(store,empty).mkdirs(),"old empty stage fixture");atBody=firstBodyChecks(new AtomicLong(-1));install(store,worker,empty);
        check(atBody.get()>=empty.files.size(),"preexisting empty stage is not a fresh job");System.out.println("PASS preexisting empty staging directory retains initial reuse scan");

        Catalog.Model partial=ModelRepairCheck.model("fresh-old-partial-leaf",bytes);select(partial,bytes);File partialStage=stage(store,partial);check(partialStage.mkdirs(),"old partial stage");Files.write(new File(partialStage,"first.bin").toPath(),bytes);atBody=firstBodyChecks(new AtomicLong(-1));install(store,worker,partial);
        check(atBody.get()>=partial.files.size()&&FakeModelTransport.requests.equals(Collections.singletonList("/nested/last.bin")),"partial stage validates and reuses its good file");System.out.println("PASS existing partial staging validates good files instead of treating them as fresh");

        Catalog.Model corrupt=ModelRepairCheck.model("fresh-old-corrupt-leaf",bytes);select(corrupt,bytes);ModelRepairCheck.seed(stage(store,corrupt),corrupt,bytes,false);ModelRepairCheck.corrupt(new File(stage(store,corrupt),"first.bin"));atBody=firstBodyChecks(new AtomicLong(-1));install(store,worker,corrupt);
        check(atBody.get()>=corrupt.files.size()&&FakeModelTransport.requests.equals(Collections.singletonList("/first.bin")),"corrupt staged bytes cannot bypass pin checks");System.out.println("PASS corrupted preexisting stage is scanned and only its damaged file is fetched");

        Catalog.Model cancelled=ModelRepairCheck.model("fresh-cancel-retry",bytes);select(cancelled,bytes);FakeModelTransport.beforeBody=()->store.cancel(cancelled.id);store.download(cancelled.id);ModelRepairCheck.idle(worker);
        check(store.progress(cancelled.id).state.equals("Cancelled")&&!store.modelDirectory(cancelled.id).exists()&&stage(store,cancelled).isDirectory(),"cancelled new stage remains unavailable");
        select(cancelled,bytes);atBody=firstBodyChecks(new AtomicLong(-1));install(store,worker,cancelled);check(atBody.get()>=cancelled.files.size(),"retry of previously fresh stage must scan");System.out.println("PASS cancellation retires fresh classification; explicit retry scans the retained stage");

        Catalog.Model nondir=ModelRepairCheck.model("fresh-leaf-create-failure",bytes);select(nondir,bytes);Files.write(stage(store,nondir).toPath(),new byte[]{9});store.download(nondir.id);ModelRepairCheck.idle(worker);
        check(store.progress(nondir.id).state.equals("Download failed")&&FakeModelTransport.requests.isEmpty()&&!store.modelDirectory(nondir.id).exists(),"failed leaf creation cannot become fresh or activate");check(Arrays.equals(Files.readAllBytes(stage(store,nondir).toPath()),new byte[]{9}),"non-directory obstruction preserved");System.out.println("PASS failed staging leaf creation aborts without transfer or activation");

        Catalog.Model injected=ModelRepairCheck.model("fresh-path-injected",bytes);select(injected,bytes);AtomicBoolean once=new AtomicBoolean();File injectedPath=new File(stage(store,injected),"first.bin");
        FakeModelTransport.beforeBody=()->{if(once.compareAndSet(false,true))try{Files.write(injectedPath.toPath(),new byte[]{7});}catch(IOException failure){throw new UncheckedIOException(failure);}};
        store.download(injected.id);ModelRepairCheck.idle(worker);check(once.get()&&store.progress(injected.id).state.equals("Download failed"),"file injected after fresh classification causes exclusive write failure");check(!store.modelDirectory(injected.id).exists()&&!new File(stage(store,injected),"installed.manifest").exists()&&Arrays.equals(Files.readAllBytes(injectedPath.toPath()),new byte[]{7}),"injected file not trusted, clobbered or published");System.out.println("PASS expected-path injection after fresh creation cannot bypass CREATE_NEW or publish a receipt");
        FakeModelTransport.beforeBody=null;
        Catalog.Model parentWindow=ModelRepairCheck.model("fresh-http-parent-window",bytes);select(parentWindow,bytes);File blockedParent=new File(stage(store,parentWindow),"nested");
        FakeModelTransport.beforeResponse=()->{if(FakeModelTransport.requests.get(FakeModelTransport.requests.size()-1).equals("/nested/last.bin"))try{check(!blockedParent.exists(),"planned nested parent remains uncreated before HTTP response");Files.write(blockedParent.toPath(),new byte[]{5});}catch(IOException failure){throw new UncheckedIOException(failure);}};
        store.download(parentWindow.id);ModelRepairCheck.idle(worker);check(store.progress(parentWindow.id).state.equals("Download failed")&&!store.modelDirectory(parentWindow.id).exists()&&!new File(stage(store,parentWindow),"installed.manifest").exists(),"network-window parent obstruction cannot activate");check(Arrays.equals(Files.readAllBytes(blockedParent.toPath()),new byte[]{5}),"post-HTTP guard preserves nondirectory obstruction");System.out.println("PASS HTTP-response-window parent injection is rechecked before directory creation or write");
        Catalog.Model leafWindow=ModelRepairCheck.model("fresh-http-leaf-window",bytes);select(leafWindow,bytes);File windowLeaf=new File(stage(store,leafWindow),"first.bin");
        FakeModelTransport.beforeResponse=()->{if(!windowLeaf.exists())try{Files.write(windowLeaf.toPath(),new byte[]{6});}catch(IOException failure){throw new UncheckedIOException(failure);}};
        store.download(leafWindow.id);ModelRepairCheck.idle(worker);check(store.progress(leafWindow.id).state.equals("Download failed")&&!store.modelDirectory(leafWindow.id).exists()&&Arrays.equals(Files.readAllBytes(windowLeaf.toPath()),new byte[]{6}),"fresh HTTP-window leaf remains untrusted and unchanged");System.out.println("PASS HTTP-response-window unexpected fresh leaf cannot be replaced or advertised");
        FakeModelTransport.beforeResponse=null;
        Catalog.Model linked=ModelRepairCheck.model("fresh-symlink-leaf",bytes);select(linked,bytes);Path link=stage(store,linked).toPath();File target=new File(stage(store,linked).getParentFile(),"fresh-link-target");check(target.mkdir(),"link target fixture");
        try{
            try{Files.createSymbolicLink(link,target.toPath());}catch(UnsupportedOperationException|FileSystemException unavailable){System.out.println("SKIP fresh staging symlink creation: host privilege unavailable; actual Android no-follow gate required");return;}
            store.download(linked.id);ModelRepairCheck.idle(worker);check(store.progress(linked.id).state.equals("Download failed")&&FakeModelTransport.requests.isEmpty()&&!store.modelDirectory(linked.id).exists(),"symlink leaf cannot be classified or used as fresh storage");System.out.println("PASS staging symlink leaf rejected without transfer or activation");
        }finally{if(Files.isSymbolicLink(link))Files.delete(link);check(target.delete(),"remove only private empty link target");}
    }
}
