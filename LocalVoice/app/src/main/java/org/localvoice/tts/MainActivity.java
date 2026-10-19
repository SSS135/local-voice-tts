package org.localvoice.tts;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.os.*;
import android.net.Uri;
import android.provider.Settings;
import android.speech.tts.*;
import android.text.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Native UI. Catalog, installation and synthesis stay in their own components. */
public final class MainActivity extends Activity implements ModelStore.Listener {
    private Ui ui;
    private ModelStore store;
    private LinearLayout root, downloadsPage, settingsPage;
    private final List<View> pages=new ArrayList<>();
    private final List<Button> tabs=new ArrayList<>();
    private int destination;
    private EditText search,sample;
    private Spinner language;
    private TextView voiceCount,empty,speechStatus,catalogStatus,speedLabel;
    private Button getVoices,refreshCatalog,cancelRefresh;
    private VoiceAdapter voiceAdapter;
    private List<Catalog.VoiceSpec> availableVoices=Collections.emptyList(),filteredVoices=Collections.emptyList();
    private final List<String> languageTags=new ArrayList<>();
    private String languageTag="",selectedVoice="",voiceSignature="uninitialized";
    private List<Catalog.Model> renderedCatalog;
    private final PreviewSession previews=new PreviewSession();
    private static final int SELECT_MODEL_PACK=41;
    private String pendingImportModel="";
    private final Map<String,String> importErrors=new HashMap<>();
    private TextToSpeech tts;
    private boolean ttsReady,rebuildingLanguages;
    private final Map<String,DownloadCard> modelCards=new LinkedHashMap<>();
    private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService voiceWorker=Executors.newSingleThreadExecutor();
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private int filterGeneration;
    private final Runnable catalogListener=()->changed();
    private static final class DownloadCard { TextView metadata,status; ProgressBar progress; Button action,cancel; Catalog.Model boundModel; }

    @Override public void onCreate(Bundle state) {
        Ui.theme(this);super.onCreate(state);ui=new Ui(this);store=new ModelStore(this);
        root=ui.root();setContentView(root);ui.appBar(root,"Local Voice","",()->{});
        FrameLayout content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        View voices=createVoices();content.addView(voices,new FrameLayout.LayoutParams(-1,-1));pages.add(voices);
        ScrollView downloads=new ScrollView(this);downloadsPage=ui.page(downloads);createDownloads();content.addView(downloads,new FrameLayout.LayoutParams(-1,-1));pages.add(downloads);
        ScrollView settings=new ScrollView(this);settingsPage=ui.page(settings);createSettings();content.addView(settings,new FrameLayout.LayoutParams(-1,-1));pages.add(settings);
        createNavigation();
        if(state!=null){destination=state.getInt("destination",0);languageTag=state.getString("language","");pendingImportModel=state.getString("pendingImportModel","");search.setText(state.getString("search",""));sample.setText(state.getString("sample",""));}
        showDestination(destination);changed();OnlineCatalogUi.initialize(this,catalogWorker);
        tts=new TextToSpeech(this,status->runOnUiThread(()->{if(isDestroyed())return;ttsReady=status==TextToSpeech.SUCCESS;if(!ttsReady)showStatus("TTS unavailable. Reopen Local Voice.");}),getPackageName());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            private void update(String id,Runnable action){runOnUiThread(()->{if(previews.accepts(id)&&!isDestroyed())action.run();});}
            @Override public void onStart(String id){}
            @Override public void onDone(String id){update(id,()->finishPreview(""));}
            @Override public void onError(String id){update(id,()->finishPreview("Preview failed. Check model or connection."));}
            @Override public void onStop(String id,boolean interrupted){update(id,()->finishPreview(""));}
        });
    }

    private View createVoices(){
        LinearLayout page=ui.column();page.setPadding(ui.dp(20),0,ui.dp(20),0);
        ListView list=new ListView(this);list.setId(0x10f004);list.setDivider(null);list.setDividerHeight(ui.dp(8));list.setClipToPadding(false);
        LinearLayout header=ui.column();header.setPadding(0,0,0,ui.dp(8));
        search=new EditText(this);search.setId(0x10f001);search.setSingleLine(true);search.setTextSize(16);search.setTextColor(ui.ink);search.setHintTextColor(ui.muted);search.setHint("Search voices");search.setContentDescription("Search voices by name or language");search.setMinHeight(ui.dp(48));search.setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(10));search.setBackground(ui.shape(ui.surface,ui.border,12));header.addView(search,new LinearLayout.LayoutParams(-1,-2));
        language=new Spinner(this);language.setId(0x10f002);language.setMinimumHeight(ui.dp(48));language.setContentDescription("Filter by language");header.addView(language,new LinearLayout.LayoutParams(-1,-2));
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(!rebuildingLanguages&&position>=0&&position<languageTags.size()){languageTag=languageTags.get(position);filterVoices();}}
        });
        TextView sampleLabel=ui.text(header,"Preview text",14,ui.muted,true);
        sample=new EditText(this);sample.setId(0x10f003);sampleLabel.setLabelFor(sample.getId());sample.setTextSize(16);sample.setTextColor(ui.ink);sample.setHintTextColor(ui.muted);sample.setHint("Optional sample");sample.setMinHeight(ui.dp(48));sample.setMaxLines(3);sample.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));sample.setBackground(ui.shape(ui.surface,ui.border,12));header.addView(sample,new LinearLayout.LayoutParams(-1,-2));
        float speed=1f;try{speed=getSharedPreferences("voices",MODE_PRIVATE).getFloat(SpeechRatePolicy.PREFERENCE,1f);}catch(ClassCastException ignored){}speed=SpeechRatePolicy.effective(100,speed);
        speedLabel=ui.text(header,"Speed · "+speedText(speed),14,ui.muted,true);
        SeekBar speedSlider=new SeekBar(this);speedSlider.setId(0x10f005);speedLabel.setLabelFor(speedSlider.getId());speedSlider.setMax(15);speedSlider.setProgress(Math.round((speed-.5f)*10));speedSlider.setMinimumHeight(ui.dp(48));speedSlider.setProgressTintList(ColorStateList.valueOf(ui.accent));speedSlider.setThumbTintList(ColorStateList.valueOf(ui.accent));speedSlider.setContentDescription("Speech speed, "+speedText(speed));header.addView(speedSlider,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar slider,int progress,boolean fromUser){float value=.5f+progress/10f;speedLabel.setText("Speed · "+speedText(value));slider.setContentDescription("Speech speed, "+speedText(value));if(fromUser){stopPreview();getSharedPreferences("voices",MODE_PRIVATE).edit().putFloat(SpeechRatePolicy.PREFERENCE,value).apply();}}
            public void onStartTrackingTouch(SeekBar slider){stopPreview();}
            public void onStopTrackingTouch(SeekBar slider){getSharedPreferences("voices",MODE_PRIVATE).edit().putFloat(SpeechRatePolicy.PREFERENCE,.5f+slider.getProgress()/10f).apply();}
        });
        voiceCount=ui.text(header,"Voices",14,ui.muted,false);voiceCount.setPadding(0,ui.dp(8),0,ui.dp(4));
        list.addHeaderView(header,null,false);
        LinearLayout footer=ui.column();footer.setGravity(Gravity.CENTER);empty=ui.text(footer,"No voices installed",18,ui.ink,true);empty.setGravity(Gravity.CENTER);getVoices=ui.button(footer,"Get voices",true);getVoices.setOnClickListener(v->showDestination(1));list.addFooterView(footer,null,false);
        voiceAdapter=new VoiceAdapter();list.setAdapter(voiceAdapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        speechStatus=ui.text(page,"",14,ui.muted,false);speechStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);speechStatus.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){filterVoices();}public void afterTextChanged(Editable value){}});
        page.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int side=Math.max(ui.dp(20),((r-l)-ui.dp(640))/2);if(page.getPaddingLeft()!=side)page.setPadding(side,0,side,0);});
        return page;
    }
    private void createNavigation(){
        LinearLayout bar=new LinearLayout(this);bar.setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4));bar.setBackgroundColor(ui.surface);
        for(String label:new String[]{"Voices","Downloads","Settings"}){final int index=tabs.size();Button tab=new Button(this);tab.setText(label);tab.setAllCaps(false);tab.setTextSize(12);tab.setMinHeight(ui.dp(56));tab.setStateListAnimator(null);tab.setPadding(ui.dp(4),ui.dp(8),ui.dp(4),ui.dp(8));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMarginStart(ui.dp(4));lp.setMarginEnd(ui.dp(4));bar.addView(tab,lp);tabs.add(tab);tab.setOnClickListener(v->showDestination(index));}
        root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
    }
    private void showDestination(int index){
        destination=Math.max(0,Math.min(2,index));
        for(int i=0;i<pages.size();i++){pages.get(i).setVisibility(i==destination?View.VISIBLE:View.GONE);Button tab=tabs.get(i);tab.setSelected(i==destination);tab.setTextColor(i==destination?ui.accent:ui.muted);tab.setBackground(ui.ripple(i==destination?ui.accentSoft:ui.surface,0,20));tab.setBackgroundTintList(null);tab.setContentDescription(tab.getText()+(i==destination?", selected":""));}
        if(destination!=0){View focused=getCurrentFocus();if(focused!=null){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(),0);focused.clearFocus();}}
    }
    private void createDownloads(){
        ui.heading(downloadsPage,"Downloads");
        for(Catalog.Model model:Catalog.MODELS)if(model.available){
            LinearLayout card=ui.card(downloadsPage);ui.text(card,shortModel(model),19,ui.ink,true);
            DownloadCard controls=new DownloadCard();controls.metadata=ui.text(card,modelMetadata(model),14,ui.muted,false);ui.text(card,model.network?"Online":formatBytes(model.bytes())+(importsFile(model)?" · File pack":" · Offline"),14,ui.muted,false);
            controls.status=ui.text(card,"",14,ui.muted,false);controls.progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);controls.progress.setMax(1000);controls.progress.setProgressTintList(ColorStateList.valueOf(ui.accent));card.addView(controls.progress);
            final String modelId=model.id;
            controls.action=ui.button(card,model.network?"Enable":importsFile(model)?"Install file":"Download",true);controls.action.setOnClickListener(v->confirmDownload(Catalog.findModel(modelId)));controls.cancel=ui.quiet(card,importsFile(model)?"Cancel import":"Cancel download");controls.cancel.setOnClickListener(v->store.cancel(modelId));
            ui.quiet(card,"Details").setOnClickListener(v->modelDetails(Catalog.findModel(modelId)));modelCards.put(modelId,controls);
        }
    }
    private void createSettings(){
        ui.heading(settingsPage,"Settings");LinearLayout system=ui.card(settingsPage);
        ui.button(system,"Android text-to-speech",false).setOnClickListener(v->{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}});
        LinearLayout catalog=ui.card(settingsPage);ui.text(catalog,"Online voice catalog",18,ui.ink,true);catalogStatus=ui.text(catalog,"",14,ui.muted,false);
        refreshCatalog=ui.button(catalog,"Refresh voices",false);refreshCatalog.setOnClickListener(v->refreshOnlineCatalog());cancelRefresh=ui.quiet(catalog,"Cancel refresh");cancelRefresh.setVisibility(View.GONE);cancelRefresh.setOnClickListener(v->cancelOnlineCatalog());
        LinearLayout about=ui.card(settingsPage);ui.button(about,"About & licenses",false).setOnClickListener(v->startActivity(new Intent(this,AboutActivity.class)));
    }

    @Override public void changed(){
        List<Catalog.Model> catalog=Catalog.MODELS;
        String previousSelection=selectedVoice;selectedVoice=store.selectedVoice();
        for(Catalog.Model model:catalog){DownloadCard card=modelCards.get(model.id);if(card==null)continue;if(card.boundModel!=model){setTextIfChanged(card.metadata,modelMetadata(model));card.boundModel=model;}ModelStore.Progress progress=store.progress(model.id);boolean installed=store.isInstalled(model.id),failed=progress.state.contains("failed");String status=installed?(model.network?"Enabled":"Installed"):importsFile(model)&&progress.state.equals("Not downloaded")?"Not installed":progress.state;
            boolean checking=progress.state.equals("Checking files");
            long shownBytes=progress.state.equals("Downloading")?progress.bytes:progress.phaseBytes,shownTotal=progress.state.equals("Downloading")?progress.total:progress.phaseTotal;
            if(checking){shownBytes=progress.filesDone;shownTotal=progress.filesTotal;}
            if(progress.running){status=progress.state;if(checking&&shownTotal>0)status+=" · "+shownBytes+" / "+shownTotal+" files";else if(shownTotal>0)status+=" · "+Math.min(100,100*shownBytes/shownTotal)+"%\n"+formatBytes(shownBytes)+" / "+formatBytes(shownTotal);}else if(failed&&!progress.detail.isEmpty())status+="\n"+progress.detail;
            String selectionError=importErrors.get(model.id);if(selectionError!=null&&!progress.running){status=selectionError;failed=true;}
            setTextIfChanged(card.status,status);setColorIfChanged(card.status,failed?ui.danger:installed?ui.success:ui.muted);setVisibleIfChanged(card.progress,progress.running?View.VISIBLE:View.GONE);
            boolean indeterminate=shownTotal<=0;int amount=shownTotal>0?(int)(1000*shownBytes/shownTotal):0;
            if(card.progress.isIndeterminate()!=indeterminate)card.progress.setIndeterminate(indeterminate);
            if(card.progress.getProgress()!=amount)card.progress.setProgress(amount);
            setVisibleIfChanged(card.action,installed||progress.running?View.GONE:View.VISIBLE);setTextIfChanged(card.action,model.network?"Enable":importsFile(model)?"Install file":failed||progress.state.equals("Cancelled")?"Retry":"Download");setVisibleIfChanged(card.cancel,progress.running?View.VISIBLE:View.GONE);
        }
        List<Catalog.VoiceSpec> voices=store.installedVoices();StringBuilder signature=new StringBuilder();for(Catalog.VoiceSpec voice:voices)signature.append('|').append(voice.id);
        if(renderedCatalog!=catalog||!signature.toString().equals(voiceSignature)){voiceSignature=signature.toString();availableVoices=voices;rebuildLanguageFilter();filterVoices();}
        else if(!previousSelection.equals(selectedVoice))voiceAdapter.notifyDataSetChanged();
        renderedCatalog=catalog;
        updateCatalogStatus();
    }
    private void rebuildLanguageFilter(){
        rebuildingLanguages=true;SortedSet<String> tags=new TreeSet<>();for(Catalog.VoiceSpec voice:availableVoices)tags.add(voice.languageTag);languageTags.clear();languageTags.add("");languageTags.addAll(tags);List<String> labels=new ArrayList<>();labels.add("All languages");for(String tag:tags)labels.add(languageLabel(tag));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);language.setAdapter(adapter);languageTag=VoiceFilter.validLanguage(languageTags,languageTag);language.setSelection(languageTags.indexOf(languageTag),false);rebuildingLanguages=false;
    }
    private void filterVoices(){
        if(search==null||voiceAdapter==null)return;
        if(pendingFilter!=null)uiHandler.removeCallbacks(pendingFilter);
        final int generation=++filterGeneration;
        final String query=search.getText().toString().trim().toLowerCase(Locale.ROOT),tag=languageTag;
        final List<Catalog.VoiceSpec> source=availableVoices;
        pendingFilter=()->voiceWorker.execute(()->{
            List<Catalog.VoiceSpec> result=new ArrayList<>();
            for(Catalog.VoiceSpec voice:source)if(VoiceFilter.matches(voice.id,voice.label,voice.languageTag,tag,query,Locale.getDefault()))result.add(voice);
            uiHandler.post(()->{
                if(isDestroyed()||generation!=filterGeneration)return;
                filteredVoices=result;voiceCount.setText(voiceCountLabel(result.size()));empty.setText(source.isEmpty()?"No voices installed":"No matching voices");empty.setVisibility(result.isEmpty()?View.VISIBLE:View.GONE);getVoices.setVisibility(source.isEmpty()?View.VISIBLE:View.GONE);voiceAdapter.notifyDataSetChanged();
            });
        });
        uiHandler.postDelayed(pendingFilter,100);
    }
    private void chooseVoice(Catalog.VoiceSpec voice){stopPreview();try{store.selectVoice(voice.id);}catch(IllegalArgumentException unavailable){showStatus("Voice unavailable. Check Downloads.");voiceSignature="uninitialized";changed();return;}selectedVoice=voice.id;voiceAdapter.notifyDataSetChanged();if(ttsReady)applyAndroidVoice(voice.id);}
    private boolean applyAndroidVoice(String id){Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice voice:voices)if(voice.getName().equals(id))return tts.setVoice(voice)==TextToSpeech.SUCCESS;return false;}
    private void listen(Catalog.VoiceSpec voice){
        if(voice.id.equals(previews.voice())){stopPreview();return;}if(!ttsReady){showStatus("TTS is connecting. Try again.");return;}if(!store.isInstalled(voice.modelId)){showStatus("Voice unavailable. Check Downloads.");return;}
        String utterance=previews.begin(voice.id);tts.stop();
        if(!applyAndroidVoice(voice.id)){finishPreview("Voice unavailable. Reopen Local Voice.");return;}tts.setSpeechRate(1f);
        String text=sample.getText().toString().trim();if(text.isEmpty())text=voice.languageTag.startsWith("ru")?"Здравствуйте! Это пример звучания голоса.":"Hello! This is a voice preview.";
        int result=tts.speak(text,TextToSpeech.QUEUE_FLUSH,new Bundle(),utterance);if(result==TextToSpeech.SUCCESS){showStatus("");voiceAdapter.notifyDataSetChanged();}else finishPreview("Could not start preview. Try again.");
    }
    private void stopPreview(){boolean wasPlaying=!previews.voice().isEmpty();previews.clear();if(tts!=null)tts.stop();if(wasPlaying&&voiceAdapter!=null)voiceAdapter.notifyDataSetChanged();}
    private void finishPreview(String message){previews.clear();showStatus(message);voiceAdapter.notifyDataSetChanged();}
    /** Only actionable problems are shown; an empty message hides the line. */
    private void showStatus(String message){setTextIfChanged(speechStatus,message);setVisibleIfChanged(speechStatus,message.isEmpty()?View.GONE:View.VISIBLE);}

    private final class VoiceAdapter extends BaseAdapter{
        public int getCount(){return filteredVoices.size();}public Object getItem(int position){return filteredVoices.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            VoiceRow row;
            if(recycled==null){row=new VoiceRow();LinearLayout layout=new LinearLayout(MainActivity.this);layout.setOrientation(LinearLayout.HORIZONTAL);layout.setGravity(Gravity.CENTER_VERTICAL);layout.setPadding(ui.dp(12),ui.dp(8),ui.dp(8),ui.dp(8));layout.setMinimumHeight(ui.dp(72));layout.setFocusable(true);
                row.check=new ImageView(MainActivity.this);row.check.setImageResource(R.drawable.ic_check_24);row.check.setImageTintList(ColorStateList.valueOf(ui.accent));row.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams icon=new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24));icon.setMarginEnd(ui.dp(8));layout.addView(row.check,icon);
                LinearLayout copy=ui.column();row.name=ui.text(copy,"",16,ui.ink,true);row.locale=ui.text(copy,"",14,ui.muted,false);copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);layout.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
                row.listen=new Button(MainActivity.this);row.listen.setAllCaps(false);row.listen.setTextSize(14);row.listen.setMinWidth(ui.dp(72));row.listen.setMinHeight(ui.dp(48));row.listen.setTextColor(ui.accent);row.listen.setBackgroundTintList(ColorStateList.valueOf(ui.accentSoft));LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-2,-2);action.setMarginStart(ui.dp(8));layout.addView(row.listen,action);row.layout=layout;layout.setTag(row);recycled=layout;
            }else row=(VoiceRow)recycled.getTag();
            Catalog.VoiceSpec voice=filteredVoices.get(position);boolean selected=voice.id.equals(selectedVoice),playing=voice.id.equals(previews.voice());row.check.setVisibility(selected?View.VISIBLE:View.INVISIBLE);row.name.setText(voice.label);Catalog.Model model=Catalog.findModel(voice.modelId);row.locale.setText(voice.languageTag+(model.network?" · Online":" · Offline"));row.layout.setBackground(ui.ripple(selected?ui.accentSoft:ui.surface,selected?ui.accent:ui.border,12));row.layout.setContentDescription(voice.label+", "+languageLabel(voice.languageTag)+(selected?", selected":""));row.layout.setSelected(selected);
            row.layout.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.RadioButton");info.setCheckable(true);info.setChecked(selected);}});
            row.layout.setOnClickListener(v->chooseVoice(voice));row.listen.setText(playing?"Stop":"Listen");row.listen.setContentDescription((playing?"Stop preview of ":"Listen to ")+voice.label);row.listen.setOnClickListener(v->listen(voice));return recycled;
        }
    }
    private static final class VoiceRow{LinearLayout layout;ImageView check;TextView name,locale;Button listen;}

    private void confirmDownload(Catalog.Model model){
        String message=model.network?"Text requested through Local Voice, including by other apps, is sent to Microsoft when an online voice is used. If no installed local voice matches a requested language, an enabled online voice may be chosen before speech starts. Internet required. No fallback after a speech error.":importsFile(model)?"Choose the model ZIP. Installed size: "+formatBytes(model.bytes())+".":formatBytes(model.bytes())+" over your current connection.";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(model.network?"Enable online voices?":importsFile(model)?"Install "+shortModel(model)+"?":"Download "+shortModel(model)+"?").setMessage(message+"\n\n"+model.license+"\n"+model.licenseText).setNegativeButton("Cancel",null).setNeutralButton("Full license",null).setPositiveButton(model.network?"Enable":importsFile(model)?"Choose file":"Download",(d,which)->{if(model.network)store.enableOnline(model.id);else if(importsFile(model))chooseModelPack(model);else store.download(model.id);changed();}).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button->AboutActivity.showLicense(this,model)));dialog.show();
    }
    private void chooseModelPack(Catalog.Model model){
        pendingImportModel=model.id;importErrors.remove(model.id);
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,SELECT_MODEL_PACK);}catch(ActivityNotFoundException error){pendingImportModel="";importErrors.put(model.id,"File picker unavailable. Install an Android document provider.");changed();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=SELECT_MODEL_PACK)return;
        String id=pendingImportModel;pendingImportModel="";if(result!=RESULT_OK)return;
        Catalog.Model model=Catalog.findModel(id);Uri uri=data==null?null:data.getData();
        if(model==null||!importsFile(model))return;
        if(uri==null||!"content".equals(uri.getScheme())){importErrors.put(id,"No readable model file selected. Choose the model ZIP again.");changed();return;}
        importErrors.remove(id);try{store.importModel(id,uri);}catch(SecurityException|IllegalArgumentException unreadable){importErrors.put(id,"Cannot read the selected model file. Choose it again.");}showDestination(1);changed();
    }
    private void modelDetails(Catalog.Model model){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shortModel(model)).setMessage(model.description+"\n\n"+model.license).setPositiveButton("Close",null).setNeutralButton("License",null).setNegativeButton(model.network?"Disable":"Remove files",(d,which)->new AlertDialog.Builder(this).setTitle(model.network?"Disable online voices?":"Remove downloaded files?").setNegativeButton("Cancel",null).setPositiveButton("Remove",(confirm,id)->store.remove(model.id,false)).show()).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button->AboutActivity.showLicense(this,model)));dialog.show();
    }
    private void updateCatalogStatus(){if(catalogStatus!=null){setTextIfChanged(catalogStatus,OnlineCatalogUi.status());setEnabledIfChanged(refreshCatalog,!OnlineCatalogUi.refreshing());setVisibleIfChanged(cancelRefresh,OnlineCatalogUi.refreshing()?View.VISIBLE:View.GONE);}}
    private static void setTextIfChanged(TextView view,String text){if(!TextUtils.equals(view.getText(),text))view.setText(text);}
    private static void setColorIfChanged(TextView view,int color){if(view.getCurrentTextColor()!=color)view.setTextColor(color);}
    private static void setVisibleIfChanged(View view,int visibility){if(view.getVisibility()!=visibility)view.setVisibility(visibility);}
    private static void setEnabledIfChanged(View view,boolean enabled){if(view.isEnabled()!=enabled)view.setEnabled(enabled);}
    private void refreshOnlineCatalog(){OnlineCatalogUi.refresh(this,catalogWorker);}
    private void cancelOnlineCatalog(){OnlineCatalogUi.cancel();}
    private static String shortModel(Catalog.Model model){int split=model.label.indexOf(" · ");return split<0?model.label:model.label.substring(0,split);}
    private static boolean importsFile(Catalog.Model model){return "import".equals(model.config.get("installMode"));}
    private static String voiceCountLabel(int count){return count+(count==1?" voice":" voices");}
    private static String modelMetadata(Catalog.Model model){if(model.network){Set<String> locales=new HashSet<>();for(Catalog.VoiceSpec voice:model.voices)locales.add(voice.languageTag);return voiceCountLabel(model.voices.size())+" · "+locales.size()+" locales";}return String.join(", ",model.languages)+" · "+voiceCountLabel(model.voices.size());}
    private static String languageLabel(String tag){return Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault());}
    private static String speedText(float value){return String.format(Locale.ROOT,"%.1fx",value);}
    public static String formatBytes(long bytes){return String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0);}
    @Override protected void onStart(){super.onStart();store.addListener(this);OnlineCatalogUi.addListener(catalogListener);changed();}
    @Override protected void onStop(){store.removeListener(this);OnlineCatalogUi.removeListener(catalogListener);super.onStop();}
    @Override protected void onDestroy(){previews.close();++filterGeneration;if(pendingFilter!=null)uiHandler.removeCallbacks(pendingFilter);if(tts!=null)tts.shutdown();catalogWorker.shutdown();voiceWorker.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("destination",destination);state.putString("language",languageTag);state.putString("pendingImportModel",pendingImportModel);state.putString("search",search.getText().toString());state.putString("sample",sample.getText().toString());super.onSaveInstanceState(state);}
}
