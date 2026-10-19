package android.content;
import java.io.File;
public final class Context {
    public static final int MODE_PRIVATE=0;
    private final File root;
    private final ContentResolver resolver=new ContentResolver();
    private final java.util.Map<String,SharedPreferences> preferences=new java.util.HashMap<>();
    public Context(File root){this.root=root;}
    public Context getApplicationContext(){return this;}
    public File getNoBackupFilesDir(){return root;}
    public ContentResolver getContentResolver(){return resolver;}
    public synchronized SharedPreferences getSharedPreferences(String name,int mode){return preferences.computeIfAbsent(name,k->new SharedPreferences());}
    public void sendBroadcast(Intent intent){}
}
