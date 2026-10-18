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
        if(mode.equals("edge-fixture")){
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
            File file=new File(store.modelDirectory(model.id),args.getString("file",model.files.get(0).path));
            try(RandomAccessFile out=new RandomAccessFile(file,"rw")){int first=out.read();out.seek(0);out.write(first^1);out.getFD().sync();}
            boolean rejected=false;try{store.verifyInstalled(model.id);}catch(IOException expected){rejected=true;}
            if(!rejected||store.isInstalled(model.id))throw new AssertionError("Corruption not invalidated");
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
