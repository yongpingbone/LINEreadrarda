package com.example.linereadradar;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Prefs prefs;
    private EditText targetInput;
    private TextView statusView;
    private TextView historyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        content.addView(text("LINE 已讀雷達", 26, true));
        content.addView(spacer(8));
        content.addView(text("只監控你自己的手機畫面。LINE 在前景、分割畫面或浮動視窗且聊天室 UI 可讀時，才可能即時偵測。", 15, false));
        content.addView(spacer(18));

        content.addView(text("指定對象的 LINE 顯示名稱", 15, true));
        targetInput = new EditText(this);
        targetInput.setSingleLine(true);
        targetInput.setHint("例如：小明");
        targetInput.setText(prefs.target());
        content.addView(targetInput, new LinearLayout.LayoutParams(-1, -2));

        Button start = button("儲存並開始監控");
        start.setOnClickListener(v -> startMonitoring());
        content.addView(start);

        Button stop = button("停止監控");
        stop.setOnClickListener(v -> {
            prefs.stop();
            Toast.makeText(this, "已停止監控", Toast.LENGTH_SHORT).show();
            refresh();
        });
        content.addView(stop);

        Button accessibility = button("開啟無障礙設定");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        content.addView(accessibility);

        Button appSettings = button("開啟 App 系統設定");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        content.addView(appSettings);

        content.addView(spacer(18));
        statusView = text("", 15, true);
        content.addView(statusView);
        content.addView(spacer(18));
        content.addView(text("使用方式", 18, true));
        content.addView(text("1. 開啟無障礙服務。\n2. 填入指定對象名稱。\n3. 點開始監控。\n4. 進入該聊天室並停在最底部。\n5. 新的已讀出現時會通知一次並停止。", 14, false));
        content.addView(spacer(16));
        content.addView(text("歷史紀錄（最多 50 筆）", 18, true));
        historyView = text("", 13, false);
        historyView.setTextIsSelectable(true);
        content.addView(historyView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void startMonitoring() {
        String target = targetInput.getText().toString().trim();
        if (target.isEmpty()) {
            targetInput.setError("請輸入 LINE 顯示名稱");
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "請先開啟『LINE 已讀雷達』無障礙服務", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        prefs.start(target);
        refresh();
        Intent launch = getPackageManager().getLaunchIntentForPackage("jp.naver.line.android");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "找不到 LINE App", Toast.LENGTH_LONG).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        String expected = new ComponentName(this, LineReadAccessibilityService.class).flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) if (expected.equalsIgnoreCase(splitter.next())) return true;
        return false;
    }

    private void refresh() {
        boolean access = isAccessibilityServiceEnabled();
        String state;
        if (!access) state = "⚠ 無障礙服務：未開啟";
        else if (prefs.enabled() && prefs.armed()) state = "● 監控中：" + prefs.target() + "（基準已建立）";
        else if (prefs.enabled()) state = "● 等待進入指定聊天室：" + prefs.target();
        else state = "○ 目前未監控";
        statusView.setText(state);
        historyView.setText(prefs.history().isEmpty() ? "尚無紀錄" : prefs.history());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(int value) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(value)));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
