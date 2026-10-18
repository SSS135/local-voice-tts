package org.localvoice.tts;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.*;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Small platform-native settings screen; all synthesis is sent through Android's TTS API. */
public final class MainActivity extends Activity implements ModelStore.Listener {
    private ModelStore store;
    private LinearLayout modelRows;
    private Spinner voices;
    private TextView voiceStatus, previewStatus, backendStatus;
    private EditText previewText;
    private TextToSpeech tts;
    private boolean ttsReady;
    private List<Catalog.VoiceSpec> visibleVoices = Collections.emptyList();
    private final Map<String, TextView> statuses = new HashMap<>();
    private final Map<String, ProgressBar> bars = new HashMap<>();
    private final Map<String, Button> downloads = new HashMap<>(), cancels = new HashMap<>();
    private String voiceSignature = "";
    private final Runnable backendStatusListener = () -> {
        if (backendStatus != null) backendStatus.setText(BackendStatus.text());
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = new ModelStore(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20); page.setPadding(pad,pad,pad,pad); scroll.addView(page); setContentView(scroll);
        text(page, "LocalVoice", 28);
        text(page, "Local voices stay on this device. The base app includes no model weights. Model downloads happen only when you choose Download.", 16);
        Button system = button(page, "Open Android text-to-speech settings");
        system.setOnClickListener(v -> {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });
        text(page, "1  Download or enable voices", 21);
        modelRows = new LinearLayout(this); modelRows.setOrientation(LinearLayout.VERTICAL); page.addView(modelRows);
        for (Catalog.Model model : Catalog.MODELS) addModel(model);
        text(page, "2  Choose your default voice", 21);
        voiceStatus = text(page, "", 15);
        voices = new Spinner(this); page.addView(voices);
        button(page, "Use selected voice").setOnClickListener(v -> {
            int position = voices.getSelectedItemPosition();
            if (position >= 0 && position < visibleVoices.size()) {
                Catalog.VoiceSpec voice = visibleVoices.get(position);
                store.selectVoice(voice.id);
                voiceStatus.setText("Default: " + voice.label + " · " + voice.languageTag);
            }
        });
        text(page, "3  Preview through Android TTS", 21);
        previewText = new EditText(this); previewText.setHint("Text to speak"); previewText.setText("Hello! This is your LocalVoice speech engine.");
        previewText.setMinLines(2); previewText.setGravity(android.view.Gravity.TOP); page.addView(previewText);
        previewStatus = text(page, "Connecting to LocalVoice…", 14);
        backendStatus = text(page, BackendStatus.text(), 14);
        backendStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button speak = button(page, "Speak preview"); speak.setOnClickListener(v -> speak());
        Button stop = button(page, "Cancel current speech request");
        stop.setOnClickListener(v -> { BackendStatus.cancel(); if (tts != null) tts.stop(); });
        text(page, "Other apps control their own queued requests. Use their Stop control to clear their queue.", 14);
        text(page, "In another app, choose LocalVoice as the Android TTS engine. If no local model is installed, return here to download one. Online voices send preview and app text to Microsoft only when explicitly selected.", 14);
        Button about = button(page, "Licenses and source attribution");
        about.setOnClickListener(v -> showText("Licenses and attribution", Catalog.ATTRIBUTION));
        tts = new TextToSpeech(this, status -> runOnUiThread(() -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            previewStatus.setText(ttsReady ? "Ready. Preview uses Android's LocalVoice engine." : "Engine is unavailable. Reopen the app after installation.");
        }), getPackageName());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { runOnUiThread(() -> previewStatus.setText("Speaking…")); }
            @Override public void onDone(String id) { runOnUiThread(() -> previewStatus.setText("Preview complete.")); }
            @Override public void onError(String id) { runOnUiThread(() -> previewStatus.setText("Speech failed. Check the installed model or the connection for an online voice.")); }
        });
        changed();
    }
    private void addModel(Catalog.Model model) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0,dp(16),0,dp(16)); modelRows.addView(card);
        text(card, model.label, 19);
        text(card, String.join(", ", model.languages) + " · " + model.voices.size() + " voices" + (model.network ? " · internet required" : " · " + formatBytes(model.bytes()) + " download"), 14);
        text(card, model.description, 14);
        Button license = button(card, "Model license: " + model.license);
        license.setOnClickListener(v -> showText(model.label + " license", licenseText(model)));
        TextView status = text(card, "", 14); statuses.put(model.id, status);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); card.addView(bar); bars.put(model.id,bar);
        Button download = button(card, model.network ? "Enable online voices…" : "Download…"); downloads.put(model.id,download);
        download.setEnabled(model.available);
        download.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(model.label)
            .setMessage((model.network ? "Online speech sends the text you ask to speak to Microsoft's service. Internet is required; no offline fallback is used.\n\n" : "Download " + formatBytes(model.bytes()) + " over your current connection. Speech runs offline after installation.\n\n") + licenseText(model))
            .setNegativeButton("Cancel", null).setPositiveButton(model.network ? "Enable online voices" : "Download", (dialog, which) -> {
                if (model.network) { store.enableOnline(model.id); voiceSignature=""; changed(); }
                else store.download(model.id);
            }).show());
        Button cancel = button(card, "Cancel download"); cancels.put(model.id,cancel); cancel.setOnClickListener(v -> store.cancel(model.id));
        if(model.available) {
            button(card,model.network?"Disable online voices":"Remove model and cached download").setOnClickListener(v ->
                new AlertDialog.Builder(this).setTitle(model.network?"Disable online voices?":"Remove model?")
                    .setMessage(model.network?"These online voices will no longer be offered to Android TTS.":"This removes downloaded model files. You can download them again later.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Remove",(dialog,which)->store.remove(model.id,false)).show());
        }
    }
    @Override protected void onStart() { super.onStart(); store.addListener(this); BackendStatus.addListener(backendStatusListener); backendStatusListener.run(); changed(); }
    @Override protected void onStop() { store.removeListener(this); BackendStatus.removeListener(backendStatusListener); super.onStop(); }
    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }
    @Override public void changed() {
        for (Catalog.Model model : Catalog.MODELS) {
            ModelStore.Progress progress = store.progress(model.id);
            boolean installed = store.isInstalled(model.id);
            statuses.get(model.id).setText(!model.available ? model.description : installed ? (model.network ? "Online voices enabled. Select one below to send text online." : "Installed · ready offline") : progress.state + (progress.detail.isEmpty() ? "" : " · " + progress.detail) + (progress.running ? "\n" + formatBytes(progress.bytes) + " / " + formatBytes(progress.total) : ""));
            ProgressBar bar = bars.get(model.id); bar.setVisibility(progress.running ? View.VISIBLE : View.GONE);
            bar.setIndeterminate(progress.state.equals("Queued"));
            bar.setProgress(progress.total > 0 ? (int) (1000 * progress.bytes / progress.total) : 0);
            Button download = downloads.get(model.id); download.setEnabled(model.available && !installed && !progress.running);
            download.setText(installed ? (model.network ? "Enabled" : "Installed") : model.network ? "Enable online voices…" : progress.state.equals("Download failed") || progress.state.equals("Cancelled") ? "Retry download…" : "Download…");
            cancels.get(model.id).setVisibility(progress.running ? View.VISIBLE : View.GONE);
        }
        List<Catalog.VoiceSpec> installed = store.installedVoices();
        StringBuilder signature = new StringBuilder(store.selectedVoice());
        for (Catalog.VoiceSpec voice : installed) signature.append('|').append(voice.id);
        if (!signature.toString().equals(voiceSignature)) {
            voiceSignature = signature.toString(); visibleVoices = installed;
            List<String> labels = new ArrayList<>(); int selected = 0;
            for (int i = 0; i < installed.size(); i++) {
                Catalog.VoiceSpec voice = installed.get(i);
                labels.add(voice.label + " · " + voice.languageTag + (Catalog.findModel(voice.modelId).network ? " · online" : ""));
                if (voice.id.equals(store.selectedVoice())) selected=i;
            }
            if (labels.isEmpty()) labels.add("Download a model first");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            voices.setAdapter(adapter); voices.setEnabled(!installed.isEmpty()); voices.setSelection(selected, false);
            Catalog.VoiceSpec choice = Catalog.findVoice(store.selectedVoice());
            voiceStatus.setText(choice == null ? "Download a local model, or explicitly select an enabled online voice." : "Default: " + choice.label + " · " + choice.languageTag);
        }
    }
    private void speak() {
        String id = store.selectedVoice(); Catalog.VoiceSpec chosen = Catalog.findVoice(id);
        if (chosen == null) { previewStatus.setText("Download a local model and choose a voice first."); return; }
        if (!ttsReady) { previewStatus.setText("Android TTS is still connecting. Try again shortly."); return; }
        Set<Voice> available = tts.getVoices(); Voice androidVoice = null;
        if (available != null) for (Voice voice : available) if (voice.getName().equals(id)) { androidVoice=voice; break; }
        if (androidVoice == null || tts.setVoice(androidVoice) != TextToSpeech.SUCCESS) { previewStatus.setText("Voice is unavailable. Try reopening LocalVoice after download."); return; }
        Bundle params = new Bundle();
        int result = tts.speak(previewText.getText().toString(), TextToSpeech.QUEUE_FLUSH, params, "localvoice-preview");
        previewStatus.setText(result == TextToSpeech.SUCCESS ? "Preview requested: " + chosen.label : "Android TTS could not start this preview.");
    }
    private void showText(String title, String value) {
        TextView content = new TextView(this); content.setText(value); content.setPadding(dp(20),dp(12),dp(20),dp(12)); content.setTextIsSelectable(true);
        Linkify.addLinks(content, Linkify.WEB_URLS); content.setMovementMethod(LinkMovementMethod.getInstance());
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }
    private String licenseText(Catalog.Model model) {
        String value=model.licenseText + "\n\n" + model.licenseUrl;
        if (!model.licenseAsset.isEmpty()) try (InputStream input=getAssets().open("licenses/"+model.licenseAsset); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
            value += "\n\n" + new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch(IOException e) { value += "\n\nFull license is available at the link above."; }
        return value;
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0,dp(7),0,dp(7)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String value) { Button view = new Button(this); view.setText(value); parent.addView(view); return view; }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    public static String formatBytes(long bytes) { return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0); }
}
