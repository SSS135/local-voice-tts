package org.localvoice.tts;
import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual ModelStore control flow with deterministic Android boundaries; not an Android runtime test. */
public final class StorageStoreCheck {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static void idle(ExecutorService executor)throws Exception{executor.submit(()->{}).get(10,TimeUnit.SECONDS);}
    static String hash(byte[] bytes)throws Exception{StringBuilder hex=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("localvoice-store-").toFile();ExecutorService executor=null;
        byte[] bytes=new byte[256*1024];new Random(7).nextBytes(bytes);
        try{
            File sample=new File(root,"sample");Files.write(sample.toPath(),bytes);String hash=ModelFiles.sha256(sample);check(sample.delete(),"sample removed");
            Catalog.Model model=new Catalog.Model(Arrays.asList(new Catalog.ModelFile("first.bin",bytes.length,hash),new Catalog.ModelFile("nested/last.bin",bytes.length,hash)));
            Catalog.MODELS=Collections.singletonList(model);
            Context context=new Context(root);ModelStore store=new ModelStore(context);Field work=ModelStore.class.getDeclaredField("WORK");work.setAccessible(true);executor=(ExecutorService)work.get(null);idle(executor);Handler.drain();
            check(EdgeCatalogRepository.initialized==1,"offline Edge bootstrap starts in constructor");
            StorageMetrics.start();for(int i=0;i<100;i++){check(!store.isInstalled(model.id),"empty snapshot");store.progress(model.id);}
            check(StorageMetrics.CANONICAL_CALLS.value.get()==0&&StorageMetrics.FILE_STATS.value.get()==0&&StorageMetrics.RECEIPT_READS.value.get()==0,"main polling performs no file IO");
            System.out.println("PASS 100 main polls: "+StorageMetrics.finish());
            File staging=new File(new File(root,"models"),"fixture-r1.download");check(staging.mkdirs(),"staging");
            for(Catalog.ModelFile file:model.files){File local=ModelFiles.child(staging,file.path);local.getParentFile().mkdirs();Files.write(local.toPath(),bytes);}
            AtomicInteger callbacks=new AtomicInteger();store.addListener(callbacks::incrementAndGet);
            StorageMetrics.start();store.download(model.id);idle(executor);Handler.drain();
            check(store.progress(model.id).state.equals("Installed"),"cached retry finishes");check(ModelFiles.receiptMatches(store.modelDirectory(model.id),model.revision),"only complete model activated");
            check(callbacks.get()<=2,"queued notifications coalesced");check(StorageMetrics.DOWNLOAD_BYTES.value.get()==0,"fully cached retry uses no network transfer");
            System.out.println("PASS actual cached retry + coalesced callbacks: "+StorageMetrics.finish());
            check(!staging.exists(),"whole directory atomically moved");
            File first=ModelFiles.child(store.modelDirectory(model.id),"first.bin");long mtime=first.lastModified();try(RandomAccessFile corrupt=new RandomAccessFile(first,"rw")){corrupt.write(42);}check(first.setLastModified(mtime),"restored timestamp");
            AtomicBoolean rejected=new AtomicBoolean();executor.submit(()->{try{store.verifyInstalled(model.id);}catch(IOException expected){rejected.set(true);}}).get(10,TimeUnit.SECONDS);Handler.drain();
            check(rejected.get(),"damaged file rejected before inference");check(!store.isInstalled(model.id),"damaged snapshot invalidated");check(!ModelFiles.receiptMatches(store.modelDirectory(model.id),model.revision),"damaged receipt removed");
            System.out.println("PASS actual inference validation rejects same-size restored-time corruption");
            store.remove(model.id,false);idle(executor);Handler.drain();check(!store.modelDirectory(model.id).exists(),"serialized removal complete");
            // Hold the single worker so cancellation happens after queuing but before any validation/publication.
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);executor.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}});check(entered.await(5,TimeUnit.SECONDS),"worker gated");
            store.download(model.id);store.cancel(model.id);release.countDown();idle(executor);Handler.drain();
            check(store.progress(model.id).state.equals("Cancelled"),"queued cancel terminal");check(!store.modelDirectory(model.id).exists(),"cancelled job never activates");
            System.out.println("PASS queued cancellation and removal cannot publish stale installation");
            Class<?> jobType=Class.forName("org.localvoice.tts.ModelStore$Job");Constructor<?> constructor=jobType.getDeclaredConstructor();constructor.setAccessible(true);Object job=constructor.newInstance();
            Method phase=ModelStore.class.getDeclaredMethod("phase",Catalog.Model.class,jobType,String.class,String.class,long.class,long.class,long.class,int.class,boolean.class);phase.setAccessible(true);
            StorageMetrics.start();long phaseStart=System.nanoTime();
            for(int i=0;i<5000;i++){phase.invoke(null,model,job,"Verifying","first.bin",0L,(long)i,5000L,0,false);Handler.drain();}
            long elapsed=System.nanoTime()-phaseStart,posts=StorageMetrics.CHANGE_POSTS.value.get();
            check(posts<=1+elapsed/200_000_000L,"phase emits at most five updates/sec even with immediately drained main handler");
            phase.invoke(null,model,job,"Finalizing","",0L,5000L,5000L,2,true);Handler.drain();
            check(store.progress(model.id).phaseBytes==5000&&store.progress(model.id).filesDone==2,"phase counters reach UI intact");
            System.out.println("PASS 5000 phase writes, throttled notifications="+posts+", terminal immediate: "+StorageMetrics.finish());
            StorageMetrics.start();long alternatingStart=System.nanoTime();
            for(int i=0;i<5000;i++){phase.invoke(null,model,job,(i&1)==0?"Downloading":"Verifying","first.bin",0L,(long)i,5000L,0,false);Handler.drain();}
            long alternatingElapsed=System.nanoTime()-alternatingStart,alternatingPosts=StorageMetrics.CHANGE_POSTS.value.get();
            check(alternatingPosts<=1+alternatingElapsed/200_000_000L,"alternating ordinary states cannot bypass five updates/sec");
            phase.invoke(null,model,job,"Finalizing","",0L,6000L,6000L,2,true);Handler.drain();
            check(store.progress(model.id).state.equals("Finalizing")&&store.progress(model.id).phaseBytes==6000&&StorageMetrics.PROGRESS_WRITES.value.get()>=1,"changed forced boundary survives alternating throttle");
            System.out.println("PASS 5000 alternating download/verify updates remain coalesced and forced boundary immediate: "+StorageMetrics.finish());
            Catalog.Model online=new Catalog.Model("edge",true,Collections.emptyList());Catalog.MODELS=Arrays.asList(model,online);store.enableOnline(online.id);Handler.drain();check(store.isInstalled(online.id),"explicit online consent enabled");
            CountDownLatch busy=new CountDownLatch(1),unblock=new CountDownLatch(1);executor.execute(()->{busy.countDown();try{unblock.await();}catch(InterruptedException ignored){}});check(busy.await(5,TimeUnit.SECONDS),"shared worker occupied");
            try{
                store.remove(online.id,false);check(!store.isInstalled(online.id),"disable revokes consent before shared worker is released");
                for(Catalog.VoiceSpec voice:store.installedVoices())check(!voice.modelId.equals(online.id),"disabled online voices immediately absent");
                System.out.println("PASS online consent/discovery revocation bypasses occupied storage worker");
            }finally{unblock.countDown();idle(executor);Handler.drain();}
            byte[] manifest="pinned manifest fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);File pack=new File(root,"fixture.zip");
            try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(pack))){zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry();zip.putNextEntry(new java.util.zip.ZipEntry("weight.bin"));zip.write(bytes);zip.closeEntry();}
            Catalog.Model imported=new Catalog.Model("import-fixture",false,Collections.singletonList(new Catalog.ModelFile("weight.bin",bytes.length,hash(bytes))));
            imported.config.put("installMode","import");imported.config.put("importArchiveBytes",Long.toString(pack.length()));imported.config.put("importArchiveSha256",ModelFiles.sha256(pack));imported.config.put("importManifestBytes",Integer.toString(manifest.length));imported.config.put("importManifestSha256",hash(manifest));Catalog.MODELS=Arrays.asList(model,online,imported);
            android.net.Uri uri=android.net.Uri.fromFile(pack);android.content.ContentResolver resolver=context.getContentResolver();
            CountDownLatch importing=new CountDownLatch(1),allowImport=new CountDownLatch(1);executor.execute(()->{importing.countDown();try{allowImport.await();}catch(InterruptedException ignored){}});check(importing.await(5,TimeUnit.SECONDS),"import worker gated");
            try{store.importModel(imported.id,uri);check(resolver.takes==0&&resolver.getPersistedUriPermissions().isEmpty(),"grant/journal work stays off caller thread while queued");store.cancel(imported.id);}finally{allowImport.countDown();idle(executor);Handler.drain();}
            check(resolver.takes==0&&resolver.releases==0&&resolver.getPersistedUriPermissions().isEmpty(),"queued cancellation avoids acquiring URI");check(!store.modelDirectory(imported.id).exists(),"queued import never activates");
            store.importModel(imported.id,uri);idle(executor);Handler.drain();check(store.progress(imported.id).state.equals("Installed"),"valid import installed");
            check(ModelFiles.sha256(new File(store.modelDirectory(imported.id),"weight.bin")).equals(hash(bytes)),"imported runtime exact");check(ModelFiles.receiptMatches(store.modelDirectory(imported.id),imported.revision),"import receipt published");check(!new File(store.modelDirectory(imported.id),".import-archive.zip").exists(),"compressed spool never activated");
            check(resolver.takes==1&&resolver.releases==1&&resolver.getPersistedUriPermissions().isEmpty(),"successful import releases its grant");
            resolver.takePersistableUriPermission(uri,1);int existingTakes=resolver.takes,existingReleases=resolver.releases;
            store.importModel(imported.id,uri);idle(executor);Handler.drain();check(resolver.takes==existingTakes&&resolver.releases==existingReleases&&resolver.getPersistedUriPermissions().size()==1,"preexisting external grant preserved");resolver.releasePersistableUriPermission(uri,1);
            System.out.println("PASS actual import atomic activation, queued cancellation, owned URI release and preexisting grant preservation");
            store.remove(imported.id,false);idle(executor);Handler.drain();int opens=resolver.opens;android.os.StatFs.available=0;
            try{store.importModel(imported.id,uri);idle(executor);Handler.drain();check(store.progress(imported.id).state.equals("Import failed"),"full storage rejected");check(resolver.opens==opens,"storage checked before reading selected archive");check(!store.modelDirectory(imported.id).exists(),"full storage cannot activate");check(resolver.getPersistedUriPermissions().isEmpty(),"full storage releases grant");}finally{android.os.StatFs.available=Long.MAX_VALUE;}
            try(RandomAccessFile corrupt=new RandomAccessFile(pack,"rw")){corrupt.seek(0);corrupt.write(0);}
            store.importModel(imported.id,uri);idle(executor);Handler.drain();check(store.progress(imported.id).state.equals("Import failed"),"bad pinned archive fails");check(!store.modelDirectory(imported.id).exists(),"bad archive never published");check(resolver.getPersistedUriPermissions().isEmpty(),"failure releases grant");
            System.out.println("PASS actual import full-storage and corrupt-package failure preserve atomic publication and grant cleanup");
            check(android.content.SharedPreferences.mainCommits==0,"production URI journal never commits on main thread");
            android.net.Uri stale=android.net.Uri.fromFile(new File(root,"old-picker.zip")),external=android.net.Uri.fromFile(new File(root,"external-picker.zip"));resolver.takePersistableUriPermission(stale,1);resolver.takePersistableUriPermission(external,1);
            context.getSharedPreferences("model-import-grants",0).edit().putStringSet("owned",Collections.singleton(stale.toString())).apply();
            new ModelStore(context);idle(executor);Handler.drain();boolean keptExternal=false,keptStale=false;for(android.content.UriPermission grant:resolver.getPersistedUriPermissions()){keptExternal|=grant.getUri().equals(external);keptStale|=grant.getUri().equals(stale);}
            check(keptExternal&&!keptStale,"startup removes only journal-owned stale grant");check(android.content.SharedPreferences.mainCommits==0,"startup cleanup stays off main");resolver.releasePersistableUriPermission(external,1);
            System.out.println("PASS startup reconciles app-owned stale URI grants off main while preserving external grants");
            ModelRepairCheck.run(store,executor,bytes);
            FreshStageCheck.run(store,executor,bytes);
            DownloadProgressCheck.run(store,executor);
        }finally{StorageMetrics.finish();if(executor!=null){executor.shutdown();executor.awaitTermination(5,TimeUnit.SECONDS);}Field cancelIo=ModelStore.class.getDeclaredField("CANCEL_IO");cancelIo.setAccessible(true);ExecutorService io=(ExecutorService)cancelIo.get(null);io.shutdown();check(io.awaitTermination(5,TimeUnit.SECONDS),"cancel I/O fixture drained");ModelFiles.remove(root);}
    }
}
