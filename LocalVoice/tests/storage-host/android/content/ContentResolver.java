package android.content;
import android.net.Uri;
import java.io.*;
import java.util.*;
public final class ContentResolver {
    public int takes,releases,opens;
    private final Set<Uri> retained=new HashSet<>();
    public synchronized List<UriPermission> getPersistedUriPermissions(){List<UriPermission> out=new ArrayList<>();for(Uri uri:retained)out.add(new UriPermission(uri));return out;}
    public synchronized void takePersistableUriPermission(Uri uri,int flags){takes++;retained.add(uri);}
    public synchronized void releasePersistableUriPermission(Uri uri,int flags){releases++;retained.remove(uri);}
    public InputStream openInputStream(Uri uri)throws IOException{opens++;return new FileInputStream(uri.fixture);}
}
