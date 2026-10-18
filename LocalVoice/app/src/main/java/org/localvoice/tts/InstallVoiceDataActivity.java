package org.localvoice.tts;
import android.app.Activity; import android.os.Bundle; import android.content.Intent;
public final class InstallVoiceDataActivity extends Activity { @Override public void onCreate(Bundle state) { super.onCreate(state);startActivity(new Intent(this,MainActivity.class));finish(); } }
