package com.example.linereadradar;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class VirtualDisplayProbeActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 28);
    private static final int CARD = Color.rgb(16, 23, 51);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(160, 171, 205);
    private static final int GREEN = Color.rgb(95, 222, 171);
    private static final int PURPLE = Color.rgb(173, 103, 255);

    private TextView displayState;
    private TextView launchState;
    private TextView accessibilityState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);

        TextView title = text("v0.5 Virtual Display Probe ✦", 24, true, TEXT);
        body.addView(title);
        TextView intro = text("只測試第二螢幕，不會改動 v0.4.1 的監控名單。請依序完成 1 → 2 → 3。", 14, false, MUTED);
        intro.setPadding(0, dp(6), 0, dp(16));
        body.addView(intro);

        LinearLayout card1 = card();
        card1.addView(text("1. 建立第二螢幕", 18, true, TEXT));
        displayState = text("尚未測試", 13, false, MUTED);
        displayState.setPadding(0, dp(6), 0, dp(10));
        card1.addView(displayState);
        Button create = button("建立 Virtual Display");
        create.setOnClickListener(v -> {
            VirtualDisplayProbeEngine.Result r = VirtualDisplayProbeEngine.create(this);
            displayState.setText((r.success ? "✓ " : "✕ ") + r.message);
            displayState.setTextColor(r.success ? GREEN : Color.rgb(255, 130, 150));
        });
        card1.addView(create);
        body.addView(card1, margin(0, 0, 0, 12));

        LinearLayout card2 = card();
        card2.addView(text("2. 把 LINE 送到第二螢幕", 18, true, TEXT));
        launchState = text("尚未測試", 13, false, MUTED);
        launchState.setPadding(0, dp(6), 0, dp(10));
        card2.addView(launchState);
        Button launch = button("在 Virtual Display 啟動 LINE");
        launch.setOnClickListener(v -> {
            VirtualDisplayProbeEngine.Result r = VirtualDisplayProbeEngine.launchLine(this);
            launchState.setText((r.success ? "✓ " : "✕ ") + r.message);
            launchState.setTextColor(r.success ? GREEN : Color.rgb(255, 130, 150));
        });
        card2.addView(launch);
        body.addView(card2, margin(0, 0, 0, 12));

        LinearLayout card3 = card();
        card3.addView(text("3. Accessibility 跨螢幕掃描", 18, true, TEXT));
        accessibilityState = text("尚未收到 Probe Accessibility 結果", 13, false, MUTED);
        accessibilityState.setPadding(0, dp(6), 0, dp(10));
        card3.addView(accessibilityState);

        Button enable = button(isProbeAccessibilityEnabled() ? "✓ Probe 無障礙已開啟" : "開啟 Probe 無障礙服務");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        card3.addView(enable);

        Button refresh = button("重新讀取掃描結果");
        refresh.setOnClickListener(v -> refreshStatus());
        card3.addView(refresh, margin(0, 8, 0, 0));
        body.addView(card3, margin(0, 0, 0, 12));

        LinearLayout resultCard = card();
        resultCard.addView(text("成功判定", 18, true, TEXT));
        TextView rule = text(
            "如果第 3 步出現「Display 1 以上: LINE visible to Accessibility」，而且 nodes / text 有數值，就代表我們可以讓 LINE 留在第二螢幕，不搶主畫面，下一版再把 5 人輪巡搬過去。",
            13, false, MUTED);
        rule.setPadding(0, dp(7), 0, 0);
        resultCard.addView(rule);
        body.addView(resultCard);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button close = button("回到 LINE Radar");
        close.setOnClickListener(v -> finish());
        root.addView(close, margin(0, 12, 0, 0));
        setContentView(root);
    }

    private void refreshStatus() {
        if (displayState != null) {
            int id = VirtualDisplayProbeEngine.displayId();
            if (id >= 0) {
                displayState.setText("✓ Virtual Display 運作中 · Display " + id);
                displayState.setTextColor(GREEN);
            }
        }
        if (accessibilityState != null) {
            String value = getSharedPreferences("radar_probe", MODE_PRIVATE)
                .getString("accessibility", "尚未收到 Probe Accessibility 結果");
            accessibilityState.setText(value);
            accessibilityState.setTextColor(value.contains("LINE visible") ? GREEN : MUTED);
        }
    }

    private boolean isProbeAccessibilityEnabled() {
        String expected = new ComponentName(this, VirtualDisplayProbeAccessibilityService.class).flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) if (expected.equalsIgnoreCase(splitter.next())) return true;
        return false;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(rounded(CARD, 20, Color.rgb(43, 55, 100), 1));
        return l;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(rounded(Color.rgb(35, 48, 98), 17, Color.rgb(91, 120, 220), 1));
        return b;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
