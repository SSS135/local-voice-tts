package org.localvoice.tts;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Signed, separately installed acceptance runner. Absent from production APKs. */
public final class PatchAcceptanceInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final JSONObject result=new JSONObject();
    private Activity activity;

    @Override public void onCreate(Bundle arguments) {this.arguments=arguments;start();}
    @Override public void onStart() {
        Bundle output=new Bundle();
        try {run();save();output.putString("result",result.toString());finish(Activity.RESULT_OK,output);}
        catch(Throwable failure) {
            try {result.put("failure",android.util.Log.getStackTraceString(failure));save();}catch(Exception ignored){}
            output.putString("failure",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,output);
        }
    }
    private static void require(boolean okay,String message) {if(!okay)throw new AssertionError(message);}
    private static Object field(Object instance,String name)throws Exception {Field f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
    private static String sha(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);}
        StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }
    private void save()throws Exception {
        File output=new File(getTargetContext().getExternalFilesDir(null),"patch-acceptance-"+arguments.getString("mode","snapshot")+".json");
        try(FileWriter writer=new FileWriter(output)){writer.write(result.toString(2));}
    }
    private static Button button(View view,String text) {
        if(view instanceof Button&&((Button)view).getText().toString().equals(text))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=button(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private void openActivity()throws Exception {
        Context target=getTargetContext();activity=startActivitySync(new Intent().setClassName(target,"org.localvoice.tts.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long deadline=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<deadline){if(activity.hasWindowFocus()&&Boolean.TRUE.equals(field(activity,"ttsReady"))&&!((List<?>)field(activity,"filteredVoices")).isEmpty())break;Thread.sleep(50);}
        require(activity.hasWindowFocus(),"Main activity lacks input focus");require(Boolean.TRUE.equals(field(activity,"ttsReady")),"UI TTS client not ready");waitForIdleSync();
    }
    private String englishDefault()throws Exception {
        CountDownLatch initialized=new CountDownLatch(1);final int[] status={-99};
        TextToSpeech client=new TextToSpeech(getTargetContext(),value->{status[0]=value;initialized.countDown();},"org.localvoice.tts");
        try {require(initialized.await(20,TimeUnit.SECONDS)&&status[0]==TextToSpeech.SUCCESS,"Public TTS initialization failed");require(client.setLanguage(new Locale("en"))>=0,"English unavailable");return client.getVoice().getName();}
        finally {client.shutdown();}
    }
    private void nativeFileModes(Context target,boolean restore)throws Exception {
        File journal=new File(target.getNoBackupFilesDir(),"patch-native-modes.json");org.json.JSONArray files;
        ModelStore store=new ModelStore(target);
        if(restore){StringBuilder json=new StringBuilder();try(BufferedReader in=new BufferedReader(new FileReader(journal))){for(String line;(line=in.readLine())!=null;)json.append(line);}files=new org.json.JSONArray(json.toString());}
        else {
            require(!journal.exists(),"Native mode fixture already active");files=new org.json.JSONArray();
            for(String id:new String[]{"kokoro-en","supertonic-3","pocket-en-v2"}){
                Catalog.Model model=Catalog.findModel(id);require(model!=null,"Native model catalog missing");File directory=store.modelDirectory(id);
                for(Catalog.ModelFile entry:model.files)if(entry.bytes>=1024L*1024L){File file=ModelFiles.child(directory,entry.path);require(file.length()==entry.bytes&&sha(file).equals(entry.sha256),"Native mode fixture pin mismatch");files.put(new JSONObject().put("model",id).put("file",entry.path).put("originalMode",android.system.Os.lstat(file.getAbsolutePath()).st_mode&0777));}
            }
            try(FileOutputStream out=new FileOutputStream(journal)){out.write(files.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }
        for(int i=0;i<files.length();i++){
            JSONObject entry=files.getJSONObject(i);String id=entry.getString("model"),relative=entry.getString("file");
            require(Arrays.asList("kokoro-en","supertonic-3","pocket-en-v2").contains(id),"Unexpected native fixture model");Catalog.Model model=Catalog.findModel(id);boolean pinned=false;
            for(Catalog.ModelFile file:model.files)if(file.path.equals(relative)&&file.bytes>=1024L*1024L)pinned=true;require(pinned,"Unexpected native fixture file");
            File file=ModelFiles.child(store.modelDirectory(id),relative);int mode=restore?entry.getInt("originalMode"):0400;android.system.Os.chmod(file.getAbsolutePath(),mode);require((android.system.Os.lstat(file.getAbsolutePath()).st_mode&0777)==mode,"Native file mode mismatch");
        }
        if(restore)require(journal.delete(),"Cannot remove native mode journal");
        result.put("nativeWeightFiles",files).put("nativeModesRestored",restore).put("modeApplied",restore?"original per-file modes":"0400");
    }
    private void checkNativeModes(Context target)throws Exception {
        File journal=new File(target.getNoBackupFilesDir(),"patch-native-modes.json");StringBuilder json=new StringBuilder();
        try(BufferedReader in=new BufferedReader(new FileReader(journal))){for(String line;(line=in.readLine())!=null;)json.append(line);}
        org.json.JSONArray files=new org.json.JSONArray(json.toString());ModelStore store=new ModelStore(target);
        for(int i=0;i<files.length();i++){
            JSONObject entry=files.getJSONObject(i);String id=entry.getString("model"),relative=entry.getString("file");
            require(Arrays.asList("kokoro-en","supertonic-3","pocket-en-v2").contains(id),"Unexpected native mode model");
            Catalog.Model model=Catalog.findModel(id);Catalog.ModelFile pin=null;for(Catalog.ModelFile file:model.files)if(file.path.equals(relative))pin=file;require(pin!=null,"Unknown native mode file");
            File file=ModelFiles.child(store.modelDirectory(id),relative);int observed=android.system.Os.lstat(file.getAbsolutePath()).st_mode&0777;
            require(observed==0400&&file.length()==pin.bytes&&sha(file).equals(pin.sha256),"Native0400 post-smoke mode/pin changed");entry.put("observedModeOctal",Integer.toOctalString(observed)).put("sha256",pin.sha256).put("pinnedBytes",pin.bytes);
        }
        result.put("nativeWeightFilesAfterSpeech",files).put("allRemain0400AndPinned",true);
    }
    private void run()throws Exception {
        Context target=getTargetContext();String mode=arguments.getString("mode","snapshot");SharedPreferences preferences=target.getSharedPreferences("voices",Context.MODE_PRIVATE);
        PackageInfo info=target.getPackageManager().getPackageInfo(target.getPackageName(),0);
        result.put("mode",mode).put("pid",Process.myPid()).put("versionName",info.versionName).put("versionCode",info.versionCode).put("apkSha256",sha(new File(target.getApplicationInfo().sourceDir)));
        result.put("voiceBefore",preferences.getString("voice","")).put("speedBefore",(double)preferences.getFloat("speed_multiplier",1f)).put("onlineBefore",preferences.getBoolean("online.edge",false));
        if(mode.equals("prepare-upgrade")) {
            require(info.versionCode==2&&info.versionName.equals("1.1.0"),"Upgrade must start from delivered1.1.0");
            require(preferences.edit().putString("voice","pocket-en-v2:alba").putFloat("speed_multiplier",1.3f).commit(),"Could not save upgrade fixture preferences");
            result.put("fixtureVoice",preferences.getString("voice","")).put("fixtureSpeed",(double)preferences.getFloat("speed_multiplier",1));
            result.put("publicEnglishDefault",englishDefault());require(result.getString("publicEnglishDefault").equals("pocket-en-v2:alba"),"Seeded public default mismatch");return;
        }
        if(mode.equals("upgrade-check")) {
            require(info.versionCode==3&&info.versionName.equals("1.1.1"),"Not patch release");
            require(preferences.getString("voice","").equals("pocket-en-v2:alba")&&Math.abs(preferences.getFloat("speed_multiplier",0)-1.3f)<.001,"Upgrade lost nondefault preferences");
            openActivity();SeekBar speed=activity.findViewById(0x10f005);require(speed.getProgress()==8,"Upgrade UI speed mismatch");
            @SuppressWarnings("unchecked") List<Catalog.VoiceSpec> voices=(List<Catalog.VoiceSpec>)field(activity,"filteredVoices");int wanted=-1;
            for(int i=0;i<voices.size();i++)if(voices.get(i).id.equals("pocket-en-v2:alba"))wanted=i;require(wanted>=0,"Alba missing after upgrade");
            ListView list=activity.findViewById(0x10f004);final int position=wanted;runOnMainSync(()->list.setSelectionFromTop(position+1,0));Thread.sleep(100);waitForIdleSync();
            View row=list.getChildAt(position+1-list.getFirstVisiblePosition());require(row!=null,"Selected row not bound");
            View check=(View)field(row.getTag(),"check");require(check.getVisibility()==View.VISIBLE&&row.getContentDescription().toString().endsWith(", selected"),"Upgrade UI checkmark mismatch");
            result.put("uiSelectedCheckmark",true).put("sliderProgress",speed.getProgress()).put("publicEnglishDefault",englishDefault());require(result.getString("publicEnglishDefault").equals("pocket-en-v2:alba"),"Upgrade public default mismatch");
        } else if(mode.equals("cadence")) {
            openActivity();runOnMainSync(()->{Button downloads=button(activity.getWindow().getDecorView(),"Downloads");require(downloads!=null,"Downloads destination missing");downloads.performClick();});waitForIdleSync();
            result.put("actualActivityProbe",UiCadenceProbe.run(this,activity));
        } else if(mode.equals("path-gates")) {
            result.put("downloadPathProbe",NativeDownloadPathProbe.run(this,target));
        } else if(mode.equals("fd-jobs")) {
            result.put("nativeDescriptorProbe",NativeFdJobProbe.run(this,target));
        } else if(mode.equals("fd-fallback")) {
            result.put("fallbackDescriptorProbe",NativeFdJobProbe.run(this,target,true));
        } else if(mode.equals("fd-cleanup")) {
            result.put("diagnosticCleanup",NativeFdJobProbe.cleanupDiagnostics(target));
        } else if(mode.equals("seal-native-files")) {
            nativeFileModes(target,false);
        } else if(mode.equals("restore-native-files")) {
            nativeFileModes(target,true);
        } else if(mode.equals("check-native-files")) {
            checkNativeModes(target);
        } else if(mode.equals("verify")) {
            String model=arguments.getString("model");ModelStore store=new ModelStore(target);boolean failed=false;
            try{store.verifyInstalled(model);}catch(IOException damaged){failed=true;}
            result.put("model",model).put("verificationRejected",failed).put("installedAfter",store.isInstalled(model));
        } else if(mode.equals("hide-data")) {
            File base=target.getNoBackupFilesDir().getCanonicalFile(),models=new File(base,"models"),held=new File(base,"models.patch-acceptance-held");
            require(models.getCanonicalFile().getParentFile().equals(base)&&held.getCanonicalFile().getParentFile().equals(base),"Unexpected model root");
            require(models.isDirectory()&&!held.exists(),"Model holding fixture already exists");
            JSONObject state=new JSONObject().put("online",preferences.getBoolean("online.edge",false));
            File stateFile=new File(base,"patch-acceptance-held-state.json");require(!stateFile.exists(),"Holding fixture journal already exists");
            try(FileWriter writer=new FileWriter(stateFile)){writer.write(state.toString());}
            boolean complete=false;
            try {
                require(models.renameTo(held)&&models.mkdir(),"Cannot isolate model data");
                require(preferences.edit().putBoolean("online.edge",false).commit(),"Cannot disable online fixture");
                result.put("dataHeld",true).put("emptyRoot",models.list().length==0);complete=true;
            } finally {
                if(!complete&&held.isDirectory()) {
                    require(!models.exists()||(models.isDirectory()&&models.list().length==0&&models.delete()),"Refusing to overwrite unexpected recovery root");
                    require(held.renameTo(models),"Holding fixture requires recovery");preferences.edit().putBoolean("online.edge",state.getBoolean("online")).commit();stateFile.delete();
                }
            }
        } else if(mode.equals("restore-data")) {
            File base=target.getNoBackupFilesDir().getCanonicalFile(),models=new File(base,"models"),held=new File(base,"models.patch-acceptance-held"),stateFile=new File(base,"patch-acceptance-held-state.json");
            require((!models.exists()||(models.isDirectory()&&models.list().length==0))&&held.isDirectory(),"Refusing to replace nonempty or unexpected root");
            require((!models.exists()||models.delete())&&held.renameTo(models),"Cannot restore held model data");
            String state;try(BufferedReader reader=new BufferedReader(new FileReader(stateFile))){state=reader.readLine();}
            require(preferences.edit().putBoolean("online.edge",new JSONObject(state).getBoolean("online")).commit(),"Cannot restore online choice");require(stateFile.delete(),"Cannot remove fixture journal");
            result.put("dataRestored",true).put("installedDirectoryCount",models.list().length);
        } else if(mode.equals("restore-prefs")) {
            String voice=arguments.getString("voice","kokoro-en:af_heart");float speed=Float.parseFloat(arguments.getString("speed","1.0"));
            require(preferences.edit().putString("voice",voice).putFloat("speed_multiplier",speed).commit(),"Cannot restore preferences");result.put("restoredVoice",voice).put("restoredSpeed",(double)speed);
        }
        if(activity!=null)runOnMainSync(activity::finish);
    }
}
