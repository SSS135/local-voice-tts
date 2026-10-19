package org.localvoice.tts;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.BaseAdapter;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** SDK-only observer of the actual activity. Synthetic Store state tests rendering, not installation. */
public final class UiCadenceProbe {
    public static JSONObject run(Instrumentation instrumentation,Activity activity)throws Exception {
        final JSONObject result=new JSONObject();final AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{
            final List<TextView> watched=new ArrayList<>();final AtomicInteger callbacks=new AtomicInteger();
            final TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable s){callbacks.incrementAndGet();}};
            List<Catalog.Model> originalCatalog=Catalog.MODELS;Map<String,ModelStore.Progress> progress=null;Map<String,Boolean> installed=null;
            ModelStore.Progress originalProgress=null;Boolean originalInstalled=null;String installedKey=null;boolean captured=false;
            SharedPreferences prefs=activity.getSharedPreferences("voices",Context.MODE_PRIVATE);boolean hadVoice=prefs.contains("voice");String originalVoice=prefs.getString("voice","");
            Method changed=null;
            try{
                require(!activity.isFinishing()&&activity.getWindow().getDecorView().hasWindowFocus(),"Activity must be settled and focused");
                changed=activity.getClass().getMethod("changed");changed.invoke(activity);
                @SuppressWarnings("unchecked") Map<String,Object> cards=(Map<String,Object>)field(activity,"modelCards");
                require(cards.size()>0,"No live download cards");
                for(Object card:cards.values())for(String name:new String[]{"metadata","status","action","cancel"}){
                    TextView view=(TextView)field(card,name);if(!watched.contains(view)){view.addTextChangedListener(watcher);watched.add(view);}
                }
                callbacks.set(0);for(int i=0;i<100;i++)changed.invoke(activity);
                int quiet=callbacks.get();require(quiet==0,"Unchanged card text emitted "+quiet+" TextWatcher callbacks");
                result.put("unchangedChangedCalls",100).put("watchedCardTextViews",watched.size()).put("unchangedCardTextCallbacks",quiet);
                PreviewSession previews=(PreviewSession)field(activity,"previews");require(previews.voice().isEmpty(),"Probe requires idle preview session");
                BaseAdapter adapter=(BaseAdapter)field(activity,"voiceAdapter");AtomicInteger notifications=new AtomicInteger();
                DataSetObserver dataObserver=new DataSetObserver(){@Override public void onChanged(){notifications.incrementAndGet();}};
                Method stop=activity.getClass().getDeclaredMethod("stopPreview");stop.setAccessible(true);
                TextView speechStatus=(TextView)field(activity,"speechStatus");CharSequence priorSpeechStatus=speechStatus.getText();
                adapter.registerDataSetObserver(dataObserver);
                try{
                    stop.invoke(activity);require(notifications.get()==0,"Idle stop unnecessarily invalidated voice adapter");
                    String token=previews.begin("probe-preview");stop.invoke(activity);
                    require(notifications.get()==1&&!previews.accepts(token)&&previews.voice().isEmpty(),"Active preview stop lost one adapter update/token invalidation");
                    result.put("idleStopAdapterNotifications",0).put("activeSyntheticStopAdapterNotifications",1).put("stoppedSyntheticTokenRejected",true);
                }finally{adapter.unregisterDataSetObserver(dataObserver);previews.clear();speechStatus.setText(priorSpeechStatus);}
                ModelStore store=(ModelStore)field(activity,"store");Catalog.Model model=Catalog.findModel("kokoro-en");require(model!=null,"Kokoro fixture absent");
                @SuppressWarnings("unchecked") Map<String,ModelStore.Progress> p=(Map<String,ModelStore.Progress>)staticField(ModelStore.class,"PROGRESS");progress=p;
                @SuppressWarnings("unchecked") Map<String,Boolean> i=(Map<String,Boolean>)staticField(ModelStore.class,"INSTALLED");installed=i;
                installedKey=store.modelDirectory(model.id).getAbsolutePath();originalProgress=progress.get(model.id);originalInstalled=installed.get(installedKey);captured=true;
                Object card=cards.get(model.id);TextView status=(TextView)field(card,"status"),action=(TextView)field(card,"action"),cancel=(TextView)field(card,"cancel");ProgressBar bar=(ProgressBar)field(card,"progress");
                installed.put(installedKey,false);progress.put(model.id,new ModelStore.Progress("Downloading","",25,100,true,25,100,0,model.files.size()));changed.invoke(activity);
                require(status.getText().toString().startsWith("Downloading")&&bar.getVisibility()==View.VISIBLE&&!bar.isIndeterminate()&&bar.getProgress()==250&&cancel.getVisibility()==View.VISIBLE&&action.getVisibility()==View.GONE,"Running phase did not render controls/progress");
                result.put("runningStateRendered",true);
                progress.put(model.id,new ModelStore.Progress("Verifying","",25,100,true,0,0,0,model.files.size()));changed.invoke(activity);
                require(bar.isIndeterminate()&&bar.getVisibility()==View.VISIBLE,"Unknown verification total lost indeterminate progress");
                progress.put(model.id,new ModelStore.Progress("Verifying","",25,100,true,50,100,1,model.files.size()));changed.invoke(activity);
                require(!bar.isIndeterminate()&&bar.getProgress()==500,"Known verification total failed to update guarded progress");
                result.put("unknownToKnownVerificationProgressRendered",true);
                installed.put(installedKey,false);progress.put(model.id,new ModelStore.Progress("Cancelled","",25,100,false));changed.invoke(activity);
                require(status.getText().toString().equals("Cancelled")&&action.getText().toString().equals("Retry")&&action.getVisibility()==View.VISIBLE&&cancel.getVisibility()==View.GONE,"Cancelled state lost explicit Retry");
                result.put("cancelledRetryRendered",true);
                installed.put(installedKey,false);progress.put(model.id,new ModelStore.Progress("Download failed","probe error",25,100,false));changed.invoke(activity);
                require(status.getText().toString().contains("probe error")&&action.getText().toString().equals("Retry"),"Error detail/Retry missing");result.put("errorRetryRendered",true);
                require(!containsModel(field(activity,"availableVoices"),model.id),"Unavailable model still advertised in activity snapshot");
                installed.put(installedKey,true);progress.put(model.id,new ModelStore.Progress("Installed","",model.bytes(),model.bytes(),false));changed.invoke(activity);
                require(status.getText().toString().equals("Installed")&&bar.getVisibility()==View.GONE&&cancel.getVisibility()==View.GONE&&action.getVisibility()==View.GONE,"Installed terminal controls stale");
                require(containsModel(field(activity,"availableVoices"),model.id),"Availability transition did not refresh voices");result.put("installedAndAvailabilityRendered",true);
                Catalog.Model replacement=new Catalog.Model(model.id,model.label,model.backend,model.revision,model.network,model.available,Collections.singletonList("probe-language"),model.voices,model.files,model.config,model.license,model.licenseUrl,model.licenseText,model.licenseAsset,model.description);
                List<Catalog.Model> replaced=new ArrayList<>(originalCatalog);replaced.set(replaced.indexOf(model),replacement);Catalog.MODELS=Collections.unmodifiableList(replaced);changed.invoke(activity);
                require(((TextView)field(card,"metadata")).getText().toString().contains("probe-language"),"Same-ID replacement metadata was skipped");
                require(field(card,"boundModel")==replacement,"Card still bound to old same-ID catalog object");result.put("sameIdNewCatalogReferenceRefreshed",true);
                String selection=model.voices.get(0).id;prefs.edit().putString("voice",selection).apply();changed.invoke(activity);
                require(field(activity,"selectedVoice").equals(selection),"Selection update was skipped");result.put("selectedStateRefreshed",true);
                require(callbacks.get()>0,"Real changes never reached observed TextViews");
                result.put("changedStateTextCallbacks",callbacks.get()).put("scope","Actual card TextWatchers and state/metadata/availability/selection handlers; controlled in-memory Store/Catalog states, no download or draw/frame claim");
            }catch(Throwable error){failure.set(error);}
            finally{
                try{
                    for(TextView view:watched)view.removeTextChangedListener(watcher);
                    Catalog.MODELS=originalCatalog;
                    if(captured){if(originalProgress==null)progress.remove("kokoro-en");else progress.put("kokoro-en",originalProgress);if(originalInstalled==null)installed.remove(installedKey);else installed.put(installedKey,originalInstalled);}
                    if(hadVoice)prefs.edit().putString("voice",originalVoice).apply();else prefs.edit().remove("voice").apply();
                    if(changed!=null)changed.invoke(activity);
                    result.put("fixtureStateRestored",true);
                }catch(Throwable cleanup){if(failure.get()==null)failure.set(cleanup);else failure.get().addSuppressed(cleanup);}
            }
        });
        instrumentation.waitForIdleSync();
        if(failure.get()!=null)throw new AssertionError("UI cadence observer failed",failure.get());
        result.put("passed",true);return result;
    }
    private static boolean containsModel(Object value,String modelId)throws Exception {for(Object voice:(List<?>)value)if(field(voice,"modelId").equals(modelId))return true;return false;}
    private static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static Object staticField(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private UiCadenceProbe(){}
}
