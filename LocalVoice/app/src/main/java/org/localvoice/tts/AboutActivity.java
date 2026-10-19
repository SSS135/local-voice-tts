package org.localvoice.tts;

import android.app.*;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Notices and technical detail are deliberately outside everyday voice controls. */
public final class AboutActivity extends Activity {
    @Override public void onCreate(Bundle state){
        Ui.theme(this);super.onCreate(state);Ui ui=new Ui(this);LinearLayout root=ui.root();setContentView(root);ui.appBar(root,"About","Back",this::finish);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout page=ui.page(scroll);
        LinearLayout app=ui.card(page);ui.text(app,"Local Voice",22,ui.ink,true);
        String version="";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}
        ui.text(app,"Version "+version,14,ui.muted,false);
        ui.button(app,"Open-source notices",false).setOnClickListener(v->showText(this,"Open-source notices",Catalog.ATTRIBUTION+"\n\n"+notice("SENTENCEPIECE-NOTICE.txt")+"\n\n"+notice("SENTENCEPIECE-LICENSE.txt")+"\n\n"+notice("DARTS-CLONE-LICENSE.txt")+"\n\n"+noticeAsset("legal/THIRD_PARTY_SONIC_NOTICES.txt")+"\n\n"+noticeAsset("legal/SONIC-APACHE-2.0-LICENSE.txt")+"\n\n"+noticeAsset("legal/THIRD_PARTY_POCKET_NOTICES.txt")+"\n\n"+noticeAsset("legal/POCKET-TTS-MIT-LICENSE.txt")));
        ui.heading(page,"Model licenses");
        for(Catalog.Model model:Catalog.MODELS){LinearLayout card=ui.card(page);ui.text(card,model.label,17,ui.ink,true);ui.text(card,model.license,14,ui.muted,false);ui.quiet(card,"Read license").setOnClickListener(v->showLicense(this,model));}
        LinearLayout technical=ui.card(page);ui.button(technical,"Technical details",false).setOnClickListener(v->{
            ModelStore store=new ModelStore(this);StringBuilder value=new StringBuilder("Android system TTS engine\n\n");
            for(Catalog.Model model:Catalog.MODELS)value.append(model.label).append("\nBackend: ").append(model.backend).append("\nRevision: ").append(model.revision).append("\n").append(model.description).append(model.network?"":"\nStorage: "+store.modelDirectory(model.id)).append("\n\n");
            showText(this,"Technical details",value.toString().trim());
        });
    }
    static void showLicense(Activity activity,Catalog.Model model){
        String value=model.licenseText+"\n\n"+model.licenseUrl;
        if(!model.licenseAsset.isEmpty())try(InputStream input=activity.getAssets().open("licenses/"+model.licenseAsset);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);value+="\n\n"+new String(output.toByteArray(),StandardCharsets.UTF_8);
        }catch(IOException error){value+="\n\nFull license is available at the link above.";}
        showText(activity,model.label+" license",value);
    }
    private String notice(String name){return noticeAsset("licenses/"+name);}
    private String noticeAsset(String path){try(InputStream input=getAssets().open(path);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return new String(output.toByteArray(),StandardCharsets.UTF_8);}catch(IOException error){return "Notice unavailable: "+path;}}
    static void showText(Activity activity,String title,String value){
        Ui ui=new Ui(activity);TextView content=new TextView(activity);content.setText(value);content.setTextSize(16);content.setTextColor(ui.ink);content.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(12));content.setTextIsSelectable(true);Linkify.addLinks(content,Linkify.WEB_URLS);content.setMovementMethod(LinkMovementMethod.getInstance());ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("Close",null).show();
    }
}
