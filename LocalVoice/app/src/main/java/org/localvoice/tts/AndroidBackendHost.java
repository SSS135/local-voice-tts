package org.localvoice.tts;
import android.content.Context;import android.net.*;import android.os.SystemClock;import android.util.Log;
import org.localvoice.tts.backend.SpeechBackend;
/** Android network signals are platform services injected into adapters, not model-specific service branches. */
final class AndroidBackendHost implements SpeechBackend.Host,AutoCloseable {
    private final ConnectivityManager connectivity;private final Object changed=new Object();private volatile boolean closed;
    private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){wake();}
        @Override public void onLost(Network network){wake();}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){wake();}
    };
    AndroidBackendHost(Context context){connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);connectivity.registerDefaultNetworkCallback(callback);}
    private void wake(){synchronized(changed){changed.notifyAll();}}
    @Override public boolean isNetworkAvailable(){Network active=connectivity.getActiveNetwork();NetworkCapabilities capabilities=active==null?null:connectivity.getNetworkCapabilities(active);return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}
    @Override public void awaitNetworkOrDelay(long millis,SpeechBackend.Cancellation cancellation)throws InterruptedException{
        boolean initiallyAvailable=isNetworkAvailable();long end=SystemClock.elapsedRealtime()+Math.max(0,millis);
        synchronized(changed){while(!cancellation.isCancelled()){
            long remaining=end-SystemClock.elapsedRealtime();if(remaining<=0||(!initiallyAvailable&&isNetworkAvailable()))return;
            changed.wait(Math.min(50,remaining));
        }}
    }
    @Override public synchronized void reportStatus(String phase,int attempts){if(!closed){BackendStatus.update(phase,attempts);Log.i("LocalVoiceStatus","phase="+phase+" attempts="+attempts);}}
    @Override public synchronized void close(){closed=true;connectivity.unregisterNetworkCallback(callback);wake();}
}
