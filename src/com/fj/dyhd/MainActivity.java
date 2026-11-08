package com.fj.dyhd;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 纯说明页。
 * 所有功能开关与阈值都改到抖音内的面板里设置：
 * 首页 → 左上角菜单 → 滚到底 → 长按「更多功能」
 */
public class MainActivity extends Activity {

    private static final int BG        = 0xFF0E0E10;
    private static final int CARD      = 0xFF1B1B1F;
    private static final int TEXT_MAIN = 0xFFF2F4F8;
    private static final int TEXT_SUB  = 0xFF8A8A90;
    private static final int ACCENT    = 0xFF25F4EE;
    private static final int LINE      = 0xFF2E2E34;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(20);
        root.setPadding(p, (int) dp(28), p, (int) dp(28));

        // ---- 头部 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = new TextView(this);
        logo.setText("抖plus");
        logo.setTextSize(26f);
        logo.setTextColor(ACCENT);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(logo);

        TextView ver = new TextView(this);
        ver.setText("  v" + versionName());
        ver.setTextSize(13f);
        ver.setTextColor(TEXT_SUB);
        head.addView(ver);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = (int) dp(6);
        root.addView(head, hlp);

        TextView sub = new TextView(this);
        sub.setText("无水印 · 可选画质下载");
        sub.setTextSize(13.5f);
        sub.setTextColor(TEXT_SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = (int) dp(24);
        root.addView(sub, slp);

        // ---- 使用方式 ----
        LinearLayout card1 = card();
        card1.addView(sectionTitle("使用方式"));

        String[] steps = {
                "1. 在 LSPosed 中启用「抖plus」",
                "2. 作用域勾选「抖音」",
                "3. 强制停止抖音后重新打开",
                "4. 首页左上角菜单 → 滚到底部",
                "5. 长按「更多功能」唤出面板"
        };
        for (String s : steps) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(14.5f);
            t.setTextColor(TEXT_MAIN);
            t.setPadding(0, (int) dp(9), 0, (int) dp(9));
            card1.addView(t);
        }
        LinearLayout.LayoutParams c1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c1lp.bottomMargin = (int) dp(16);
        root.addView(card1, c1lp);

        // ---- 面板里能做什么 ----
        LinearLayout card2 = card();
        card2.addView(sectionTitle("面板功能"));

        String[][] feats = {
                {"高画质下载", "超高原画质 / 720P / 540P，全部无水印"},
                {"屏蔽广告",   "刷到广告自动划过"},
                {"直播跳过",   "刷到直播自动划过"},
                {"长视频跳过", "超过设定秒数自动划过（点秒数可改）"},
                {"自动播放",   "当前视频播完自动下一个"},
                {"表情下载",   "表情预览页长按自动转格式"}
        };
        for (int i = 0; i < feats.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, (int) dp(10), 0, (int) dp(10));

            TextView n = new TextView(this);
            n.setText(feats[i][0]);
            n.setTextSize(15f);
            n.setTextColor(TEXT_MAIN);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(n);

            TextView d = new TextView(this);
            d.setText(feats[i][1]);
            d.setTextSize(12.5f);
            d.setTextColor(TEXT_SUB);
            d.setPadding(0, (int) dp(3), 0, 0);
            row.addView(d);

            card2.addView(row);

            if (i < feats.length - 1) {
                View l = new View(this);
                l.setBackgroundColor(LINE);
                card2.addView(l, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(0.6f)));
            }
        }
        root.addView(card2);

        // ---- 底部提示 ----
        TextView foot = new TextView(this);
        foot.setText("配置保存在抖音内，重装模块不会丢失。");
        foot.setTextSize(12f);
        foot.setTextColor(0xFF5A5A60);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = (int) dp(22);
        root.addView(foot, flp);

        sv.addView(root);
        setContentView(sv);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) { return "?"; }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) dp(18), (int) dp(16), (int) dp(18), (int) dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16.5f);
        t.setTextColor(ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, (int) dp(8));
        return t;
    }
}