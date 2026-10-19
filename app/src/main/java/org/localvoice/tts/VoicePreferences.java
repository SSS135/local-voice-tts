package org.localvoice.tts;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices are separate from catalog, model files, and backend configuration. */
final class VoicePreferences {
    private final SharedPreferences preferences;
    VoicePreferences(Context context) { preferences=context.getSharedPreferences("voices",Context.MODE_PRIVATE); }
    String selected() { return preferences.getString("voice",""); }
    void select(String id) { preferences.edit().putString("voice",id).apply(); }
    boolean onlineEnabled(String id) { return preferences.getBoolean("online."+id,false); }
    void enableOnline(String id,boolean enabled) { preferences.edit().putBoolean("online."+id,enabled).apply(); }
}
