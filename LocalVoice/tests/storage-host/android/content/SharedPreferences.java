package android.content;
import java.util.*;
public final class SharedPreferences {
    public static int mainCommits;
    private final Map<String,Set<String>> sets=new HashMap<>();
    public synchronized Set<String> getStringSet(String key,Set<String> fallback){Set<String> value=sets.get(key);return value==null?fallback:new HashSet<>(value);}
    public Editor edit(){return new Editor();}
    public final class Editor {
        private final Map<String,Set<String>> pending=new HashMap<>();
        public Editor putStringSet(String key,Set<String> value){pending.put(key,new HashSet<>(value));return this;}
        private void save(){synchronized(SharedPreferences.this){sets.putAll(pending);}}
        public void apply(){save();}
        public boolean commit(){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())mainCommits++;save();return true;}
    }
}
