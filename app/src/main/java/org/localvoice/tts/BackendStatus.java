package org.localvoice.tts;
/** Process-local cancellation of the bound service's active request; instrumentation uses it to end fixture runs. */
public final class BackendStatus {
    private static volatile Runnable cancellation;
    private static Object controllerOwner;
    static synchronized void controller(Object owner,Runnable action){controllerOwner=owner;cancellation=action;}
    static synchronized void clearController(Object owner){if(controllerOwner==owner){controllerOwner=null;cancellation=null;}}
    public static void cancel(){Runnable action=cancellation;if(action!=null)action.run();}
}
