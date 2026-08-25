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

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 28);
    private static final int CARD = Color.rgb(16, 23, 51);
    private static final int CARD_SOFT = Color.rgb(20, 29, 62);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(160, 171, 205);
    private static final int BLUE = Color.rgb(91, 139, 255);
    private static final int PURPLE = Color.rgb(173, 103, 255);
    private static final int GREEN = Color.rgb(95, 222, 171);
    private static final int AMBER = Color.rgb(255, 202, 108);

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
        syncMonitoringRuntime();
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

    private void renderRadar() {
        LinearLayout heading = row();
        TextView title = text("監控對象", 21, true, TEXT);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView count = text(prefs.count() + " / " + Prefs.MAX_SLOTS, 13, false, MUTED);
        count.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        heading.addView(count, new LinearLayout.LayoutParams(dp(64), dp(44)));
        pageHost.addView(heading, marginLp(4, 4, 4, 4));

        if (prefs.count() == 0) {
            LinearLayout empty = card(CARD);
            TextView icon = text("✦", 34, false, Color.rgb(190, 199, 255));
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon, new LinearLayout.LayoutParams(-1, dp(56)));
            TextView emptyTitle = text("還沒有監控對象", 18, true, TEXT);
            emptyTitle.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle);
            TextView hint = text("新增之後才會出現人物卡片，最多 5 位。", 13, false, MUTED);
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
        } else {
            TextView full = text("已使用全部 5 個監控名額", 13, false, MUTED);
            full.setGravity(Gravity.CENTER);
            full.setPadding(0, dp(16), 0, dp(22));
            pageHost.addView(full);
        }
    }

    private View slotCard(Prefs.Slot slot) {
        LinearLayout card = card(CARD);

        LinearLayout top = row();
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.addView(text(slot.name, 21, true, TEXT));
        TextView status = text(slot.enabled ? slot.status : "已暫停", 12, false,
            !slot.enabled ? MUTED : (slot.armed ? GREEN : AMBER));
        status.setPadding(0, dp(3), 0, 0);
        identity.addView(status);
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout monitorControl = new LinearLayout(this);
        monitorControl.setOrientation(LinearLayout.VERTICAL);
        monitorControl.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView monitorLabel = text("監控", 11, false, MUTED);
        monitorLabel.setGravity(Gravity.CENTER);
        monitorControl.addView(monitorLabel);
        Switch monitor = systemSwitch(slot.enabled);
        monitor.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.setSlotEnabled(slot.index, checked);
            syncMonitoringRuntime();
            renderRadar();
            if (checked && !prefs.slot(slot.index).armed) showBaselineGuide(slot.index);
        });
        monitorControl.addView(monitor);
        top.addView(monitorControl, new LinearLayout.LayoutParams(dp(76), -2));
        card.addView(top);

        if (!slot.armed) {
            LinearLayout baselineBox = new LinearLayout(this);
            baselineBox.setOrientation(LinearLayout.VERTICAL);
            baselineBox.setPadding(dp(12), dp(11), dp(12), dp(11));
            baselineBox.setBackground(rounded(Color.rgb(40, 37, 57), 14, Color.rgb(112, 91, 90), 1));
            baselineBox.addView(text("尚未建立基準", 14, true, AMBER));
            TextView guide = text("先讓 Radar 看一次目前聊天室，之後才知道什麼算新的已讀或新訊息。", 12, false, MUTED);
            guide.setPadding(0, dp(3), 0, dp(7));
            baselineBox.addView(guide);
            Button baseline = smallButton("建立基準");
            baseline.setOnClickListener(v -> showBaselineGuide(slot.index));
            baselineBox.addView(baseline, new LinearLayout.LayoutParams(-1, dp(44)));
            card.addView(baselineBox, marginLp(0, 14, 0, 12));
        } else {
            TextView checked = text(lastCheckedText(slot.lastCheckedAt), 12, false, MUTED);
            checked.setPadding(0, dp(9), 0, dp(3));
            card.addView(checked);

            LinearLayout backgroundRow = row();
            backgroundRow.setPadding(0, dp(10), 0, dp(5));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text("背景檢查", 15, true, TEXT));
            labels.addView(text("手機使用中會自動暫停，不搶畫面", 11, false, MUTED));
            backgroundRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch background = systemSwitch(slot.backgroundEnabled);
            background.setEnabled(slot.enabled);
            background.setOnCheckedChangeListener((buttonView, checked) -> {
                prefs.setBackgroundEnabled(slot.index, checked);
                syncMonitoringRuntime();
                renderRadar();
            });
            backgroundRow.addView(background);
            card.addView(backgroundRow);
        }

        Button detail = quietButton("詳細設定");
        detail.setOnClickListener(v -> showSlotDialog(slot.index));
        card.addView(detail, marginLp(0, 8, 0, 0));
        return card;
    }

    private String lastCheckedText(long at) {
        if (at <= 0) return "最近檢查：尚未檢查";
        return "最近檢查：" + new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(at));
    }

    private void renderHistory() {
        pageHost.addView(text("事件紀錄", 22, true, TEXT), marginLp(4, 8, 0, 2));
        pageHost.addView(text("只保留真正需要看的已讀、新訊息與基準事件。", 13, false, MUTED), marginLp(4, 0, 0, 12));

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
            int shown = Math.min(lines.length, 100);
            for (int i = 0; i < shown; i++) {
                LinearLayout event = card(CARD);
                TextView t = text(lines[i], 13, false, TEXT);
                t.setLineSpacing(0, 1.15f);
                event.addView(t);
                pageHost.addView(event, marginLp(0, 4, 0, 4));
            }
        }

        Button clear = quietButton("清除歷史紀錄");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("清除紀錄？")
            .setMessage("監控對象與設定不會被刪除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除", (d, w) -> { prefs.clearHistory(); renderHistory(); })
            .show());
        pageHost.addView(clear, marginLp(0, 14, 0, 22));
    }

    private void renderSettings() {
        pageHost.addView(text("設定", 22, true, TEXT), marginLp(4, 8, 0, 12));

        if (prefs.count() > 0) {
            LinearLayout pauseCard = card(CARD);
            LinearLayout pauseRow = row();
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text("暫停全部監控", 16, true, TEXT));
            labels.addView(text("保留人物、基準與歷史紀錄", 12, false, MUTED));
            pauseRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch paused = systemSwitch(prefs.paused());
            paused.setOnCheckedChangeListener((buttonView, checked) -> {
                prefs.setPaused(checked);
                syncMonitoringRuntime();
                renderSettings();
            });
            pauseRow.addView(paused);
            pauseCard.addView(pauseRow);
            pageHost.addView(pauseCard, marginLp(0, 0, 0, 12));
        }

        LinearLayout interval = card(CARD);
        interval.addView(text("背景檢查頻率", 16, true, TEXT));
        interval.addView(text("只有人物卡的「背景檢查」為 ON 時才會使用。", 12, false, MUTED));
        LinearLayout options = row();
        options.setPadding(0, dp(12), 0, 0);
        options.addView(intervalButton("30 秒", 30000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(spacer(6));
        options.addView(intervalButton("60 秒", 60000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        options.addView(spacer(6));
        options.addView(intervalButton("2 分鐘", 120000L), new LinearLayout.LayoutParams(0, dp(44), 1f));
        interval.addView(options);
        pageHost.addView(interval, marginLp(0, 0, 0, 12));

        LinearLayout rule = card(CARD);
        rule.addView(text("背景檢查規則", 16, true, TEXT));
        rule.addView(text("螢幕正在使用時會自動暫停背景檢查。手機要求 PIN／指紋時不會繞過安全鎖。", 12, false, MUTED));
        pageHost.addView(rule, marginLp(0, 0, 0, 12));

        Button accessibility = button(isAccessibilityServiceEnabled() ? "✓ 無障礙服務已開啟" : "開啟無障礙服務");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        pageHost.addView(accessibility, marginLp(0, 4, 0, 4));

        Button appSettings = quietButton("App 系統設定");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        pageHost.addView(appSettings, marginLp(0, 4, 0, 4));

        TextView note = text("v0.4.2：以人物為主。沒有新增對象就不顯示人物卡，背景檢查也改成每個人獨立控制。", 12, false, MUTED);
        note.setPadding(dp(6), dp(16), dp(6), dp(24));
        pageHost.addView(note);
    }

    private Button intervalButton(String label, long value) {
        long current = prefs.pollIntervalMs();
        Button b = quietButton((current == value ? "✓ " : "") + label);
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
            if (name.isEmpty()) {
                input.setError("請輸入名稱");
                return;
            }
            int result = prefs.addTarget(name);
            if (result < 0) {
                Toast.makeText(this, "最多只能監控 5 位", Toast.LENGTH_SHORT).show();
                return;
            }
            syncMonitoringRuntime();
            dialog.dismiss();
            renderRadar();
            showBaselineGuide(result);
        }));
        dialog.show();
    }

    private void showBaselineGuide(int index) {
        Prefs.Slot slot = prefs.slot(index);
        if (slot.name.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setTitle("建立「" + slot.name + "」基準")
            .setMessage(
                "接下來會開啟 LINE。\n\n" +
                "1. 進入「" + slot.name + "」聊天室\n" +
                "2. 停留約 2 秒，不要滑動畫面\n" +
                "3. 出現「基準已建立」提示後即可完成\n\n" +
                "Radar 只會把目前畫面記成起點，不會傳送任何訊息。"
            )
            .setNegativeButton("稍後", null)
            .setPositiveButton("前往 LINE", (d, w) -> startBaselineFlow(index))
            .show();
    }

    private void startBaselineFlow(int index) {
        if (!isAccessibilityServiceEnabled()) {
            new AlertDialog.Builder(this)
                .setTitle("先開啟無障礙服務")
                .setMessage("Radar 需要讀取你自己的 LINE 聊天室畫面才能建立基準。開啟後回到這裡，再按一次「建立基準」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("開啟設定", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
            return;
        }

        Prefs.Slot slot = prefs.slot(index);
        if (slot.name.isEmpty()) return;
        if (!slot.enabled) prefs.setSlotEnabled(index, true);
        if (!prefs.globalEnabled()) prefs.setGlobalEnabled(true);
        prefs.markChecked(index, System.currentTimeMillis(), "正在建立基準 · 請進入聊天室");

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

        if (slot.armed) {
            Button rebuild = quietButton("重新建立基準");
            body.addView(rebuild, new LinearLayout.LayoutParams(-1, dp(44)));
            rebuild.setOnClickListener(v -> showBaselineGuide(index));
        }

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
                syncMonitoringRuntime();
                dialog.dismiss();
                renderRadar();
                if (renamed) showBaselineGuide(index);
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.rgb(255, 120, 140));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除「" + slot.name + "」？")
                .setMessage("其他監控對象不受影響。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (confirm, which) -> {
                    prefs.deleteTarget(index);
                    syncMonitoringRuntime();
                    dialog.dismiss();
                    renderRadar();
                }).show());
        });
        dialog.show();
    }

    private void syncMonitoringRuntime() {
        boolean shouldEnable = prefs.activeCount() > 0;
        if (prefs.globalEnabled() != shouldEnable) prefs.setGlobalEnabled(shouldEnable);

        boolean needsBackgroundService = shouldEnable
            && !prefs.paused()
            && prefs.backgroundActiveCount() > 0
            && isAccessibilityServiceEnabled();

        if (needsBackgroundService) MonitorForegroundService.start(this);
        else MonitorForegroundService.stop(this);
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

    private Button quietButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.rgb(217, 222, 248));
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackground(rounded(Color.rgb(25, 33, 66), 15, Color.rgb(63, 76, 128), 1));
        b.setPadding(dp(10), 0, dp(10), 0);
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
