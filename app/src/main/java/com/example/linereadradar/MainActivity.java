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

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 28);
    private static final int CARD = Color.rgb(16, 23, 51);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(160, 171, 205);
    private static final int PURPLE = Color.rgb(173, 103, 255);
    private static final int GREEN = Color.rgb(95, 222, 171);
    private static final int AMBER = Color.rgb(255, 202, 108);
    private static final int RED = Color.rgb(255, 130, 150);

    private static final String SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases";
    private static final String SHIZUKU_PLAY = "market://details?id=moe.shizuku.privileged.api";
    private static final String DISCLOSURE_PREF = "accessibility_disclosure_v1";

    private Prefs prefs;
    private HealthDiagnostics health;
    private LinearLayout pageHost;
    private TextView tabRadar;
    private TextView tabHistory;
    private TextView tabSettings;
    private int currentTab = 0;
    private int pendingBackgroundSlot = -1;
    private int pendingInitialSlot = -1;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (requestCode != ShizukuBridge.REQUEST_CODE) return;
        runOnUiThread(() -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED && pendingBackgroundSlot >= 0) {
                int index = pendingBackgroundSlot;
                activateBackgroundMode(index);
            } else {
                Toast.makeText(this, "沒有取得 Shizuku 授權，背景模式尚未開啟", Toast.LENGTH_LONG).show();
                pendingBackgroundSlot = -1;
                renderCurrentPage();
            }
        });
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        health = new HealthDiagnostics(this);
        setupWindow();
        buildShell();
        requestNotificationPermissionIfNeeded();
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener); }
        catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncMonitoringRuntime();
        renderCurrentPage();

        if (pendingInitialSlot >= 0 && isAccessibilityServiceEnabled()) {
            int index = pendingInitialSlot;
            pendingInitialSlot = -1;
            startInitialMonitorFlow(index);
            return;
        }

        if (pendingBackgroundSlot >= 0) {
            continueBackgroundSetup(pendingBackgroundSlot, false);
        }
    }

    @Override
    protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); }
        catch (Throwable ignored) {}
        super.onDestroy();
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

        LinearLayout header = row();
        TextView title = text("LINE Radar", 26, true, TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView star = text("✦", 27, false, PURPLE);
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

    private void resetPageForDirectRender() {
        if (pageHost != null) pageHost.removeAllViews();
    }

    private void renderRadar() {
        resetPageForDirectRender();
        LinearLayout heading = row();
        heading.addView(text("監控對象", 21, true, TEXT), new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView count = text(prefs.count() + " / " + Prefs.MAX_SLOTS, 13, false, MUTED);
        count.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        heading.addView(count, new LinearLayout.LayoutParams(dp(64), dp(44)));
        pageHost.addView(heading, marginLp(4, 4, 4, 4));

        if (prefs.count() == 0) {
            LinearLayout empty = card(CARD);
            TextView icon = text("✦", 34, false, Color.rgb(190, 199, 255));
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon, new LinearLayout.LayoutParams(-1, dp(56)));
            TextView title = text("還沒有監控對象", 18, true, TEXT);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView hint = text("新增後會直接帶你去 LINE。第一次建立基準不需要懸浮視窗。", 13, false, MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(5), 0, dp(8));
            empty.addView(hint);
            pageHost.addView(empty, marginLp(0, 4, 0, 12));
        } else {
            for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
                Prefs.Slot slot = prefs.slot(i);
                if (!slot.name.isEmpty()) pageHost.addView(slotCard(slot), marginLp(0, 6, 0, 6));
            }
        }

        if (prefs.count() < Prefs.MAX_SLOTS) {
            Button add = button("＋ 新增監控對象");
            add.setOnClickListener(v -> showAddDialog());
            pageHost.addView(add, marginLp(0, 12, 0, 22));
        }
    }

    private View slotCard(Prefs.Slot slot) {
        LinearLayout card = card(CARD);
        LinearLayout top = row();
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.addView(text(slot.name, 21, true, TEXT));

        String statusText = slot.enabled ? slot.status : "已暫停";
        if (slot.enabled && !slot.armed) statusText = "尚未啟動 · 點開始監控後到 LINE 選聊天室";
        TextView status = text(statusText, 12, false, !slot.enabled ? MUTED : (slot.armed ? GREEN : AMBER));
        status.setPadding(0, dp(3), 0, 0);
        identity.addView(status);
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView label = text("監控", 11, false, MUTED);
        label.setGravity(Gravity.CENTER);
        control.addView(label);
        Switch monitor = systemSwitch(slot.enabled);
        monitor.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.setSlotEnabled(slot.index, checked);
            if (!checked) prefs.setBackgroundEnabled(slot.index, false);
            syncMonitoringRuntime();
            renderRadar();
            if (checked && !prefs.slot(slot.index).armed) startInitialMonitorFlow(slot.index);
        });
        control.addView(monitor);
        top.addView(control, new LinearLayout.LayoutParams(dp(76), -2));
        card.addView(top);

        if (!slot.armed) {
            TextView guide = text("第一次只要做一次：按「開始監控」→ 到 LINE 點進「" + slot.name + "」→ 停留 1～2 秒。Radar 會在背景自動建立基準。", 12, false, MUTED);
            guide.setPadding(0, dp(12), 0, dp(5));
            card.addView(guide);
            Button start = button("開始監控");
            start.setOnClickListener(v -> startInitialMonitorFlow(slot.index));
            card.addView(start, marginLp(0, 8, 0, 0));
        } else {
            TextView checked = text(lastCheckedText(slot.lastCheckedAt), 12, false, MUTED);
            checked.setPadding(0, dp(9), 0, dp(3));
            card.addView(checked);

            LinearLayout bg = row();
            bg.setPadding(0, dp(10), 0, dp(5));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text("背景持續監控", 15, true, TEXT));
            labels.addView(text(backgroundStatusText(slot), 11, false, MUTED));
            bg.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            Switch sw = systemSwitch(slot.backgroundEnabled);
            sw.setEnabled(slot.enabled);
            sw.setOnCheckedChangeListener((buttonView, checked2) -> {
                if (!checked2) {
                    prefs.setBackgroundEnabled(slot.index, false);
                    pendingBackgroundSlot = -1;
                    syncMonitoringRuntime();
                    renderRadar();
                    return;
                }
                beginBackgroundSetup(slot.index);
            });
            bg.addView(sw);
            card.addView(bg);
        }

        Button detail = quietButton("詳細設定");
        detail.setOnClickListener(v -> showSlotDialog(slot.index));
        card.addView(detail, marginLp(0, 8, 0, 0));
        return card;
    }

    private String backgroundStatusText(Prefs.Slot slot) {
        if (!slot.backgroundEnabled) return "開啟後會自動檢查 Shizuku、授權、第二螢幕與 LINE";
        if (!ShizukuBridge.isShizukuInstalled(this)) return "等待安裝 Shizuku";
        if (!ShizukuBridge.binderReady()) return "等待啟動 Shizuku";
        if (!ShizukuBridge.permissionGranted()) return "等待 Shizuku 授權";
        int id = prefs.virtualDisplayId();
        if (!prefs.virtualDisplayRunning() || id < 0) return "正在建立第二螢幕";

        long now = System.currentTimeMillis();
        boolean lineOk = prefs.secondaryLineDisplayId() == id
            && now - prefs.secondaryLineSeenAt() <= 15000L;
        boolean targetOk = health.isTargetRecent(slot.index, id, now, prefs);

        if (lineOk && targetOk) {
            return "✓ Display " + id + " · 目標聊天室已定位 · 背景監控中";
        }
        if (lineOk) {
            return "Display " + id + " · LINE 已驗證 · 正在定位「" + slot.name + "」";
        }
        return "Display " + id + " 已建立 · 等待 LINE／自動尋路";
    }

    private String lastCheckedText(long at) {
        if (at <= 0) return "最近檢查：尚未檢查";
        return "最近檢查：" + new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(at));
    }

    private void renderHistory() {
        resetPageForDirectRender();
        pageHost.addView(text("事件紀錄", 22, true, TEXT), marginLp(4, 8, 0, 2));
        pageHost.addView(text("訊息正文只有在該人物的「保存訊息內容」開啟時才會永久寫入。", 13, false, MUTED), marginLp(4, 0, 0, 12));

        String history = prefs.history();
        if (history.isEmpty()) {
            LinearLayout empty = card(CARD);
            TextView e = text("目前還沒有紀錄 ✦", 15, false, MUTED);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(34), 0, dp(34));
            empty.addView(e);
            pageHost.addView(empty);
        } else {
            String[] lines = history.split("\\n");
            for (int i = 0; i < Math.min(lines.length, 100); i++) {
                LinearLayout event = card(CARD);
                TextView t = text(lines[i], 13, false, TEXT);
                event.addView(t);
                pageHost.addView(event, marginLp(0, 4, 0, 4));
            }
        }

        Button clear = quietButton("清除歷史紀錄");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("清除紀錄？")
            .setMessage("監控對象與設定不會被刪除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除", (d, w) -> {
                prefs.clearHistory();
                renderHistory();
            }).show());
        pageHost.addView(clear, marginLp(0, 14, 0, 22));
    }

    private void renderSettings() {
        resetPageForDirectRender();
        pageHost.addView(text("設定", 22, true, TEXT), marginLp(4, 8, 0, 12));

        LinearLayout environment = card(CARD);
        environment.addView(text("背景環境檢查", 16, true, TEXT));
        environment.addView(statusLine(isAccessibilityServiceEnabled(), "無障礙服務", isAccessibilityServiceEnabled() ? "已開啟" : "未開啟"));
        environment.addView(statusLine(ShizukuBridge.isShizukuInstalled(this), "Shizuku", ShizukuBridge.stateText(this)));
        boolean displayOk = prefs.virtualDisplayRunning() && prefs.virtualDisplayId() >= 0;
        environment.addView(statusLine(displayOk, "第二螢幕", prefs.virtualDisplayStatus()));
        boolean lineOk = displayOk && prefs.secondaryLineDisplayId() == prefs.virtualDisplayId()
            && System.currentTimeMillis() - prefs.secondaryLineSeenAt() <= 15000L;
        environment.addView(statusLine(lineOk, "LINE 第二螢幕驗證", lineOk ? "已驗證" : "尚未驗證"));
        boolean targetOk = displayOk && health.hasRecentBackgroundTarget(prefs, prefs.virtualDisplayId(), System.currentTimeMillis());
        environment.addView(statusLine(targetOk, "監控聊天室定位", targetOk ? "已定位 · 背景監控就緒" : "尚未定位到啟用中的監控聊天室"));
        Button lab = quietButton("開啟進階診斷");
        lab.setOnClickListener(v -> startActivity(new Intent(this, ExperimentalLabActivity.class)));
        environment.addView(lab, marginLp(0, 10, 0, 0));
        pageHost.addView(environment, marginLp(0, 0, 0, 12));

        if (prefs.count() > 0) {
            LinearLayout pauseCard = card(CARD);
            LinearLayout pauseRow = row();
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text("暫停全部監控", 16, true, TEXT));
            labels.addView(text("保留人物、基準與歷史紀錄", 12, false, MUTED));
            pauseRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch paused = systemSwitch(prefs.paused());
            paused.setOnCheckedChangeListener((b, c) -> {
                prefs.setPaused(c);
                syncMonitoringRuntime();
                renderSettings();
            });
            pauseRow.addView(paused);
            pauseCard.addView(pauseRow);
            pageHost.addView(pauseCard, marginLp(0, 0, 0, 12));
        }

        LinearLayout interval = card(CARD);
        interval.addView(text("第二畫面重新對準頻率", 16, true, TEXT));
        interval.addView(text("單一人物會持續監看；多人物時會依這個頻率在第二螢幕內重新對準聊天室。", 12, false, MUTED));
        LinearLayout options = row();
        options.setPadding(0, dp(12), 0, 0);
        options.addView(intervalButton("30 秒", 30000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(spacer(6));
        options.addView(intervalButton("60 秒", 60000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(spacer(6));
        options.addView(intervalButton("2 分鐘", 120000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        interval.addView(options);
        pageHost.addView(interval, marginLp(0, 0, 0, 12));

        Button accessibility = button(isAccessibilityServiceEnabled() ? "✓ 無障礙服務已開啟" : "設定無障礙服務");
        accessibility.setOnClickListener(v -> showAccessibilityDisclosure(-1, false));
        pageHost.addView(accessibility, marginLp(0, 4, 0, 4));

        Button shizuku = button("設定 Shizuku（免 Root）");
        shizuku.setOnClickListener(v -> {
            pendingBackgroundSlot = firstArmedSlot();
            if (pendingBackgroundSlot < 0) pendingBackgroundSlot = 0;
            continueBackgroundSetup(pendingBackgroundSlot, true);
        });
        pageHost.addView(shizuku, marginLp(0, 4, 0, 4));

        Button appSettings = quietButton("App 系統設定");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        pageHost.addView(appSettings, marginLp(0, 4, 0, 4));
    }

    private View statusLine(boolean ok, String label, String detail) {
        LinearLayout line = row();
        line.setPadding(0, dp(8), 0, 0);
        TextView dot = text(ok ? "✓" : "•", 15, true, ok ? GREEN : AMBER);
        line.addView(dot, new LinearLayout.LayoutParams(dp(28), -2));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 13, true, TEXT));
        labels.addView(text(detail, 11, false, MUTED));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        return line;
    }

    private Button intervalButton(String label, long value) {
        Button b = quietButton((prefs.pollIntervalMs() == value ? "✓ " : "") + label);
        b.setOnClickListener(v -> {
            prefs.setPollIntervalMs(value);
            renderSettings();
        });
        return b;
    }

    private void showAddDialog() {
        EditText input = dialogInput("LINE 顯示名稱");
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("新增監控對象")
            .setMessage("輸入聊天室上方顯示的名稱。新增後會直接開 LINE，你只要點進該聊天室，Radar 會在背景自動建立基準。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("新增並開始", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("請輸入名稱");
                return;
            }
            int result = prefs.addTarget(name);
            if (result < 0) {
                Toast.makeText(this, "最多只能監控 5 位", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            startInitialMonitorFlow(result);
        }));
        dialog.show();
    }

    private void startInitialMonitorFlow(int index) {
        Prefs.Slot slot = prefs.slot(index);
        if (slot.name.isEmpty()) return;
        if (!isAccessibilityServiceEnabled()) {
            pendingInitialSlot = index;
            showAccessibilityDisclosure(index, true);
            return;
        }

        if (!slot.enabled) prefs.setSlotEnabled(index, true);
        if (!prefs.globalEnabled()) prefs.setGlobalEnabled(true);
        prefs.markChecked(index, System.currentTimeMillis(), "首次設定中 · 請在 LINE 進入「" + slot.name + "」聊天室");
        prefs.setGlobalStatus("首次設定「" + slot.name + "」");
        MonitorForegroundService.start(this);

        Intent command = new Intent(LineReadAccessibilityService.ACTION_BASELINE);
        command.setPackage(getPackageName());
        command.putExtra(MonitorForegroundService.EXTRA_SLOT, index);
        sendBroadcast(command);

        Intent launch = getPackageManager().getLaunchIntentForPackage("jp.naver.line.android");
        if (launch == null) {
            Toast.makeText(this, "找不到 LINE App", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(launch);
        Toast.makeText(this, "請進入「" + slot.name + "」聊天室，停留 1～2 秒即可", Toast.LENGTH_LONG).show();
    }

    private void beginBackgroundSetup(int index) {
        pendingBackgroundSlot = index;
        Prefs.Slot slot = prefs.slot(index);
        if (!slot.armed) {
            Toast.makeText(this, "請先完成「" + slot.name + "」第一次基準", Toast.LENGTH_LONG).show();
            pendingBackgroundSlot = -1;
            renderRadar();
            startInitialMonitorFlow(index);
            return;
        }
        continueBackgroundSetup(index, false);
    }

    private void continueBackgroundSetup(int index, boolean settingsOnly) {
        if (!isAccessibilityServiceEnabled()) {
            if (settingsOnly) {
                showAccessibilityDisclosure(-1, false);
            } else {
                showAccessibilityDisclosure(index, false);
            }
            return;
        }

        if (!ShizukuBridge.isShizukuInstalled(this)) {
            showShizukuInstallDialog(index);
            return;
        }

        if (!ShizukuBridge.binderReady()) {
            showShizukuStartDialog(index);
            return;
        }

        if (!ShizukuBridge.permissionGranted()) {
            showShizukuPermissionDialog(index);
            return;
        }

        if (!settingsOnly && index >= 0 && index < Prefs.MAX_SLOTS && !prefs.slot(index).name.isEmpty()) {
            activateBackgroundMode(index);
        } else {
            pendingBackgroundSlot = -1;
            Toast.makeText(this, "Shizuku 已準備完成 ✓", Toast.LENGTH_SHORT).show();
            renderCurrentPage();
        }
    }

    private void showShizukuInstallDialog(int index) {
        pendingBackgroundSlot = index;
        new AlertDialog.Builder(this)
            .setTitle("需要安裝 Shizuku（免 Root）")
            .setMessage(
                "背景持續監控需要 Shizuku 提供 Android 的 ADB shell 權限橋接。\n\n" +
                "不用 Root。安裝完成後，請回到這裡，Radar 會繼續檢查下一步。"
            )
            .setNegativeButton("取消", (d, w) -> {
                pendingBackgroundSlot = -1;
                renderCurrentPage();
            })
            .setNeutralButton("Google Play", (d, w) -> openUri(SHIZUKU_PLAY, "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
            .setPositiveButton("GitHub 下載", (d, w) -> openUri(SHIZUKU_RELEASES, SHIZUKU_RELEASES))
            .show();
    }

    private void showShizukuStartDialog(int index) {
        pendingBackgroundSlot = index;
        new AlertDialog.Builder(this)
            .setTitle("啟動 Shizuku")
            .setMessage(
                "Shizuku 已安裝，但服務尚未啟動。\n\n" +
                "免 Root 操作：\n" +
                "1. 手機設定 → 開發人員選項 → 開啟「無線偵錯」\n" +
                "2. 開啟 Shizuku\n" +
                "3. 依 Shizuku 畫面完成配對並按「啟動」\n" +
                "4. 回到 LINE Radar，會自動繼續"
            )
            .setNegativeButton("取消", (d, w) -> {
                pendingBackgroundSlot = -1;
                renderCurrentPage();
            })
            .setNeutralButton("重新檢查", (d, w) -> continueBackgroundSetup(index, false))
            .setPositiveButton("開啟 Shizuku", (d, w) -> openShizukuApp())
            .show();
    }

    private void showShizukuPermissionDialog(int index) {
        pendingBackgroundSlot = index;
        if (ShizukuBridge.permissionDeniedPermanently()) {
            new AlertDialog.Builder(this)
                .setTitle("Shizuku 權限已被拒絕")
                .setMessage("請開啟 Shizuku，在「已授權的應用程式」中重新允許 LINE Radar，然後回來重新開啟背景監控。")
                .setNegativeButton("取消", (d, w) -> {
                    pendingBackgroundSlot = -1;
                    renderCurrentPage();
                })
                .setPositiveButton("開啟 Shizuku", (d, w) -> openShizukuApp())
                .show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("允許 LINE Radar 使用 Shizuku")
            .setMessage(
                "Radar 只會使用 Shizuku 的 shell 權限來建立／維持第二螢幕，並把你自己的 LINE 啟動到該 Display。\n\n" +
                "不會取得 Root，也不會讀取其他 App 的私有資料夾。"
            )
            .setNegativeButton("取消", (d, w) -> {
                pendingBackgroundSlot = -1;
                renderCurrentPage();
            })
            .setPositiveButton("繼續授權", (d, w) -> ShizukuBridge.requestPermission())
            .show();
    }

    private void activateBackgroundMode(int index) {
        if (index < 0 || index >= Prefs.MAX_SLOTS) return;
        Prefs.Slot slot = prefs.slot(index);
        if (slot.name.isEmpty()) return;

        prefs.setBackgroundEnabled(index, true);
        prefs.setGlobalEnabled(true);
        prefs.setGlobalStatus("Shizuku 背景模式啟動中");
        pendingBackgroundSlot = -1;
        ProjectionForegroundService.start(this);
        renderCurrentPage();

        new AlertDialog.Builder(this)
            .setTitle("正在啟動背景監控")
            .setMessage(
                "Radar 會自動：\n" +
                "1. 建立第二螢幕\n" +
                "2. 用 Shizuku 把 LINE 放進第二 Display\n" +
                "3. 驗證 LINE root\n" +
                "4. 自動復位到聊天列表並定位「" + slot.name + "」\n\n" +
                "只有人物卡顯示「目標聊天室已定位」才算背景監控真正就緒。看到 LINE 已驗證但還在『正在定位』時，Radar 仍會自動尋路。"
            )
            .setPositiveButton("知道了", null)
            .show();
    }

    private void showAccessibilityDisclosure(int index, boolean initialFlow) {
        if (getSharedPreferences("radar_prefs", MODE_PRIVATE).getBoolean(DISCLOSURE_PREF, false)) {
            pendingInitialSlot = initialFlow ? index : pendingInitialSlot;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("為什麼需要無障礙服務？")
            .setMessage(
                "LINE Radar 會透過 Android 無障礙服務讀取你主動開啟，或交由第二 Display 顯示的聊天畫面。可能讀到聊天室名稱、可見的「已讀」狀態與可見訊息內容。\n\n" +
                "用途：只用來在你的手機本機判斷已讀／新訊息並發出提醒。只有你另外開啟「保存訊息內容」時，訊息正文才會寫入本機歷史。Radar 不會把聊天內容上傳到伺服器。\n\n" +
                "你可以隨時在 Android 設定中關閉這個服務。"
            )
            .setNegativeButton("不同意", (d, w) -> {
                pendingInitialSlot = -1;
                if (!initialFlow) pendingBackgroundSlot = -1;
                renderCurrentPage();
            })
            .setPositiveButton("我了解並同意", (d, w) -> {
                getSharedPreferences("radar_prefs", MODE_PRIVATE).edit().putBoolean(DISCLOSURE_PREF, true).apply();
                if (initialFlow) pendingInitialSlot = index;
                else if (index >= 0) pendingBackgroundSlot = index;
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            })
            .show();
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
        Switch save = dialogSwitch("保存訊息內容到歷史紀錄", slot.saveMessageContentEnabled);
        body.addView(read);
        body.addView(messages);
        body.addView(notify);
        body.addView(vibrate);
        body.addView(save);

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
                if (newName.isEmpty()) {
                    name.setError("名稱不能空白");
                    return;
                }
                boolean renamed = !newName.equals(slot.name);
                if (renamed) prefs.renameTarget(index, newName);
                prefs.setReadEnabled(index, read.isChecked());
                prefs.setMessageEnabled(index, messages.isChecked());
                prefs.setNotifyEnabled(index, notify.isChecked());
                prefs.setVibrateEnabled(index, vibrate.isChecked());
                prefs.setSaveMessageContentEnabled(index, save.isChecked());
                syncMonitoringRuntime();
                dialog.dismiss();
                renderRadar();
                if (renamed) startInitialMonitorFlow(index);
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.rgb(255, 120, 140));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除「" + slot.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (c, w) -> {
                    prefs.deleteTarget(index);
                    syncMonitoringRuntime();
                    dialog.dismiss();
                    renderRadar();
                }).show());
        });
        dialog.show();
    }

    private boolean hasUnarmedActiveSlot() {
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot s = prefs.slot(i);
            if (s.active() && !s.armed) return true;
        }
        return false;
    }

    private int firstArmedSlot() {
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot s = prefs.slot(i);
            if (s.active() && s.armed) return i;
        }
        return -1;
    }

    private void syncMonitoringRuntime() {
        boolean shouldEnable = prefs.activeCount() > 0;
        if (prefs.globalEnabled() != shouldEnable) prefs.setGlobalEnabled(shouldEnable);

        if (!shouldEnable || prefs.paused()) {
            MonitorForegroundService.stop(this);
            ProjectionForegroundService.stop(this);
            return;
        }

        if (prefs.backgroundActiveCount() > 0) {
            if (isAccessibilityServiceEnabled() && ShizukuBridge.permissionGranted()) {
                ProjectionForegroundService.start(this);
            }
            MonitorForegroundService.stop(this);
            return;
        }

        ProjectionForegroundService.stop(this);
        if (hasUnarmedActiveSlot() && isAccessibilityServiceEnabled()) {
            MonitorForegroundService.start(this);
        } else {
            MonitorForegroundService.stop(this);
        }
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

    private void openShizukuApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        } else {
            openUri(SHIZUKU_RELEASES, SHIZUKU_RELEASES);
        }
    }

    private void openUri(String primary, String fallback) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(primary)));
        } catch (Throwable ignored) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback))); }
            catch (Throwable ignoredToo) {
                Toast.makeText(this, "無法開啟連結", Toast.LENGTH_LONG).show();
            }
        }
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

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
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
