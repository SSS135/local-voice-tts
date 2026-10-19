package org.localvoice.tts;

import android.content.Context;
import android.content.Intent;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.localvoice.tts.backend.EdgeCatalogClient;
import org.localvoice.tts.backend.EdgeCatalogData;
import org.localvoice.tts.backend.SpeechBackend;

/** Startup reads only local metadata; refresh() is an explicit user action on a worker thread. */
public final class EdgeCatalogRepository {
    private static CompletableFuture<EdgeCatalogRepository> instance;
    interface Bundle { InputStream open() throws IOException; }
    private final Runnable changed;
    private final Supplier<EdgeCatalogClient> clients;
    private final File cache;
    private final Object publication=new Object();
    private final AtomicBoolean refreshing=new AtomicBoolean();
    private volatile EdgeCatalogClient active;
    private volatile boolean cancelled;
    private volatile EdgeCatalogData.Snapshot snapshot;
    private volatile String cacheWarning="";

    public static EdgeCatalogRepository get(Context context) { return initialize(context).join(); }
    /** Starts only local disk parsing, always on a dedicated worker, even when called from service onCreate. */
    public static synchronized CompletableFuture<EdgeCatalogRepository> initialize(Context context) {
        if(instance==null) {
            instance=new CompletableFuture<>();Context app=context.getApplicationContext();
            Thread loader=new Thread(()->{
                try { instance.complete(new EdgeCatalogRepository(app)); }
                catch(Throwable failed) { instance.completeExceptionally(failed); }
            },"EdgeCatalog-local");
            loader.start();
        }
        return instance;
    }
    private EdgeCatalogRepository(Context context) {
        this(new File(context.getNoBackupFilesDir(),"edge-catalog.json"),
            ()->context.getAssets().open("edge-voices-snapshot.json"),EdgeCatalogClient::new,
            ()->context.sendBroadcast(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)));
    }
    /** Package-private integration seam: production has no endpoint preference or external override. */
    EdgeCatalogRepository(File cache,Bundle bundle,Supplier<EdgeCatalogClient> clients,Runnable changed) {
        this.cache=cache;this.changed=changed;this.clients=clients;
        try { snapshot=EdgeCatalogData.load(cache,()->false); }
        catch(IOException badCache) {
            if(cache.exists()) cacheWarning="Saved catalog is damaged; showing bundled snapshot";
            try(InputStream input=bundle.open()) {
                snapshot=EdgeCatalogData.load(input,"bundled",()->false);
            } catch(IOException badBundle) { throw new IllegalStateException("Bundled Edge catalog is invalid",badBundle); }
        }
        Catalog.replaceEdgeVoices(snapshot);
    }
    public EdgeCatalogData.Snapshot snapshot() { return snapshot; }
    public String cacheWarning() { return cacheWarning; }
    public boolean isRefreshing() { return refreshing.get(); }
    public void cancelRefresh() {
        synchronized(publication) { cancelled=true; }
        EdgeCatalogClient current=active;if(current!=null) current.cancel();
    }
    public EdgeCatalogData.Snapshot refresh() throws IOException {
        return refresh(()->false);
    }
    /** The token must be created before queuing the UI worker, so queued cancellation is retained. */
    public EdgeCatalogData.Snapshot refresh(SpeechBackend.Cancellation external) throws IOException {
        if(external.isCancelled()) throw new InterruptedIOException("Edge catalog refresh cancelled");
        EdgeCatalogClient client=clients.get();
        synchronized(publication) {
            if(!refreshing.compareAndSet(false,true)) throw new IOException("Edge catalog refresh already running");
            cancelled=false;active=client;
        }
        SpeechBackend.Cancellation stopped=()->cancelled || external.isCancelled();
        try {
            if(stopped.isCancelled()) client.cancel();
            String raw=client.fetch();
            EdgeCatalogData.Snapshot next=EdgeCatalogData.parse(raw,System.currentTimeMillis(),"network",stopped);
            synchronized(publication) {
                if(stopped.isCancelled()) throw new InterruptedIOException("Edge catalog refresh cancelled");
                EdgeCatalogData.save(cache,next,stopped);
                Catalog.replaceEdgeVoices(next);snapshot=next;cacheWarning="";
            }
            changed.run();
            return next;
        } finally { synchronized(publication) { active=null;refreshing.set(false); } }
    }
}
