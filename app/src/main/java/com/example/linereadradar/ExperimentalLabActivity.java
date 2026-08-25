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
    private static final int PURPLE = Color.rgb(173, 103, 255);
    private static final int AMBER = Color.rgb(255, 202, 108);

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
        TextView intro = text("把 Virtual Display、No-Read 與每個人的訊息保存設定放在同一個測試頁。", 13, false, MUTED);
        intro.setPadding(0, dp(5), 0, dp(15));
        body.addView(intro);

        LinearLayout noRead = card();
        noRead.addView(text("1. No-Read 模組", 18, true, TEXT));
        noRead.addView(text("這個 APK 已包含實驗性 Xposed hook。只有經 LSPosed / LSPatch 類框架實際載入到 LINE 後才會生效。它會嘗試攔截 sendChatChecked 與已知的 mark-as-read 路徑。", 12, false, MUTED));
        TextView warning = text("重要：目前 hook 目標來自 LINE 15.0.0 的開源實作。新版 LINE 可能失效，因此第一次測試一定要用另一端確認有沒有真的保持未讀。", 12, true, AMBER);
        warning.setPadding(0, dp(8), 0, dp(8));
        noRead.addView(warning);
        Button accessibility = button("開啟無障礙設定");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        noRead.addView(accessibility);
        body.addView(noRead, margin(0, 0, 0, 12));

        LinearLayout display = card();
        display.addView(text("2. Virtual Display", 18, true, TEXT));
        display.addView(text("先建立第二螢幕，再要求 Android 把 LINE 放到第二 Display。主畫面是否被搶、LINE 是否真的留在第二 Display，仍以實機結果為準。", 12, false, MUTED));
        displayState = text("尚未建立 Virtual Display", 13, false, MUTED);
        displayState.setPadding(0, dp(8), 0, dp(8));
        display.addView(displayState);

        Button create = button("建立 Virtual Display");
        create.setOnClickListener(v -> {
            VirtualDisplayEngine.Result r = VirtualDisplayEngine.create(this);
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
            refreshDisplayState();
        });
        display.addView(create);

        Button launch = button("在 Virtual Display 啟動 LINE");
        launch.setOnClickListener(v -> {
            VirtualDisplayEngine.Result r = VirtualDisplayEngine.launchLine(this);
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
            refreshDisplayState();
        });
        display.addView(launch, margin(0, 8, 0, 0));

        Button stop = quietButton("關閉 Virtual Display");
        stop.setOnClickListener(v -> {
            VirtualDisplayEngine.release();
            refreshDisplayState();
        });
        display.addView(stop, margin(0, 8, 0, 0));
        body.addView(display, margin(0, 0, 0, 12));

        LinearLayout privacy = card();
        privacy.addView(text("3. 每個人的訊息保存", 18, true, TEXT));
        privacy.addView(text("通知仍可顯示最新訊息。下面的開關只決定是否把訊息正文永久寫進 Radar 的事件紀錄，預設全部 OFF。", 12, false, MUTED));

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
            labels.addView(text("保存訊息正文到歷史紀錄", 11, false, MUTED));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(slot.saveMessageContentEnabled);
            final int index = i;
            sw.setOnCheckedChangeListener((buttonView, checked) -> prefs.setSaveMessageContentEnabled(index, checked));
            row.addView(sw);
            privacy.addView(row);
        }
        if (found == 0) {
            TextView none = text("目前還沒有監控對象，先回主程式新增人物。", 13, false, MUTED);
            none.setPadding(0, dp(10), 0, dp(4));
            privacy.addView(none);
        }
        body.addView(privacy, margin(0, 0, 0, 12));

        LinearLayout test = card();
        test.addView(text("建議測試順序", 18, true, TEXT));
        test.addView(text("① 確認 No-Read 模組已被框架載入到 LINE\n② 建立 Virtual Display\n③ 把 LINE 啟動到第二 Display\n④ 讓另一個帳號傳一則新訊息\n⑤ Radar 應收到內容，但另一端不應出現已讀\n⑥ 再由另一端讀你送出的訊息，確認 Radar 仍能抓到對方已讀", 12, false, MUTED));
        body.addView(test, margin(0, 0, 0, 16));

        Button explain = quietButton("為什麼還需要實機驗證？");
        explain.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("No-Read 不是普通權限")
            .setMessage("Virtual Display 只負責把 LINE 放到另一個 Display，不會自動阻止已讀。No-Read 則必須在 LINE 程序內攔截已讀流程。新版 LINE 如果改了類別或方法名稱，舊 hook 會失效，所以必須用另一端帳號驗證結果，不能只看 Radar 顯示。")
            .setPositiveButton("知道了", null)
            .show());
        body.addView(explain);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button back = button("回到 LINE Radar");
        back.setOnClickListener(v -> finish());
        root.addView(back, margin(0, 12, 0, 0));
        setContentView(root);
    }

    private void refreshDisplayState() {
        if (displayState == null) return;
        int id = VirtualDisplayEngine.displayId();
        if (id >= 0) {
            displayState.setText("✓ Virtual Display 運作中 · Display " + id);
            displayState.setTextColor(GREEN);
        } else {
            displayState.setText("尚未建立 Virtual Display");
            displayState.setTextColor(MUTED);
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
