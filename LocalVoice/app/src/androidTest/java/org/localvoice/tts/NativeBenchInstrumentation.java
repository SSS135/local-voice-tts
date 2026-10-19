package org.localvoice.tts;
import android.app.Instrumentation;
import static android.app.Activity.RESULT_OK;
import static android.app.Activity.RESULT_CANCELED;
import android.os.*;
import org.localvoice.tts.backend.*;
import org.json.*;
import java.io.*;
import java.util.*;
/** Test APK only: native CPU comparison and corrupt/retry fixtures. No release entry point. */
public final class NativeBenchInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        ModelStore store=new ModelStore(getTargetContext());String mode=args.getString("mode","bench");
        Catalog.Model model=Catalog.findModel(args.getString("model","supertonic-3"));
        if(mode.equals("store-profile")){
            JSONArray rows=new JSONArray();
            for(String modelId:new String[]{"supertonic-3","kokoro-en"}){
                Catalog.Model measured=Catalog.findModel(modelId);
                if(!store.isInstalled(modelId))throw new AssertionError("Profile needs installed "+modelId);
                ModelStore.startProfile();store.verifyInstalled(modelId);
                JSONObject cold=new JSONObject(ModelStore.finishProfile());cold.put("phase","cold-verify");cold.put("model",modelId);rows.put(cold);
                ModelStore.startProfile();store.verifyInstalled(modelId);
                JSONObject warm=new JSONObject(ModelStore.finishProfile());warm.put("phase","warm-verify");warm.put("model",modelId);rows.put(warm);
                ModelStore.startProfile();
                runOnMainSync(()->{for(int i=0;i<100;i++)if(!store.isInstalled(modelId))throw new AssertionError("Lost installed model");});
                JSONObject poll=new JSONObject(ModelStore.finishProfile());poll.put("phase","100-main-isInstalled");poll.put("model",modelId);rows.put(poll);
                File installed=store.modelDirectory(modelId),staging=new File(installed.getPath()+".download");
                if(staging.exists())throw new AssertionError("Staging already exists; preserve it before profile");
                if(!installed.renameTo(staging))throw new IOException("Cannot stage installed model");
                if(!new File(staging,"installed.manifest").delete())throw new IOException("Cannot reset receipt");
                ModelStore.startProfile();store.download(modelId);long deadline=SystemClock.elapsedRealtime()+300000;
                String phase="";long phaseStart=SystemClock.elapsedRealtime();JSONArray phases=new JSONArray();
                while(SystemClock.elapsedRealtime()<deadline){
                    ModelStore.Progress progress=store.progress(modelId);
                    if(!phase.equals(progress.state)){JSONObject entry=new JSONObject();entry.put("state",phase);entry.put("elapsedMs",SystemClock.elapsedRealtime()-phaseStart);phases.put(entry);phase=progress.state;phaseStart=SystemClock.elapsedRealtime();}
                    if(!progress.running){if(!store.isInstalled(modelId))throw new AssertionError("Profile install failed: "+progress.state+" "+progress.detail);break;}
                    Thread.sleep(20);
                }
                if(!store.isInstalled(modelId))throw new AssertionError("Profile install timed out");
                waitForIdleSync();JSONObject retry=new JSONObject(ModelStore.finishProfile());retry.put("phase","cached-retry");retry.put("model",modelId);retry.put("phases",phases);rows.put(retry);
            }
            File out=new File(getTargetContext().getExternalFilesDir(null),"store-profile.json");try(FileWriter writer=new FileWriter(out)){writer.write(rows.toString(2));}
            result.putString("result",rows.toString());
        }else if(mode.equals("edge-fixture")){
            java.lang.reflect.Field field=BackendRegistry.class.getDeclaredField("FACTORIES");field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,java.util.function.Supplier<SpeechBackend>> factories=(Map<String,java.util.function.Supplier<SpeechBackend>>)field.get(null);
            String endpoint=args.getString("endpoint","http://127.0.0.1:8765/edge");long timeout=Long.parseLong(args.getString("attemptMs","2000"));
            java.util.function.Supplier<SpeechBackend> original=factories.put("edge",()->new EdgeBackend(endpoint,timeout));
            File stop=new File(getTargetContext().getFilesDir(),"edge-fixture-stop");stop.delete();
            Bundle ready=new Bundle();ready.putString("fixture","ready");sendStatus(0,ready);
            long deadline=SystemClock.elapsedRealtime()+Long.parseLong(args.getString("durationMs","600000"));
            try{while(!stop.exists()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);}
            finally{BackendStatus.cancel();factories.put("edge",original);stop.delete();}
            result.putString("result","fixture factory restored");
        }else if(mode.equals("corrupt")){
            store.verifyInstalled(model.id);
            File file=new File(store.modelDirectory(model.id),args.getString("file",model.files.get(0).path));
            long originalMtime=file.lastModified();
            File backup=new File(getTargetContext().getExternalFilesDir(null),"corrupt-backup-"+new File(file.getName()).getName());
            if(file.length()>1048576)throw new AssertionError("Corruption fixture only accepts a small asset");
            try(InputStream input=new FileInputStream(file);OutputStream output=new FileOutputStream(backup)){byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);}
            try(RandomAccessFile out=new RandomAccessFile(file,"rw")){int first=out.read();out.seek(0);out.write(first^1);out.getFD().sync();}
            if(args.getString("restoreMtime","false").equals("true")&&!file.setLastModified(originalMtime))throw new AssertionError("Cannot restore mtime in corruption fixture");
            boolean rejected=false;try{store.verifyInstalled(model.id);}catch(IOException expected){rejected=true;}
            if(!rejected||store.isInstalled(model.id))throw new AssertionError("Corruption not invalidated");
            result.putLong("originalMtime",originalMtime);result.putLong("afterMtime",file.lastModified());
            result.putBoolean("mtimeRestored",originalMtime==file.lastModified());result.putString("backupSha256",ModelFiles.sha256(backup));result.putString("corruptSha256",ModelFiles.sha256(file));
            result.putString("result","PASS corrupted package rejected; receipt invalidated; retry required");
        }else{
            store.verifyInstalled(model.id);JSONArray rows=new JSONArray();
            for(int threads:new int[]{2,4}){
                Map<String,String> config=new HashMap<>(model.config);config.put("numThreads",Integer.toString(threads));
                SpeechBackend backend=BackendRegistry.create(model.backend);long load=SystemClock.elapsedRealtime();backend.load(store.modelDirectory(model.id),config);long loaded=SystemClock.elapsedRealtime()-load;
                try{for(int run=0;run<2;run++){
                    long start=SystemClock.elapsedRealtime();long[] first={-1},samples={0};
                    backend.synthesize("A quiet evening falls beside the river.","en-US",0,1f,()->false,pcm->{if(first[0]<0)first[0]=SystemClock.elapsedRealtime()-start;samples[0]+=pcm.length;return true;});
                    JSONObject row=new JSONObject();row.put("threads",threads);row.put("run",run);row.put("loadMs",loaded);row.put("firstPcmMs",first[0]);row.put("totalMs",SystemClock.elapsedRealtime()-start);row.put("samples",samples[0]);row.put("sampleRate",backend.sampleRate());row.put("pssKb",Debug.getPss());rows.put(row);
                }}finally{backend.close();}
            }
            File out=new File(getTargetContext().getExternalFilesDir(null),"native-bench-"+model.id+".json");try(FileWriter writer=new FileWriter(out)){writer.write(rows.toString(2));}result.putString("result",rows.toString());
        }
        finish(RESULT_OK,result);
    }catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));finish(RESULT_CANCELED,result);}}
}
