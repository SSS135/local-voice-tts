package org.localvoice.tts;
import android.os.Handler;import android.os.Looper;
import java.util.concurrent.CopyOnWriteArraySet;
/** Process-local UI status/control shared with the bound service; contains no synthesis text. */
public final class BackendStatus {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Runnable> LISTENERS=new CopyOnWriteArraySet<>();
    private static volatile String text="Ready. Local voices work offline.";
    private static volatile Runnable cancellation;
    private static Object controllerOwner;
    public static String text(){return text;}
    public static void addListener(Runnable listener){LISTENERS.add(listener);}
    public static void removeListener(Runnable listener){LISTENERS.remove(listener);}
    static synchronized void controller(Object owner,Runnable action){controllerOwner=owner;cancellation=action;}
    static synchronized void clearController(Object owner){if(controllerOwner==owner){controllerOwner=null;cancellation=null;}}
    public static void cancel(){Runnable action=cancellation;if(action!=null)action.run();}
    static void update(String phase,int attempt){
        switch(phase){
            case "waiting_network":case "waiting_for_network":text="Waiting for network. The current speech stays queued. Stop cancels it.";break;
            case "retrying":text="Connection interrupted. Retrying the same speech chunk (attempt "+attempt+"). Stop cancels it.";break;
            case "stopped":text="Speech cancelled.";break;
            case "failed":text="Speech failed. Check the selected voice and service access.";break;
            case "synthesizing":text="Preparing speech…";break;
            case "complete":text="Speech completed.";break;
            default:text="Connected. Preparing the queued speech.";
        }
        MAIN.post(()->{for(Runnable listener:LISTENERS)listener.run();});
    }
}
