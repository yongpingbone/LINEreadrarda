package com.example.linereadradar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class ExperimentalLabActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 28);
    private static final int CARD = Color.rgb(16, 23, 51);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(160, 171, 205);
    private static final int GREEN = Color.rgb(95, 222, 171);
    private static final int AMBER = Color.rgb(255, 202, 108);
    private static final int RED = Color.rgb(255, 130, 150);
    private static final int RC_PROJECTION = 5505;

    private Prefs prefs;
    private TextView displayState;
    private TextView nextStep;
    private MediaProjectionManager projectionManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDisplayState();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);

        body.addView(text("Radar Lab ✦", 25, true, TEXT));
        TextView intro = text("這裡只處理第二螢幕。成功後，LINE 可以留在第二 Display，主畫面可照常使用其他 App，不需要懸浮視窗。", 13, false, MUTED);
        intro.setPadding(0, dp(5), 0, dp(15));
        body.addView(intro);

        LinearLayout statusCard = card();
        statusCard.addView(text("目前狀態", 19, true, TEXT));
        displayState = text("尚未建立第二螢幕", 16, true, AMBER);
        displayState.setPadding(0, dp(10), 0, dp(5));
        statusCard.addView(displayState);
        nextStep = text("按下面的「啟動第二螢幕」開始。", 13, false, MUTED);
        statusCard.addView(nextStep);
        body.addView(statusCard, margin(0, 0, 0, 12));

        LinearLayout display = card();
        display.addView(text("啟動第二螢幕", 18, true, TEXT));
        display.addView(text("Android 會跳出螢幕分享／錄製授權。如果可以選範圍，選「整個螢幕」後按允許。Radar 只用這個授權建立實驗 Display，不會儲存影片。", 12, false, MUTED));

        Button oneTap = button("啟動第二螢幕");
        oneTap.setOnClickListener(v -> requestProjectionAndRun());
        display.addView(oneTap, margin(0, 12, 0, 0));

        Button stop = quietButton("停止第二螢幕");
        stop.setOnClickListener(v -> {
            ProjectionForegroundService.stop(this);
            VirtualDisplayEngine.release();
            prefs.setVirtualDisplayStopped("第二畫面已手動關閉");
            refreshDisplayState();
        });
        display.addView(stop, margin(0, 8, 0, 0));
        body.addView(display, margin(0, 0, 0, 12));

        LinearLayout lockWarning = card();
        lockWarning.addView(text("⚠ 關螢幕／鎖屏限制", 17, true, AMBER));
        lockWarning.addView(text("目前這個版本的第二 Display 是 MediaProjection。Android 會在螢幕鎖定時停止 MediaProjection，所以鎖屏後這個第二螢幕會中斷。這不是省電設定造成的。真正的息屏持續監控需要改用 Shizuku / shell virtual display。", 12, false, MUTED));
        body.addView(lockWarning, margin(0, 0, 0, 12));

        LinearLayout readProtection = card();
        readProtection.addView(text("No-Read 保護", 18, true, TEXT));
        readProtection.addView(text("你已實機確認目前監控過程不會讓對方看到你已讀。這部分先維持，不再改動。", 12, false, MUTED));
        body.addView(readProtection, margin(0, 0, 0, 12));

        LinearLayout saveCard = card();
        saveCard.addView(text("每個人的訊息保存", 18, true, TEXT));
        saveCard.addView(text("通知可顯示最新訊息；下面的開關只決定是否把正文永久寫進 Radar 歷史紀錄。", 12, false, MUTED));
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
            TextView none = text("目前沒有監控對象，先回主頁新增人物。", 13, false, MUTED);
            none.setPadding(0, dp(10), 0, dp(4));
            saveCard.addView(none);
        }
        body.addView(saveCard, margin(0, 0, 0, 12));

        Button accessibility = quietButton("開啟無障礙設定");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(accessibility);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button back = button("回到 LINE Radar");
        back.setOnClickListener(v -> finish());
        root.addView(back, margin(0, 12, 0, 0));
        setContentView(root);
    }

    private void requestProjectionAndRun() {
        if (projectionManager == null) {
            showFailure("這台手機沒有 MediaProjectionManager");
            return;
        }
        displayState.setText("① 等待 Android 授權");
        displayState.setTextColor(AMBER);
        nextStep.setText("在系統視窗選「整個螢幕」並按允許。完成後 Radar 會自動建立第二 Display。 ");
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), RC_PROJECTION);
        } catch (Throwable t) {
            showFailure(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            showFailure("你沒有允許螢幕分享，所以第二 Display 沒有建立");
            return;
        }

        displayState.setText("② 授權成功，正在建立第二 Display…");
        displayState.setTextColor(AMBER);
        nextStep.setText("請稍候，接下來會自動把 LINE 啟動要求送到第二畫面。 ");
        try {
            ProjectionForegroundService.start(this, resultCode, data);
            waitForDisplay(0);
        } catch (Throwable t) {
            showFailure(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    private void waitForDisplay(int attempt) {
        handler.postDelayed(() -> {
            int id = VirtualDisplayEngine.displayId();
            if (id >= 0) {
                displayState.setText("③ 第二螢幕已建立 · Display " + id);
                displayState.setTextColor(GREEN);
                nextStep.setText("正在把 LINE 啟動要求送到 Display " + id + "…");

                VirtualDisplayEngine.Result launch = VirtualDisplayEngine.launchLine(this);
                if (launch.success) {
                    prefs.setVirtualDisplayRunning(id, "第二畫面運作中 · Display " + id);
                    displayState.setText("✓ 第二螢幕已開啟 · Display " + id);
                    displayState.setTextColor(GREEN);
                    nextStep.setText("LINE 啟動要求已送出。現在可以直接按 Home 回主畫面，滑其他 App；不需要懸浮視窗。請先不要鎖屏。 ");
                    showSuccess(id);
                } else {
                    showFailure(launch.message);
                }
                return;
            }

            if (attempt >= 12) {
                showFailure(VirtualDisplayEngine.status());
                return;
            }
            waitForDisplay(attempt + 1);
        }, 250L);
    }

    private void showSuccess(int displayId) {
        new AlertDialog.Builder(this)
            .setTitle("第二螢幕已開啟 ✓")
            .setMessage(
                "Display " + displayId + " 已建立，LINE 啟動要求已送出。\n\n" +
                "接下來：\n" +
                "1. 直接按 Home 回主畫面\n" +
                "2. 正常使用 IG、Chrome、YouTube、遊戲都可以\n" +
                "3. Radar 不需要懸浮視窗\n" +
                "4. 通知欄只會保留一張必要的背景監控狀態\n\n" +
                "注意：目前 MediaProjection 模式一鎖屏就會被 Android 終止。"
            )
            .setPositiveButton("知道了", null)
            .show();
    }

    private void refreshDisplayState() {
        if (displayState == null) return;
        int id = VirtualDisplayEngine.displayId();
        if (id >= 0) {
            prefs.setVirtualDisplayRunning(id, "第二畫面運作中 · Display " + id);
            displayState.setText("✓ 第二螢幕運作中 · Display " + id);
            displayState.setTextColor(GREEN);
            nextStep.setText("可以回主畫面正常使用其他 App。Radar 會持續監控第二 Display；目前請不要鎖屏。 ");
            return;
        }

        if (prefs.virtualDisplayRunning()) {
            displayState.setText("⚠ 第二螢幕狀態需要重新確認");
            displayState.setTextColor(AMBER);
            nextStep.setText(prefs.virtualDisplayStatus());
            return;
        }

        String status = prefs.virtualDisplayStatus();
        displayState.setText("第二螢幕未運作");
        displayState.setTextColor(status.contains("鎖") || status.contains("停止") ? RED : AMBER);
        nextStep.setText(status);
    }

    private void showFailure(String message) {
        prefs.setVirtualDisplayStopped(message);
        displayState.setText("✕ 第二螢幕沒有成功啟動");
        displayState.setTextColor(RED);
        nextStep.setText(message + "\n請截這段錯誤給我。 ");
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

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
