package com.example.linereadradar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 28);
    private static final int CARD = Color.rgb(16, 23, 51);
    private static final int CARD_SOFT = Color.rgb(20, 29, 62);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(160, 171, 205);
    private static final int BLUE = Color.rgb(91, 139, 255);
    private static final int PURPLE = Color.rgb(173, 103, 255);
    private static final int GREEN = Color.rgb(95, 222, 171);

    private Prefs prefs;
    private LinearLayout pageHost;
    private TextView tabRadar;
    private TextView tabHistory;
    private TextView tabSettings;
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setupWindow();
        buildShell();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCurrentPage();
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(0);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text("LINE Radar", 26, true, TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView star = text("✦", 26, false, PURPLE);
        star.setGravity(Gravity.CENTER);
        header.addView(star, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(header);

        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(pageHost, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(8), dp(4), 0);
        nav.setBackground(rounded(CARD, 24, Color.TRANSPARENT, 0));
        tabRadar = navItem("◉\n雷達", 0);
        tabHistory = navItem("≡\n紀錄", 1);
        tabSettings = navItem("⚙\n設定", 2);
        nav.addView(tabRadar, new LinearLayout.LayoutParams(0, dp(58), 1f));
        nav.addView(tabHistory, new LinearLayout.LayoutParams(0, dp(58), 1f));
        nav.addView(tabSettings, new LinearLayout.LayoutParams(0, dp(58), 1f));
        root.addView(nav);

        setContentView(root);
        renderCurrentPage();
    }

    private TextView navItem(String label, int tab) {
        TextView tv = text(label, 12, true, MUTED);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> {
            currentTab = tab;
            renderCurrentPage();
        });
        return tv;
    }

    private void renderCurrentPage() {
        if (pageHost == null) return;
        pageHost.removeAllViews();
        tabRadar.setTextColor(currentTab == 0 ? TEXT : MUTED);
        tabHistory.setTextColor(currentTab == 1 ? TEXT : MUTED);
        tabSettings.setTextColor(currentTab == 2 ? TEXT : MUTED);
        if (currentTab == 0) renderRadar();
        else if (currentTab == 1) renderHistory();
        else renderSettings();
    }

    private void renderRadar() {
        LinearLayout hero = card(CARD);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView radar = text("✦\n◌  ◎  ◌\n◌     ◌", 24, false, Color.rgb(181, 196, 255));
        radar.setGravity(Gravity.CENTER);
        hero.addView(radar, new LinearLayout.LayoutParams(-1, dp(118)));

        String mainState;
        int stateColor;
        if (!prefs.globalEnabled()) {
            mainState = "背景監控已關閉";
            stateColor = MUTED;
        } else if (prefs.paused()) {
            mainState = "背景監控已暫停";
            stateColor = PURPLE;
        } else {
            mainState = prefs.globalStatus();
            stateColor = GREEN;
        }
        TextView state = text(mainState, 16, true, stateColor);
        state.setGravity(Gravity.CENTER);
        hero.addView(state);
        TextView count = text(prefs.activeCount() + " 位對象正在監控 · 最多 5 位", 13, false, MUTED);
        count.setGravity(Gravity.CENTER);
        count.setPadding(0, dp(5), 0, dp(12));
        hero.addView(count);
        pageHost.addView(hero, marginLp(0, 6, 0, 12));

        LinearLayout master = card(CARD_SOFT);
        LinearLayout row = row();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("背景監控", 17, true, TEXT));
        labels.addView(text("輪巡已啟用的聊天室", 13, false, MUTED));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch global = systemSwitch(prefs.globalEnabled());
        global.setOnCheckedChangeListener((buttonView, checked) -> setGlobalMonitoring(checked));
        row.addView(global);
        master.addView(row);
        if (prefs.globalEnabled()) {
            Button pause = smallButton(prefs.paused() ? "恢復監控" : "暫停全部");
            pause.setOnClickListener(v -> {
                prefs.setPaused(!prefs.paused());
                if (!prefs.paused()) MonitorForegroundService.start(this);
                renderRadar();
            });
            master.addView(pause);
        }
        pageHost.addView(master, marginLp(0, 0, 0, 14));

        TextView section = text("監控對象", 18, true, TEXT);
        section.setPadding(dp(4), dp(3), 0, dp(6));
        pageHost.addView(section);

        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (!slot.name.isEmpty()) pageHost.addView(slotCard(slot), marginLp(0, 6, 0, 6));
        }

        if (prefs.count() < Prefs.MAX_SLOTS) {
            Button add = button("＋ 新增監控對象");
            add.setOnClickListener(v -> showAddDialog());
            pageHost.addView(add, marginLp(0, 10, 0, 18));
        } else {
            TextView full = text("已使用 5 / 5 個監控名額", 13, false, MUTED);
            full.setGravity(Gravity.CENTER);
            full.setPadding(0, dp(16), 0, dp(22));
            pageHost.addView(full);
        }
    }

    private View slotCard(Prefs.Slot slot) {
        LinearLayout card = card(CARD);
        card.setClickable(true);
        card.setOnClickListener(v -> showSlotDialog(slot.index));

        LinearLayout top = row();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(slot.name, 18, true, TEXT);
        left.addView(name);
        String status = slot.enabled ? slot.status : "已暫停";
        TextView sub = text(status + lastCheckedSuffix(slot.lastCheckedAt), 12, false, slot.enabled ? MUTED : Color.rgb(118, 124, 150));
        sub.setPadding(0, dp(3), 0, 0);
        left.addView(sub);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch enabled = systemSwitch(slot.enabled);
        enabled.setOnClickListener(v -> { });
        enabled.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.setSlotEnabled(slot.index, checked);
            renderRadar();
        });
        top.addView(enabled);
        card.addView(top);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(12), 0, 0);
        chips.addView(chip("✦ 已讀 " + (slot.readEnabled ? "ON" : "OFF"), slot.readEnabled), new LinearLayout.LayoutParams(0, dp(38), 1f));
        View gap = spacer(8);
        chips.addView(gap);
        chips.addView(chip("💬 新訊息 " + (slot.messageEnabled ? "ON" : "OFF"), slot.messageEnabled), new LinearLayout.LayoutParams(0, dp(38), 1f));
        card.addView(chips);
        return card;
    }

    private String lastCheckedSuffix(long at) {
        if (at <= 0) return " · 尚未檢查";
        return " · 最近 " + new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(new Date(at));
    }

    private void renderHistory() {
        TextView title = text("事件紀錄", 23, true, TEXT);
        title.setPadding(dp(4), dp(10), 0, dp(4));
        pageHost.addView(title);
        pageHost.addView(text("已讀、新訊息補抓與基準建立都會記在這裡。", 13, false, MUTED), marginLp(4, 0, 0, 12));

        String history = prefs.history();
        if (history.isEmpty()) {
            LinearLayout empty = card(CARD);
            TextView e = text("目前還沒有紀錄 ✦", 15, false, MUTED);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(36), 0, dp(36));
            empty.addView(e);
            pageHost.addView(empty);
        } else {
            String[] lines = history.split("\\n");
            int shown = Math.min(lines.length, 100);
            for (int i = 0; i < shown; i++) {
                LinearLayout event = card(CARD);
                TextView t = text(lines[i], 13, false, TEXT);
                t.setLineSpacing(0, 1.15f);
                event.addView(t);
                pageHost.addView(event, marginLp(0, 4, 0, 4));
            }
        }
        Button clear = smallButton("清除歷史紀錄");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("清除紀錄？")
            .setMessage("監控名單與設定不會被刪除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除", (d, w) -> { prefs.clearHistory(); renderHistory(); })
            .show());
        pageHost.addView(clear, marginLp(0, 14, 0, 22));
    }

    private void renderSettings() {
        pageHost.addView(text("設定", 23, true, TEXT), marginLp(4, 10, 0, 12));

        LinearLayout interval = card(CARD);
        interval.addView(text("背景巡檢頻率", 17, true, TEXT));
        interval.addView(text("手機解鎖且 Android 允許喚起 LINE 時使用。", 13, false, MUTED));
        LinearLayout options = row();
        options.setPadding(0, dp(12), 0, 0);
        options.addView(intervalButton("30 秒", 30000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(spacer(6));
        options.addView(intervalButton("60 秒", 60000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(spacer(6));
        options.addView(intervalButton("2 分鐘", 120000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        interval.addView(options);
        pageHost.addView(interval, marginLp(0, 0, 0, 12));

        LinearLayout lock = card(CARD);
        lock.addView(text("🔒 安全鎖定規則", 17, true, TEXT));
        lock.addView(text("手機要求 PIN／指紋時，Radar 不會繞過鎖屏。解鎖後會在下一輪自動補抓。", 13, false, MUTED));
        pageHost.addView(lock, marginLp(0, 0, 0, 12));

        Button accessibility = button(isAccessibilityServiceEnabled() ? "✓ 無障礙服務已開啟" : "開啟無障礙服務");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        pageHost.addView(accessibility, marginLp(0, 4, 0, 4));

        Button appSettings = button("App 系統設定");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        pageHost.addView(appSettings, marginLp(0, 4, 0, 4));

        TextView note = text("v0.4 背景輪巡為實驗功能。LINE 介面改版、聊天室不在目前可搜尋範圍，或 Android 阻擋背景啟動時，可能需要你手動開一次 LINE。", 12, false, MUTED);
        note.setPadding(dp(6), dp(16), dp(6), dp(24));
        pageHost.addView(note);
    }

    private Button intervalButton(String label, long value) {
        long current = prefs.pollIntervalMs();
        Button b = smallButton((current == value ? "✓ " : "") + label);
        b.setOnClickListener(v -> { prefs.setPollIntervalMs(value); renderSettings(); });
        return b;
    }

    private void showAddDialog() {
        EditText input = dialogInput("LINE 顯示名稱");
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("新增監控對象")
            .setMessage("輸入聊天室上方顯示的名稱")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("新增", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("請輸入名稱"); return; }
            int result = prefs.addTarget(name);
            if (result < 0) Toast.makeText(this, "最多只能監控 5 位", Toast.LENGTH_SHORT).show();
            else {
                dialog.dismiss();
                renderRadar();
            }
        }));
        dialog.show();
    }

    private void showSlotDialog(int index) {
        Prefs.Slot slot = prefs.slot(index);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), 0, dp(22), 0);
        EditText name = dialogInput("LINE 顯示名稱");
        name.setText(slot.name);
        body.addView(name);
        Switch read = dialogSwitch("已讀提醒", slot.readEnabled);
        Switch messages = dialogSwitch("新訊息補抓", slot.messageEnabled);
        Switch notify = dialogSwitch("系統通知", slot.notifyEnabled);
        Switch vibrate = dialogSwitch("震動提醒", slot.vibrateEnabled);
        body.addView(read);
        body.addView(messages);
        body.addView(notify);
        body.addView(vibrate);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(slot.name)
            .setView(body)
            .setNeutralButton("刪除", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存", null)
            .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newName = name.getText().toString().trim();
                if (newName.isEmpty()) { name.setError("名稱不能空白"); return; }
                if (!newName.equals(slot.name)) prefs.renameTarget(index, newName);
                prefs.setReadEnabled(index, read.isChecked());
                prefs.setMessageEnabled(index, messages.isChecked());
                prefs.setNotifyEnabled(index, notify.isChecked());
                prefs.setVibrateEnabled(index, vibrate.isChecked());
                dialog.dismiss();
                renderRadar();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.rgb(255, 120, 140));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除「" + slot.name + "」？")
                .setMessage("其他監控對象不受影響。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (confirm, which) -> {
                    prefs.deleteTarget(index);
                    dialog.dismiss();
                    renderRadar();
                }).show());
        });
        dialog.show();
    }

    private void setGlobalMonitoring(boolean enabled) {
        if (enabled) {
            if (prefs.activeCount() == 0) {
                Toast.makeText(this, "請先新增至少一位監控對象", Toast.LENGTH_SHORT).show();
                renderRadar();
                return;
            }
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "請先開啟 LINE Radar 無障礙服務", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                renderRadar();
                return;
            }
            prefs.setGlobalEnabled(true);
            prefs.setGlobalStatus("準備第一次背景巡檢");
            prefs.setLastPollAt(0L);
            MonitorForegroundService.start(this);
        } else {
            prefs.setGlobalEnabled(false);
            prefs.setGlobalStatus("待機中");
            MonitorForegroundService.stop(this);
        }
        renderRadar();
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

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(rounded(color, 20, Color.rgb(43, 55, 100), 1));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView chip(String label, boolean on) {
        TextView tv = text(label, 12, true, on ? Color.rgb(214, 220, 255) : MUTED);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(rounded(on ? Color.rgb(35, 46, 91) : Color.rgb(25, 30, 50), 15,
            on ? Color.rgb(90, 103, 186) : Color.rgb(48, 53, 75), 1));
        return tv;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setIncludeFontPadding(true);
        return tv;
    }

    private Switch systemSwitch(boolean checked) {
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setShowText(false);
        return sw;
    }

    private Switch dialogSwitch(String label, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setChecked(checked);
        sw.setTextSize(16);
        sw.setPadding(0, dp(8), 0, dp(8));
        return sw;
    }

    private EditText dialogInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(rounded(Color.rgb(35, 48, 98), 17, Color.rgb(91, 120, 220), 1));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private Button smallButton(String label) {
        Button b = button(label);
        b.setTextSize(13);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0 && strokeColor != Color.TRANSPARENT) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private View spacer(int widthDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return v;
    }

    private LinearLayout.LayoutParams marginLp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
