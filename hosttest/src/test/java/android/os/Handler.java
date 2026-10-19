package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class Handler {
    private static final ConcurrentLinkedQueue<Runnable> QUEUE=new ConcurrentLinkedQueue<>();
    public Handler(Looper looper){}
    public boolean post(Runnable runnable){QUEUE.add(runnable);return true;}
    public static void drain(){Runnable task;while((task=QUEUE.poll())!=null)task.run();}
}
