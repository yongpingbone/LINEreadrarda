package com.example.linereadradar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
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
import android.widget.Toast;

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
        TextView intro = text("測試 LINE 能不能留在第二畫面。新版會先使用 Android 官方螢幕分享授權建立 Public Virtual Display。", 13, false, MUTED);
        intro.setPadding(0, dp(5), 0, dp(15));
        body.addView(intro);

        LinearLayout display = card();
        display.addView(text("Virtual Display 測試", 19, true, TEXT));
        display.addView(text("按一次即可。Android 會先跳出螢幕分享／錄製授權視窗；如果可以選範圍，請選「整個螢幕」，再按允許。Radar 接著才會建立第二 Display 並嘗試啟動 LINE。", 13, false, MUTED));
        TextView privacy = text("注意：這個授權會讓 Android 顯示正在分享／錄製螢幕的系統提示。Radar 只用授權建立實驗 Display，畫面幀會立即丟棄，不寫入檔案。", 12, true, AMBER);
        privacy.setPadding(0, dp(8), 0, 0);
        display.addView(privacy);

        displayState = text("尚未測試", 14, true, AMBER);
        displayState.setPadding(0, dp(12), 0, dp(12));
        display.addView(displayState);

        Button oneTap = button("一鍵開始 Display 測試");
        oneTap.setOnClickListener(v -> requestProjectionAndRun());
        display.addView(oneTap);

        Button stop = quietButton("停止 Display 測試");
        stop.setOnClickListener(v -> {
            ProjectionForegroundService.stop(this);
            VirtualDisplayEngine.release();
            displayState.setText("第二畫面已關閉");
            displayState.setTextColor(AMBER);
        });
        display.addView(stop, margin(0, 8, 0, 0));
        body.addView(display, margin(0, 0, 0, 12));

        LinearLayout readProtection = card();
        readProtection.addView(text("No-Read 保護", 18, true, TEXT));
        readProtection.addView(text("Virtual Display 成功不代表 No-Read 已生效。No-Read 仍需要 LSPosed / LSPatch 類框架把實驗 hook 載入 LINE，最後還必須由另一個帳號確認真的沒有出現已讀。", 12, false, MUTED));
        Button explain = quietButton("看懂 No-Read 是什麼");
        explain.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("No-Read 的作用")
            .setMessage("Virtual Display 負責把 LINE 放到第二畫面。No-Read 則嘗試阻止 Radar 在第二畫面查看聊天室時造成的已讀。\n\n兩者是不同層，所以要先把 Display 測通，再驗證 No-Read。")
            .setPositiveButton("知道了", null)
            .show());
        readProtection.addView(explain, margin(0, 8, 0, 0));
        body.addView(readProtection, margin(0, 0, 0, 12));

        LinearLayout saveCard = card();
        saveCard.addView(text("每個人的訊息保存", 18, true, TEXT));
        saveCard.addView(text("通知可以顯示最新訊息；這個開關只決定是否把正文永久寫進 Radar 歷史紀錄。", 12, false, MUTED));
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

        LinearLayout result = card();
        result.addView(text("成功怎麼看？", 18, true, TEXT));
        result.addView(text("成功：授權後顯示第二 Display 已建立，而且 LINE 沒有搶回主畫面。\n\n失敗：如果第二 Display 建立成功，但 LINE 啟動仍被 Android 拒絕，把完整錯誤截圖給我。那就代表下一個限制是在「把外部 App 放到第二 Display」，不是建立 Display 本身。", 12, false, MUTED));
        body.addView(result, margin(0, 0, 0, 16));

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
        displayState.setText("① 請在 Android 系統視窗允許螢幕分享；如果能選，請選整個螢幕");
        displayState.setTextColor(AMBER);
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
            showFailure("你沒有允許螢幕分享，所以無法建立 Public Virtual Display");
            return;
        }

        displayState.setText("② 授權成功，正在建立第二 Display…");
        displayState.setTextColor(AMBER);
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
                displayState.setText("③ Display " + id + " 已建立，正在把 LINE 放進去…");
                VirtualDisplayEngine.Result launch = VirtualDisplayEngine.launchLine(this);
                if (launch.success) {
                    displayState.setText("✓ 已要求 LINE 進入 Display " + launch.displayId + "\n現在觀察主畫面有沒有被 LINE 搶走。");
                    displayState.setTextColor(GREEN);
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

    private void refreshDisplayState() {
        if (displayState == null) return;
        int id = VirtualDisplayEngine.displayId();
        if (id >= 0) {
            displayState.setText("✓ 第二畫面運作中 · Display " + id);
            displayState.setTextColor(GREEN);
        }
    }

    private void showFailure(String message) {
        displayState.setText("✕ Display 測試失敗\n" + message);
        displayState.setTextColor(RED);
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
