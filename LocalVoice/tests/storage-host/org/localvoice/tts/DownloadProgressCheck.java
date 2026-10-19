package org.localvoice.tts;

import android.os.Handler;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual Store phase publication with drained main callbacks and bounded fake HTTPS. */
final class DownloadProgressCheck {
    static final String PHASE="Downloading and checking";
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static Catalog.Model select(String id,int count,byte[] bytes)throws Exception{
        List<Catalog.ModelFile> files=new ArrayList<>();String pin=StorageStoreCheck.hash(bytes);FakeModelTransport.reset(bytes);FakeModelTransport.payloads.clear();
        for(int i=0;i<count;i++){String path="tiny-"+i+".bin";files.add(new Catalog.ModelFile(path,bytes.length,pin));FakeModelTransport.payloads.put("/"+path,bytes);}
        Catalog.Model model=new Catalog.Model(id,false,files);Catalog.MODELS=Collections.singletonList(model);return model;
    }
    static void aggregate(ModelStore.Progress p,Catalog.Model m,long previous){check(p.running&&p.state.equals(PHASE),"coherent running job phase");check(p.bytes==p.phaseBytes&&p.phaseTotal==m.bytes(),"whole-job byte boundaries match");check(p.bytes>=previous&&p.bytes<=m.bytes(),"monotonic downloaded bytes without hash double-count");}
    static void run(ModelStore store,ExecutorService worker)throws Exception{
        byte[] tiny=new byte[128];new Random(115).nextBytes(tiny);Catalog.Model many=select("progress-many-tiny",40,tiny);AtomicInteger bodies=new AtomicInteger(),coarseCallbacks=new AtomicInteger();AtomicLong previous=new AtomicLong();
        ModelStore.Listener listener=()->{ModelStore.Progress p=store.progress(many.id);if(p.state.equals(PHASE)){aggregate(p,many,previous.get());previous.set(p.bytes);coarseCallbacks.incrementAndGet();}};store.addListener(listener);
        FakeModelTransport.beforeBody=()->{Handler.drain();aggregate(store.progress(many.id),many,previous.get());check(coarseCallbacks.get()>0,"first forced phase delivered before first body");bodies.incrementAndGet();};
        StorageMetrics.start();long start=System.nanoTime();store.download(many.id);ModelRepairCheck.idle(worker);long elapsed=System.nanoTime()-start,writes=StorageMetrics.PROGRESS_WRITES.value.get();StorageMetrics.finish();store.removeListener(listener);
        check(bodies.get()==40&&coarseCallbacks.get()<=1+elapsed/200_000_000L,"tiny files cannot force a phase burst per file");check(writes<=8+elapsed/200_000_000L,"total publications retain only bounded forced job boundaries");ModelRepairCheck.verifyPublished(store,many);System.out.println("PASS 40 tiny files keep monotonic aggregate phase, first immediate, callbacks="+coarseCallbacks.get()+", writes="+writes);

        for(int blockedAt:new int[]{1,2}){
            Catalog.Model stalled=select("progress-stall-"+blockedAt,3,tiny);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger bodyIndex=new AtomicInteger();
            FakeModelTransport.beforeBody=()->{Handler.drain();if(bodyIndex.incrementAndGet()==blockedAt){entered.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}}};
            store.download(stalled.id);check(entered.await(5,TimeUnit.SECONDS),"first/later HTTP input stalled");try{ModelStore.Progress pending=store.progress(stalled.id);aggregate(pending,stalled,0);long cancelStart=System.nanoTime();store.cancel(stalled.id);check(System.nanoTime()-cancelStart<1_000_000_000L,"cancel call does not wait for stalled input");}finally{release.countDown();ModelRepairCheck.idle(worker);}
            check(store.progress(stalled.id).state.equals("Cancelled")&&!store.isInstalled(stalled.id),"cancelled stall has immediate terminal after worker exits, no activation");
            // Retry retained staged files: accepted bytes and new bytes share one monotonic job total.
            FakeModelTransport.reset(tiny);FakeModelTransport.payloads.clear();for(Catalog.ModelFile file:stalled.files)FakeModelTransport.payloads.put("/"+file.path,tiny);AtomicLong last=new AtomicLong();FakeModelTransport.beforeBody=()->{Handler.drain();ModelStore.Progress p=store.progress(stalled.id);aggregate(p,stalled,last.get());last.set(p.bytes);};
            store.download(stalled.id);ModelRepairCheck.idle(worker);ModelRepairCheck.verifyPublished(store,stalled);System.out.println("PASS stalled HTTP body "+blockedAt+" keeps truthful aggregate phase, prompt cancel, and checked retry");
        }
        Catalog.Model error=select("progress-http-error",1,tiny);FakeModelTransport.responseCode=503;store.download(error.id);ModelRepairCheck.idle(worker);check(store.progress(error.id).state.equals("Download failed")&&!store.progress(error.id).running&&!store.isInstalled(error.id),"HTTP error is terminal without phase-throttle delay");System.out.println("PASS HTTP failure bypasses ordinary phase throttle and cannot activate");FakeModelTransport.reset(tiny);
    }
}
