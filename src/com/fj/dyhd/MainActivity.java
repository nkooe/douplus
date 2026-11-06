package com.fj.dyhd;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String PREF = "dyhd";

    private static final int BG        = 0xFF0E1014;
    private static final int CARD      = 0xFF171A21;
    private static final int TEXT_MAIN = 0xFFF2F4F8;
    private static final int TEXT_SUB  = 0xFF8B93A1;
    private static final int ACCENT    = 0xFFFE2C55;
    private static final int ACCENT_2  = 0xFF25F4EE;

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int color, float radiusDp, Integer strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != null) d.setStroke((int) dp(1), strokeColor);
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18, 0x14FFFFFF));
        c.setPadding((int) dp(18), (int) dp(18), (int) dp(18), (int) dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView tv(String text, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) dp(18), (int) dp(38), (int) dp(18), (int) dp(30));
        scroll.addView(root);

        // ---------- header ----------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) dp(52), (int) dp(52));
        ilp.rightMargin = (int) dp(14);
        icon.setLayoutParams(ilp);
        head.addView(icon);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = tv("抖plus", 26f, TEXT_MAIN, true);
        TextView t2 = tv("无水印 · 可选画质下载", 13f, TEXT_SUB, false);
        titles.addView(t1);
        titles.addView(t2);
        head.addView(titles);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = (int) dp(22);
        head.setLayoutParams(hlp);
        root.addView(head);

        // ---------- switch card ----------
        LinearLayout c1 = card();
        final String[][] switches = {
            {"enabled",  "高画质下载", "长按视频弹出画质选择"},
            {"emoji",    "表情下载",   "表情预览页长按「添加表情」自动转格式"},
            {"autoPlay", "自动播放",   "当前视频播完自动下一个"}
        };
        for (int i = 0; i < switches.length; i++) {
            final String key = switches[i][0];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (i > 0) row.setPadding(0, (int) dp(14), 0, 0);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(tv(switches[i][1], 16f, TEXT_MAIN, true));
            TextView sub = tv(switches[i][2], 12f, TEXT_SUB, false);
            sub.setPadding(0, (int) dp(3), 0, 0);
            texts.addView(sub);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            texts.setLayoutParams(tlp);
            row.addView(texts);

            Switch sw = new Switch(this);
            sw.setChecked(sp.getBoolean(key, true));
            // 双保险：部分 ROM 上 OnCheckedChange 不回调，再挂一层 OnClick 兜底
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    boolean ok = sp.edit().putBoolean(key, v).commit();
                    android.util.Log.i("DYHD", "pref " + key + "=" + v + " commit=" + ok);
                }
            });
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Switch s2 = (Switch) view;
                    boolean v = s2.isChecked();
                    if (sp.getBoolean(key, true) != v) {
                        boolean ok = sp.edit().putBoolean(key, v).commit();
                        android.util.Log.i("DYHD", "pref(clk) " + key + "=" + v + " commit=" + ok);
                    }
                }
            });
            row.addView(sw);
            c1.addView(row);
        }
        root.addView(c1);

        // ---------- steps card ----------
        LinearLayout c2 = card();
        TextView h2 = tv("使用步骤", 15f, ACCENT_2, true);
        h2.setPadding(0, 0, 0, (int) dp(10));
        c2.addView(h2);
        String[] steps = new String[]{
                "1  在 LSPosed 中启用「抖plus」",
                "2  作用域勾选「抖音」",
                "3  回到本页打开上面的开关",
                "4  强制停止抖音后重新打开",
                "5  长按任意视频 →「高画质下载」→ 选画质"
        };
        for (String s : steps) {
            TextView t = tv(s, 13f, TEXT_MAIN, false);
            t.setPadding(0, (int) dp(5), 0, (int) dp(5));
            t.setLineSpacing(dp(3), 1f);
            c2.addView(t);
        }
        root.addView(c2);

        // ---------- info card ----------
        LinearLayout c3 = card();
        TextView h3 = tv("说明", 15f, ACCENT, true);
        h3.setPadding(0, 0, 0, (int) dp(10));
        c3.addView(h3);
        String[] notes = new String[]{
                "· 保存到 Download/douyin",
                "· 画质列表按码率从高到低",
                "·「原始画质」取 playAddr，通常最清晰",
                "· 动图转 MP4，静图转 JPG（微信可直接发）",
                "· 地址来自无水印通道，不含抖音水印"
        };
        for (String s : notes) {
            TextView t = tv(s, 12.5f, TEXT_SUB, false);
            t.setPadding(0, (int) dp(4), 0, (int) dp(4));
            t.setLineSpacing(dp(2), 1f);
            c3.addView(t);
        }
        root.addView(c3);

        // ---------- footer ----------
        TextView foot = tv("v2.1 · 适配抖音 41.0.0", 11f, 0xFF5A6270, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, (int) dp(8), 0, 0);
        root.addView(foot);

        setContentView(scroll);
    }
}
