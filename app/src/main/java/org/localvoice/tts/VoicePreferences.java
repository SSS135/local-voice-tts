package org.localvoice.tts;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices are separate from catalog, model files, and backend configuration. */
final class VoicePreferences {
    private final SharedPreferences preferences;
    VoicePreferences(Context context) { preferences=context.getSharedPreferences("voices",Context.MODE_PRIVATE); }
    /** The saved voice id; a retired id (catalog alias, e.g. after a model id change) is migrated to its successor. */
    String selected() {
        String id=preferences.getString("voice","");
        String current=Catalog.canonicalVoiceId(id);
        if(!current.equals(id)) select(current);
        return current;
    }
    void select(String id) { preferences.edit().putString("voice",id).apply(); }
    boolean onlineEnabled(String id) { return preferences.getBoolean("online."+id,false); }
    void enableOnline(String id,boolean enabled) { preferences.edit().putBoolean("online."+id,enabled).apply(); }
}
