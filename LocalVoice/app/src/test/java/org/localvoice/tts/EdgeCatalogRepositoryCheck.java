package org.localvoice.tts;

import org.localvoice.tts.backend.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises the actual repository and actual OkHttp calls against a local HTTP fixture. */
public final class EdgeCatalogRepositoryCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        byte[] bundle=Files.readAllBytes(Path.of(args[0]));
        String envelope=new String(bundle,java.nio.charset.StandardCharsets.UTF_8);
        byte[] raw=new org.json.JSONObject(envelope).getJSONArray("voices").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AtomicInteger requests=new AtomicInteger(),mode=new AtomicInteger(),changes=new AtomicInteger();
        CountDownLatch active=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        ServerSocket server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0));
        ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"catalog-http-fixture");t.setDaemon(true);return t;});
        workers.execute(()->{
            while(!server.isClosed()) try {
                Socket socket=server.accept();workers.execute(()->{
                    try(Socket connection=socket) {
                        connection.setSoTimeout(3000);BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));
                        String line;while((line=reader.readLine())!=null && !line.isEmpty()){}
                        requests.incrementAndGet();int action=mode.get();
                        byte[] bytes=action==1?Arrays.copyOf(raw,raw.length/2):raw;OutputStream output=connection.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        if(action==2) {
                            output.write('[');output.flush();active.countDown();
                            try{for(int i=0;i<100;i++){Thread.sleep(30);output.write(' ');output.flush();}}
                            catch(IOException closed){disconnected.countDown();}catch(InterruptedException stop){Thread.currentThread().interrupt();}
                        } else {output.write(bytes);output.flush();}
                    } catch(IOException ignored) {}
                });
            } catch(IOException closed) {if(!server.isClosed())throw new UncheckedIOException(closed);}
        });
        String endpoint="http://127.0.0.1:"+server.getLocalPort()+"/voices";
        Path directory=Files.createTempDirectory(Path.of(args[1]),"edge-repository-check-");File cache=directory.resolve("catalog.json").toFile();
        EdgeCatalogRepository repository=new EdgeCatalogRepository(cache,()->new ByteArrayInputStream(bundle),()->new EdgeCatalogClient(endpoint,3000),changes::incrementAndGet);
        int passed=0;
        try {
            check(requests.get()==0 && repository.snapshot().voices.size()==322,"offline construction made HTTP/lost bundled catalog");passed++;System.out.println("PASS offline repository construction makes no HTTP request");
            try {repository.refresh(()->true);throw new AssertionError("queued cancellation refreshed");}catch(InterruptedIOException expected){}
            check(requests.get()==0 && !cache.exists(),"cancel before worker issued HTTP/cache write");passed++;System.out.println("PASS queued cancellation opens no HTTP request and changes no cache");
            repository.refresh();byte[] good=Files.readAllBytes(cache.toPath());long date=repository.snapshot().updatedAtMillis;
            check(requests.get()==1 && changes.get()==1 && repository.snapshot().voices.size()==322,"full refresh was not committed once");passed++;System.out.println("PASS valid HTTP refresh atomically publishes all322 voices");
            EdgeBackend configured=new EdgeBackend();
            for(Catalog.VoiceSpec voice:Catalog.findModel("edge").voices) configured.load(directory.toFile(),voice.config);
            java.util.Map<String,String> invalid=new java.util.HashMap<>(Catalog.findModel("edge").voices.get(0).config);invalid.put("locale","en-US");
            try{configured.load(directory.toFile(),invalid);throw new AssertionError("mismatched backend config accepted");}catch(EdgeRetry.FatalException expected){}
            configured.close();check(requests.get()==1,"backend config validation issued HTTP");passed++;System.out.println("PASS actual backend accepts all322 configs and rejects mismatched locale without HTTP");
            mode.set(1);
            try{repository.refresh();throw new AssertionError("truncated refresh accepted");}catch(IOException expected){}
            check(Arrays.equals(good,Files.readAllBytes(cache.toPath())) && repository.snapshot().updatedAtMillis==date && changes.get()==1,"failed refresh replaced good snapshot");passed++;System.out.println("PASS truncated HTTP response retains previous cache, date, and full catalog");
            mode.set(2);AtomicBoolean stop=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
            Thread refresh=new Thread(()->{try{repository.refresh(stop::get);failure.set(new AssertionError("cancelled HTTP committed"));}catch(IOException expected){}catch(Throwable unexpected){failure.set(unexpected);}finally{done.countDown();}},"catalog-refresh-test");refresh.setDaemon(true);refresh.start();
            check(active.await(3,TimeUnit.SECONDS),"active HTTP fixture did not start");long started=System.nanoTime();stop.set(true);repository.cancelRefresh();
            check(done.await(2,TimeUnit.SECONDS),"active HTTP cancellation did not unblock");
            check(disconnected.await(2,TimeUnit.SECONDS),"active HTTP socket remained open");
            check(failure.get()==null && Arrays.equals(good,Files.readAllBytes(cache.toPath())) && changes.get()==1,"cancelled refresh mutated cache");
            passed++;System.out.println("PASS active HTTP cancellation closes socket, unblocks worker, preserves cache; ms="+(System.nanoTime()-started)/1000000);
            EdgeCatalogRepository offline=new EdgeCatalogRepository(cache,()->{throw new IOException("no bundle needed");},()->{throw new AssertionError("offline requested client");},()->{});
            check(offline.snapshot().source.equals("cache") && offline.snapshot().voices.size()==322 && offline.snapshot().updatedAtMillis==date,"offline restart lost cached voices");passed++;System.out.println("PASS offline restart loads complete refreshed cache with its original date");
            Files.writeString(cache.toPath(),"corrupt");EdgeCatalogRepository recovered=new EdgeCatalogRepository(cache,()->new ByteArrayInputStream(bundle),()->{throw new AssertionError("corrupt cache triggered HTTP");},()->{});
            check(recovered.snapshot().source.equals("bundled") && recovered.snapshot().voices.size()==322 && !recovered.cacheWarning().isEmpty(),"corrupt cache did not disclose bundled fallback");passed++;System.out.println("PASS corrupt cache uses dated bundled snapshot with warning and no HTTP");
            System.out.println("PASS: "+passed+" actual repository/HTTP checks");
        } finally {server.close();workers.shutdownNow();}
        System.exit(0);
    }
}
