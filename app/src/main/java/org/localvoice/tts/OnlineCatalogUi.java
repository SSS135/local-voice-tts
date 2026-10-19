package org.localvoice.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.text.DateFormat;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.localvoice.tts.backend.EdgeCatalogData;

/** Retains refresh feedback across rotation; never owns an Activity or synthesis text. */
final class OnlineCatalogUi {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Set<Runnable> LISTENERS=new CopyOnWriteArraySet<>();
    private static volatile EdgeCatalogRepository repository;
    private static volatile String status="Loading catalog";
    private static volatile boolean refreshing;
    private static volatile AtomicBoolean refreshCancellation;
    static void addListener(Runnable listener){LISTENERS.add(listener);}
    static void removeListener(Runnable listener){LISTENERS.remove(listener);}
    static String status(){return status;}
    static boolean refreshing(){return refreshing;}
    private static void notifyChanged(){MAIN.post(()->{for(Runnable listener:LISTENERS)listener.run();});}
    static void initialize(Context context,ExecutorService worker){
        Context app=context.getApplicationContext();
        worker.execute(()->{
            try{repository=EdgeCatalogRepository.get(app);if(!refreshing)status=snapshotStatus(repository.snapshot());}
            catch(RuntimeException error){status="Catalog unavailable: "+error.getMessage();}
            notifyChanged();
        });
    }
    static synchronized void refresh(Context context,ExecutorService worker){
        if(refreshing)return;refreshing=true;AtomicBoolean cancellation=new AtomicBoolean();refreshCancellation=cancellation;status="Refreshing voices";notifyChanged();Context app=context.getApplicationContext();
        worker.execute(()->{
            try{repository=EdgeCatalogRepository.get(app);status=snapshotStatus(repository.refresh(cancellation::get));}
            catch(Exception error){status=cancellation.get()?"Refresh cancelled":"Refresh failed: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
            finally{refreshing=false;notifyChanged();}
        });
    }
    static void cancel(){AtomicBoolean cancellation=refreshCancellation;if(cancellation!=null)cancellation.set(true);EdgeCatalogRepository current=repository;if(current!=null)current.cancelRefresh();if(refreshing){status="Cancelling refresh";notifyChanged();}}
    private static String snapshotStatus(EdgeCatalogData.Snapshot value){
        String source=value.source.equals("bundled")?"bundled":"updated "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(value.updatedAtMillis));
        return value.voices.size()+" voices · "+source;
    }
}
