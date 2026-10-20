package org.localvoice.tts;
import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cold-start snapshot race: the UI renders before the first refresh, then a TTS binder thread inspects the model
 * first. The UI must show CHECKING (not "Not downloaded") and must be notified when the snapshot arrives.
 */
public final class InstalledSnapshotCheck {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    @org.junit.Test public void check() throws Exception {
        File root=Files.createTempDirectory("localvoice-snapshot-").toFile();
        Field work=ModelStore.class.getDeclaredField("WORK");work.setAccessible(true);ExecutorService executor=(ExecutorService)work.get(null);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            byte[] bytes="intact model".getBytes("UTF-8");
            File sample=new File(root,"sample");Files.write(sample.toPath(),bytes);String hash=ModelFiles.sha256(sample);check(sample.delete(),"sample removed");
            Catalog.Model model=new Catalog.Model(Collections.singletonList(new Catalog.ModelFile("model.bin",bytes.length,hash)));
            Catalog.MODELS=Collections.singletonList(model);
            File installed=new File(new File(root,"models"),"fixture-r1");check(installed.mkdirs(),"installed dir");
            Files.write(new File(installed,"model.bin").toPath(),bytes);ModelFiles.writeReceipt(installed,model.revision);
            // Hold the worker so the store's first refresh is queued but not yet run (slow startup work).
            executor.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}});check(entered.await(5,TimeUnit.SECONDS),"worker gated");
            ModelStore store=new ModelStore(new Context(root));AtomicInteger renders=new AtomicInteger();store.addListener(renders::incrementAndGet);
            check(!store.installedKnown(model.id),"snapshot pending");
            check(store.progress(model.id).state.equals(ModelStore.CHECKING),"unknown model is Checking, never Not downloaded");
            Handler.drain();renders.set(0);
            // A TTS binder thread asks first; it inspects synchronously and fills the snapshot.
            check(CompletableFuture.supplyAsync(()->store.isInstalled(model.id)).get(5,TimeUnit.SECONDS),"binder sees intact files");
            Handler.drain();
            check(renders.get()>=1,"snapshot filled off-main notifies the UI");
            check(store.isInstalled(model.id)&&store.progress(model.id).state.equals("Installed"),"UI re-render reads Installed");
            release.countDown();executor.submit(()->{}).get(10,TimeUnit.SECONDS);Handler.drain();
            check(store.isInstalled(model.id),"refresh keeps Installed");
            System.out.println("PASS cold-start snapshot: Checking, then off-main fill notifies listeners");
        }finally{release.countDown();executor.shutdown();executor.awaitTermination(5,TimeUnit.SECONDS);ModelFiles.remove(root);}
    }
}
