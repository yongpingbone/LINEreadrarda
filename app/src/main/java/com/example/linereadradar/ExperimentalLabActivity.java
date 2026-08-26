package com.example.linereadradar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class ExperimentalLabActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 28);
    private static final int CARD = Color.rgb(16, 23, 51);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(160, 171, 205);
    private static final int GREEN = Color.rgb(95, 222, 171);
    private static final int AMBER = Color.rgb(255, 202, 108);
    private static final int RED = Color.rgb(255, 130, 150);
    private static final String SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases";

    private Prefs prefs;
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderBody();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button back = button("回到 LINE Radar");
        back.setOnClickListener(v -> finish());
        root.addView(back, margin(0, 12, 0, 0));
        setContentView(root);
        renderBody();
    }

    private void renderBody() {
        if (body == null) return;
        body.removeAllViews();

        body.addView(text("Radar Lab ✦", 25, true, TEXT));
        TextView intro = text("這裡是進階診斷頁。一般使用者直接在人物卡開啟「背景持續監控」即可，Radar 會自動完成 Shizuku 與第二螢幕設定。", 13, false, MUTED);
        intro.setPadding(0, dp(5), 0, dp(15));
        body.addView(intro);

        LinearLayout status = card();
        status.addView(text("環境狀態", 19, true, TEXT));
        status.addView(statusLine(ShizukuBridge.isShizukuInstalled(this), "Shizuku App", ShizukuBridge.isShizukuInstalled(this) ? "已安裝" : "未安裝"));
        status.addView(statusLine(ShizukuBridge.binderReady(), "Shizuku 服務", ShizukuBridge.stateText(this)));
        status.addView(statusLine(ShizukuBridge.permissionGranted(), "Radar Shizuku 權限", ShizukuBridge.permissionGranted() ? "已授權" : "未授權"));

        int displayId = VirtualDisplayEngine.displayId();
        boolean displayOk = displayId >= 0;
        status.addView(statusLine(displayOk, "第二螢幕", displayOk ? "Display " + displayId + " 運作中" : prefs.virtualDisplayStatus()));

        boolean lineOk = displayOk
            && prefs.secondaryLineDisplayId() == displayId
            && System.currentTimeMillis() - prefs.secondaryLineSeenAt() <= 15000L;
        status.addView(statusLine(lineOk, "LINE 第二螢幕驗證", lineOk ? "✓ Accessibility 已看到 LINE" : "尚未看到 LINE"));
        body.addView(status, margin(0, 0, 0, 12));

        LinearLayout actions = card();
        actions.addView(text("手動操作", 18, true, TEXT));

        Button download = quietButton("Shizuku GitHub 下載頁");
        download.setOnClickListener(v -> openUri(SHIZUKU_RELEASES));
        actions.addView(download, margin(0, 10, 0, 0));

        Button openShizuku = quietButton("開啟 Shizuku");
        openShizuku.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE);
            if (launch != null) startActivity(launch);
            else openUri(SHIZUKU_RELEASES);
        });
        actions.addView(openShizuku, margin(0, 8, 0, 0));

        Button permission = quietButton("重新請求 Shizuku 授權");
        permission.setOnClickListener(v -> {
            if (!ShizukuBridge.binderReady()) {
                Toast.makeText(this, "請先啟動 Shizuku", Toast.LENGTH_LONG).show();
            } else if (ShizukuBridge.permissionGranted()) {
                Toast.makeText(this, "Radar 已經取得 Shizuku 授權", Toast.LENGTH_SHORT).show();
            } else {
                ShizukuBridge.requestPermission();
            }
        });
        actions.addView(permission, margin(0, 8, 0, 0));

        Button startDisplay = button("手動啟動第二螢幕");
        startDisplay.setOnClickListener(v -> {
            if (!ShizukuBridge.permissionGranted()) {
                new AlertDialog.Builder(this)
                    .setTitle("Shizuku 尚未準備完成")
                    .setMessage("請先完成 Shizuku 安裝、無線偵錯啟動與 Radar 授權。一般情況建議回人物卡直接開背景監控，會有完整自動引導。")
                    .setPositiveButton("知道了", null)
                    .show();
                return;
            }
            ProjectionForegroundService.start(this);
            Toast.makeText(this, "正在建立第二螢幕並啟動 LINE", Toast.LENGTH_LONG).show();
        });
        actions.addView(startDisplay, margin(0, 12, 0, 0));

        Button stopDisplay = quietButton("停止第二螢幕");
        stopDisplay.setOnClickListener(v -> {
            ProjectionForegroundService.stop(this);
            prefs.setVirtualDisplayStopped("第二畫面已手動關閉");
            renderBody();
        });
        actions.addView(stopDisplay, margin(0, 8, 0, 0));

        Button accessibility = quietButton("開啟 Android 無障礙設定");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        actions.addView(accessibility, margin(0, 8, 0, 0));
        body.addView(actions, margin(0, 0, 0, 12));

        LinearLayout success = card();
        success.addView(text("什麼才算背景模式成功？", 18, true, TEXT));
        success.addView(text(
            "必須同時看到：\n" +
            "✓ Shizuku 已啟動並授權\n" +
            "✓ 第二 Display 存在\n" +
            "✓ Accessibility 在第二 Display 看得到 LINE\n\n" +
            "只有『Display 已建立』還不算成功。成功後主畫面可自由使用其他 App，也不需要懸浮視窗。",
            13, false, MUTED));
        body.addView(success, margin(0, 0, 0, 12));

        LinearLayout screenOff = card();
        screenOff.addView(text("息屏測試", 18, true, TEXT));
        screenOff.addView(text(
            "v0.6 已移除 MediaProjection 依賴，改成非 Root Shizuku + Virtual Display。第二畫面不應再因 Android 停止螢幕錄製工作階段而直接消失。\n\n" +
            "但不同 Android / Samsung 版本對虛擬顯示器與鎖屏仍可能有額外限制，因此必須實機驗證：鎖屏後 Display 是否仍存在、LINE Accessibility 是否仍可讀、已讀提醒是否能持續收到。",
            12, false, MUTED));
        body.addView(screenOff, margin(0, 0, 0, 12));

        LinearLayout readProtection = card();
        readProtection.addView(text("No-Read 保護", 18, true, TEXT));
        readProtection.addView(text("No-Read 與 Shizuku 第二螢幕是不同層。已確認有效的 No-Read 行為先維持不變；第二螢幕是否成功，仍要看上面的 LINE 驗證狀態。", 12, false, MUTED));
        body.addView(readProtection, margin(0, 0, 0, 12));

        LinearLayout saveCard = card();
        saveCard.addView(text("每個人的訊息保存", 18, true, TEXT));
        saveCard.addView(text("通知可以顯示最新訊息；這些開關只決定是否把正文永久寫進 Radar 本機歷史。", 12, false, MUTED));
        int found = 0;
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (slot.name.isEmpty()) continue;
            found++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(4));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(slot.name, 16, true, TEXT));
            labels.addView(text("保存訊息正文", 11, false, MUTED));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(slot.saveMessageContentEnabled);
            final int index = i;
            sw.setOnCheckedChangeListener((buttonView, checked) -> prefs.setSaveMessageContentEnabled(index, checked));
            row.addView(sw);
            saveCard.addView(row);
        }
        if (found == 0) {
            TextView none = text("目前沒有監控對象。", 13, false, MUTED);
            none.setPadding(0, dp(10), 0, dp(4));
            saveCard.addView(none);
        }
        body.addView(saveCard, margin(0, 0, 0, 12));
    }

    private LinearLayout statusLine(boolean ok, String label, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, 0);
        TextView mark = text(ok ? "✓" : "•", 15, true, ok ? GREEN : AMBER);
        row.addView(mark, new LinearLayout.LayoutParams(dp(28), -2));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 14, true, TEXT));
        labels.addView(text(detail, 11, false, ok ? MUTED : AMBER));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private void openUri(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "無法開啟連結", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(rounded(CARD, 20, Color.rgb(43, 55, 100), 1));
        return l;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
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

    private Button quietButton(String label) {
        Button b = button(label);
        b.setBackground(rounded(Color.rgb(25, 33, 66), 15, Color.rgb(63, 76, 128), 1));
        return b;
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
