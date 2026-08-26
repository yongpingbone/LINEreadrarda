package com.example.linereadradar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    private Prefs prefs;
    private TextView displayState;

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
        TextView intro = text("這頁只做一件事：測試 LINE 能不能留在第二畫面，讓 Radar 背景讀取。", 13, false, MUTED);
        intro.setPadding(0, dp(5), 0, dp(15));
        body.addView(intro);

        LinearLayout display = card();
        display.addView(text("Virtual Display 測試", 19, true, TEXT));
        display.addView(text("你不用分步驟操作。直接按下面按鈕，Radar 會先建立第二畫面，再嘗試把 LINE 放進去。", 13, false, MUTED));
        displayState = text("尚未測試", 14, true, AMBER);
        displayState.setPadding(0, dp(12), 0, dp(12));
        display.addView(displayState);

        Button oneTap = button("一鍵開始 Display 測試");
        oneTap.setOnClickListener(v -> runDisplayTest());
        display.addView(oneTap);

        Button stop = quietButton("關閉第二畫面");
        stop.setOnClickListener(v -> {
            VirtualDisplayEngine.release();
            refreshDisplayState();
            Toast.makeText(this, "第二畫面已關閉", Toast.LENGTH_SHORT).show();
        });
        display.addView(stop, margin(0, 8, 0, 0));
        body.addView(display, margin(0, 0, 0, 12));

        LinearLayout readProtection = card();
        readProtection.addView(text("No-Read 保護", 18, true, TEXT));
        readProtection.addView(text("這不是普通 Android 權限。APK 雖然包含實驗 hook，但只有 LSPosed / LSPatch 類框架真的把 hook 載入 LINE 時才會生效。", 12, false, MUTED));
        TextView warning = text("所以目前先把 Display 測通。No-Read 是否真的阻止已讀，之後一定要用另一個帳號驗證，Radar 不能自己判定成功。", 12, true, AMBER);
        warning.setPadding(0, dp(8), 0, dp(8));
        readProtection.addView(warning);
        Button explain = quietButton("看懂 No-Read 是什麼");
        explain.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("No-Read 的作用")
            .setMessage("Virtual Display 只是把 LINE 放到第二畫面，不會自動阻止已讀。\n\nNo-Read 的工作是：當 Radar 在第二畫面查看聊天室時，嘗試攔住 LINE 的已讀回報。\n\n你自己正常在主畫面打開 LINE 時，不應該被阻止。")
            .setPositiveButton("知道了", null)
            .show());
        readProtection.addView(explain);
        body.addView(readProtection, margin(0, 0, 0, 12));

        LinearLayout privacy = card();
        privacy.addView(text("每個人的訊息保存", 18, true, TEXT));
        privacy.addView(text("通知可以顯示最新訊息。這個開關只決定是否把正文永久寫進 Radar 歷史紀錄。", 12, false, MUTED));

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
            privacy.addView(row);
        }
        if (found == 0) {
            TextView none = text("目前沒有監控對象，先回主頁新增人物。", 13, false, MUTED);
            none.setPadding(0, dp(10), 0, dp(4));
            privacy.addView(none);
        }
        body.addView(privacy, margin(0, 0, 0, 12));

        LinearLayout result = card();
        result.addView(text("你只要看結果", 18, true, TEXT));
        result.addView(text("成功：LINE 沒有搶回主畫面，而且 Radar 顯示第二 Display 運作中。\n\n失敗：如果跳出 SecurityException 或 LINE 還是回到主畫面，把錯誤截圖給我，我會繼續針對你這台手機調整。", 12, false, MUTED));
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

    private void runDisplayTest() {
        displayState.setText("① 正在建立第二畫面…");
        displayState.setTextColor(AMBER);

        VirtualDisplayEngine.Result create = VirtualDisplayEngine.create(this);
        if (!create.success) {
            displayState.setText("✕ 建立第二畫面失敗\n" + create.message);
            displayState.setTextColor(RED);
            return;
        }

        displayState.setText("② Display " + create.displayId + " 已建立，正在啟動 LINE…");
        VirtualDisplayEngine.Result launch = VirtualDisplayEngine.launchLine(this);
        if (!launch.success) {
            displayState.setText("✕ 第二畫面已建立，但 LINE 無法進入\n" + launch.message);
            displayState.setTextColor(RED);
            return;
        }

        displayState.setText("✓ 已要求 LINE 進入 Display " + launch.displayId + "\n現在觀察 LINE 是否搶回主畫面。");
        displayState.setTextColor(GREEN);
    }

    private void refreshDisplayState() {
        if (displayState == null) return;
        int id = VirtualDisplayEngine.displayId();
        if (id >= 0) {
            displayState.setText("✓ 第二畫面運作中 · Display " + id);
            displayState.setTextColor(GREEN);
        } else {
            displayState.setText("尚未測試");
            displayState.setTextColor(AMBER);
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
