package org.localvoice.tts;

import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual private publication/cancel methods with bounded test I/O gates, no socket or APK. */
public final class StoragePublicationCheck {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    static int passed;
    static final byte[] DATA=new byte[128*1024];
    static final Class<?> JOB;
    static final Constructor<?> JOB_CTOR;
    static final Method PUBLISH;
    static {try{JOB=Class.forName("org.localvoice.tts.ModelStore$Job");JOB_CTOR=JOB.getDeclaredConstructor();JOB_CTOR.setAccessible(true);PUBLISH=ModelInstaller.class.getDeclaredMethod("publishInstallation",Catalog.Model.class,JOB,File.class);PUBLISH.setAccessible(true);}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    interface Action {void run()throws Exception;}
    static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void test(String name,Action action)throws Exception{action.run();passed++;System.out.println("PASS "+name);}
    static Object value(Class<?> owner,Object target,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static Object job(Catalog.Model model)throws Exception{Object result=JOB_CTOR.newInstance();@SuppressWarnings("unchecked") Map<String,Object> jobs=(Map<String,Object>)value(ModelStore.class,null,"JOBS");jobs.put(model.id,result);return result;}
    static boolean flag(Object job,String name)throws Exception{return (Boolean)value(JOB,job,name);}
    static Catalog.Model model(String id)throws Exception{Catalog.Model model=new Catalog.Model(id,false,Collections.singletonList(new Catalog.ModelFile("weight.bin",DATA.length,StorageStoreCheck.hash(DATA))));Catalog.MODELS=Collections.singletonList(model);return model;}
    static File seed(ModelStore store,Catalog.Model model)throws Exception{File stage=new File(store.modelDirectory(model.id).getPath()+".download");require(stage.mkdirs(),"fresh stage");Files.write(new File(stage,"weight.bin").toPath(),DATA);return stage;}
    static boolean publish(ModelStore store,Catalog.Model model,Object job,File stage)throws Exception{try{return (Boolean)PUBLISH.invoke(value(ModelStore.class,store,"installer"),model,job,stage);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}}
    static final class GatedCanonicalFile extends File {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean once=new AtomicBoolean();
        GatedCanonicalFile(File file){super(file.getPath());}
        @Override public String getCanonicalPath()throws IOException{if(once.compareAndSet(false,true)){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Receipt path gate timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException();}}return super.getCanonicalPath();}
    }
    static final class GatedRenameFile extends File {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        GatedRenameFile(File file){super(file.getPath());}
        @Override public boolean renameTo(File destination){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))return false;}catch(InterruptedException error){Thread.currentThread().interrupt();return false;}return super.renameTo(destination);}
    }
    public static void main(String[] args)throws Exception{
        new Random(111).nextBytes(DATA);File root=Files.createTempDirectory("localvoice-publish-").toFile().getCanonicalFile();ExecutorService helpers=Executors.newCachedThreadPool(),work=null,cancelIo=null;
        try{
            Catalog.MODELS=Collections.emptyList();ModelStore store=new ModelStore(new Context(root));work=(ExecutorService)value(ModelStore.class,null,"WORK");cancelIo=(ExecutorService)value(ModelStore.class,null,"CANCEL_IO");work.submit(()->{}).get(3,TimeUnit.SECONDS);Handler.drain();
            test("cancel returns while receipt-path I/O is blocked and prevents activation",()->{
                Catalog.Model model=model("receipt-block");File stage=seed(store,model);Object job=job(model);GatedCanonicalFile gate=new GatedCanonicalFile(stage);
                Future<Throwable> publishing=helpers.submit(()->{try{publish(store,model,job,gate);return null;}catch(Throwable error){return error;}});
                require(gate.entered.await(2,TimeUnit.SECONDS),"receipt path I/O entered");
                try{Future<?> cancelling=helpers.submit(()->store.cancel(model.id));cancelling.get(1,TimeUnit.SECONDS);require(flag(job,"cancelled")&&!flag(job,"activated"),"cancel accepted before receipt path released");}finally{gate.release.countDown();}
                require(publishing.get(3,TimeUnit.SECONDS) instanceof InterruptedIOException,"cancelled publication stops before activation");
                require(!store.modelDirectory(model.id).exists()&&!store.isInstalled(model.id),"accepted cancellation cannot advertise ready");
                require(stage.isDirectory(),"unadvertised reusable stage retained");
            });
            test("atomic rename winning the monitor cannot become Cancelled afterward",()->{
                Catalog.Model model=model("rename-wins");File stage=seed(store,model);Object job=job(model);GatedRenameFile gate=new GatedRenameFile(stage);
                Future<Boolean> publishing=helpers.submit(()->publish(store,model,job,gate));require(gate.entered.await(2,TimeUnit.SECONDS),"atomic rename entered");
                CountDownLatch cancelStarted=new CountDownLatch(1),cancelReturned=new CountDownLatch(1);
                Future<?> cancelling=helpers.submit(()->{cancelStarted.countDown();store.cancel(model.id);cancelReturned.countDown();});
                try{require(cancelStarted.await(1,TimeUnit.SECONDS),"cancel contender started");require(!cancelReturned.await(100,TimeUnit.MILLISECONDS),"cancel cannot interleave inside atomic rename/commit");}finally{gate.release.countDown();}
                require(publishing.get(3,TimeUnit.SECONDS),"publication committed");cancelling.get(2,TimeUnit.SECONDS);
                require(flag(job,"activated")&&!flag(job,"cancelled"),"commit wins without cancelled terminal");
                require(ModelFiles.receiptMatches(store.modelDirectory(model.id),model.revision)&&store.isInstalled(model.id),"committed receipt remains valid/ready");
            });
            final ExecutorService io=cancelIo;
            test("blocked connection disconnect is asynchronous and repeated cancel queues once",()->{
                Catalog.Model model=model("close-block");Object job=job(model);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger closes=new AtomicInteger();
                set(job,"connection",new java.net.HttpURLConnection(new java.net.URL("https://example.invalid/close-block")){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closes.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Close gate timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();}}});
                try{helpers.submit(()->store.cancel(model.id)).get(1,TimeUnit.SECONDS);require(entered.await(2,TimeUnit.SECONDS),"asynchronous close entered");helpers.submit(()->store.cancel(model.id)).get(1,TimeUnit.SECONDS);require(flag(job,"cancelled")&&!flag(job,"activated"),"cancel state established promptly");}finally{release.countDown();}
                io.submit(()->{}).get(3,TimeUnit.SECONDS);require(closes.get()==1,"repeated cancel does not queue duplicate closes");
            });
            test("recursive removal checks cancellation instead of deleting the entire tree",()->{
                File tree=new File(root,"cleanup");require(tree.mkdir(),"cleanup fixture");for(int i=0;i<4;i++)Files.write(new File(tree,"file"+i).toPath(),DATA);AtomicInteger checks=new AtomicInteger();
                try{ModelFiles.remove(tree,()->checks.incrementAndGet()>2);throw new AssertionError("Expected interrupted cleanup");}catch(InterruptedIOException expected){}
                require(tree.isDirectory()&&tree.list().length>0,"cleanup abort leaves remaining unadvertised files");
            });
            System.out.println("Storage publication checks: "+passed+" PASS; bounded host gates are not Android fsync latency measurements");
        }finally{
            helpers.shutdownNow();require(helpers.awaitTermination(6,TimeUnit.SECONDS),"test helpers drained");
            if(work!=null){work.shutdown();require(work.awaitTermination(6,TimeUnit.SECONDS),"WORK drained");}
            if(cancelIo!=null){cancelIo.shutdown();require(cancelIo.awaitTermination(6,TimeUnit.SECONDS),"CANCEL_IO drained");}
            ModelFiles.remove(root);
        }
    }
}
