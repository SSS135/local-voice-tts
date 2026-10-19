package org.localvoice.tts;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Small native view vocabulary shared by the two settings screens. */
final class Ui {
    final Activity activity;
    final int background, surface, ink, muted, accent, accentSoft, border, danger, success;
    final boolean dark;

    static void theme(Activity activity) {
        boolean dark=(activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        activity.setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
    }
    Ui(Activity activity) {
        this.activity=activity;
        dark=(activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        background=Color.parseColor(dark?"#111923":"#F3F6FA");
        surface=Color.parseColor(dark?"#1C2835":"#FFFFFF");
        ink=Color.parseColor(dark?"#EFF4FA":"#152334");
        muted=Color.parseColor(dark?"#B7C5D5":"#536477");
        accent=Color.parseColor(dark?"#A5C8FF":"#154E9B");
        accentSoft=Color.parseColor(dark?"#263F61":"#E8F0FD");
        border=Color.parseColor(dark?"#415064":"#D6DFEA");
        danger=Color.parseColor(dark?"#FFB4AB":"#A52324");
        success=Color.parseColor(dark?"#A0DDBD":"#176644");
        Window window=activity.getWindow();
        window.setStatusBarColor(background); window.setNavigationBarColor(background);
        int flags=dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout view=new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    LinearLayout root() {
        LinearLayout root=column(); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        return root;
    }
    LinearLayout page(ScrollView scroll) {
        scroll.setFillViewport(false); scroll.setClipToPadding(false);
        LinearLayout body=column(); body.setPadding(dp(20),dp(8),dp(20),dp(24)); scroll.addView(body);
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int side=Math.max(dp(20),((r-l)-dp(640))/2);
            if(body.getPaddingLeft()!=side) body.setPadding(side,dp(8),side,dp(24));
        });
        return body;
    }
    TextView text(LinearLayout parent,String value,int sp,int color,boolean bold) {
        TextView text=new TextView(activity); text.setText(value); text.setTextSize(sp); text.setTextColor(color);
        if(bold) text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        text.setLineSpacing(dp(2),1.08f); text.setPadding(0,dp(3),0,dp(3)); parent.addView(text);
        return text;
    }
    void heading(LinearLayout parent,String value) {
        TextView heading=text(parent,value,20,ink,true);
        heading.setPadding(0,dp(16),0,dp(10));
        if(Build.VERSION.SDK_INT>=28) heading.setAccessibilityHeading(true);
    }
    LinearLayout card(LinearLayout parent) {
        LinearLayout card=column(); card.setPadding(dp(18),dp(16),dp(18),dp(16));
        card.setBackground(shape(surface,border,18));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(12); parent.addView(card,lp);
        return card;
    }
    GradientDrawable shape(int fill,int stroke,int radius) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if(stroke!=0) drawable.setStroke(dp(1),stroke);
        return drawable;
    }
    RippleDrawable ripple(int fill,int stroke,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark?0x22FFFFFF:0x22154E9B),shape(fill,stroke,radius),null);
    }
    Button button(LinearLayout parent,String label,boolean primary) {
        Button button=new Button(activity); button.setText(label); button.setAllCaps(false); button.setSingleLine(false); button.setTextSize(16);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(dp(48));
        button.setPadding(dp(16),dp(10),dp(16),dp(10)); button.setGravity(Gravity.CENTER);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{muted,primary?(dark?Color.parseColor("#102B50"):Color.WHITE):accent}));
        button.setBackground(ripple(primary?accent:accentSoft,0,12));button.setBackgroundTintList(null);button.setStateListAnimator(null);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(8); parent.addView(button,lp);
        return button;
    }
    Button quiet(LinearLayout parent,String label) {
        Button button=button(parent,label,false);button.setBackground(ripple(surface,0,12));return button;
    }
    void space(LinearLayout parent,int height) { View space=new View(activity); parent.addView(space,new LinearLayout.LayoutParams(1,dp(height))); }
    void appBar(LinearLayout root,String title,String action,Runnable onAction) {
        LinearLayout bar=new LinearLayout(activity); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20),dp(8),dp(12),dp(8)); root.addView(bar);
        TextView name=new TextView(activity); name.setText(title); name.setTextSize(25); name.setTextColor(ink); name.setTypeface(Typeface.create("sans-serif-medium",0));
        bar.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        if(action.isEmpty()) return;
        Button more=new Button(activity); more.setText(action); more.setAllCaps(false); more.setTextColor(accent); more.setTextSize(15);
        more.setMinHeight(dp(48)); more.setMinWidth(dp(48)); more.setBackgroundTintList(ColorStateList.valueOf(background));
        bar.addView(more,new LinearLayout.LayoutParams(-2,-2)); more.setOnClickListener(v->onAction.run());
    }
}
