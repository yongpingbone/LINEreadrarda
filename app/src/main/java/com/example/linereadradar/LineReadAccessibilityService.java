package com.example.linereadradar;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class LineReadAccessibilityService extends AccessibilityService {
    static final String ACTION_BASELINE = "com.example.linereadradar.BASELINE";

    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String CHANNEL_ID = "line_read_radar_events_v068";
    private static final long DEBOUNCE_MS = 250L;
    private static final long WATCHDOG_MS = 700L;
    private static final long REQUEST_TIMEOUT_MS = 20000L;
    private static final long MANUAL_SESSION_GAP_MS = 1600L;
    private static final long MANUAL_STABLE_MS = 1000L;
    private static final long SECONDARY_DIAGNOSTIC_MS = 1500L;
    private static final long NAVIGATION_COOLDOWN_MS = 650L;
    private static final long TARGET_OPEN_WAIT_MS = 1500L;
    private static final int MAX_BACK_STEPS = 3;
    private static final int MAX_SCROLL_TO_TOP_STEPS = 4;
    private static final int MAX_SCROLL_FORWARD_STEPS = 10;

    private static final Pattern TIME_LIKE = Pattern.compile(
        "^(上午|下午|AM|PM|am|pm|오전|오후)?\\s*\\d{1,2}:\\d{2}$|^\\d{1,2}/\\d{1,2}$|^昨天$|^今天$|^Yesterday$|^Today$|^昨日$|^今日$|^어제$|^오늘$"
    );

    private static final Set<String> READ_LABELS = setOf(
        "已讀", "已读", "Read", "READ", "read", "既読", "읽음"
    );

    private static final Set<String> CHAT_TAB_LABELS = setOf(
        "聊天", "Chats", "Talk", "トーク", "채팅"
    );

    private static final Set<String> BACK_LABELS = setOf(
        "返回", "Back", "BACK", "back", "上一頁", "上一页", "戻る", "뒤로"
    );

    private static final Set<String> UI_LABELS = setOf(
        "聊天", "主頁", "首页", "VOOM", "錢包", "钱包",
        "Chats", "Home", "Wallet", "Talk", "ホーム", "トーク", "ウォレット",
        "채팅", "홈", "지갑",
        "顯示加號選單", "加號選單", "從照片、影片按鈕", "從照片、影片",
        "Open plus menu", "Photos and videos button", "Photos and videos",
        "プラスメニューを表示", "写真・動画ボタン", "사진 및 동영상 버튼"
    );

    private Prefs prefs;
    private HealthDiagnostics health;
    private long lastScanAt = 0L;
    private long lastSecondaryDiagnosticAt = 0L;
    private int requestedSlot = -1;
    private long requestStartedAt = 0L;
    private boolean clickedTargetDuringRequest = false;
    private boolean baselineRequest = false;

    // Bounded navigation state used by hard refresh. The request first looks for
    // the target in the current tree, then resets LINE to the Chats tab, then
    // scans a bounded number of chat-list pages. It never loops forever.
    private boolean requestChatsTabOpened = false;
    private int requestBackSteps = 0;
    private int requestScrollToTopSteps = 0;
    private int requestScrollForwardSteps = 0;
    private long requestLastNavigationAt = 0L;
    private long requestTargetClickAt = 0L;

    private int manualVisibleSlot = -1;
    private long manualVisibleSince = 0L;
    private long manualLastSeenAt = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (prefs != null && prefs.globalEnabled() && !prefs.paused() && prefs.activeCount() > 0) {
                    long now = System.currentTimeMillis();
                    if (requestedSlot >= 0) evaluateRequestedSlot(now);
                    else evaluateVisibleMonitoredChat(now);
                }
            } catch (Throwable ignored) {}
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable timeoutRunnable = () -> {
        if (requestedSlot < 0) return;
        Prefs.Slot slot = prefs.slot(requestedSlot);
        long now = System.currentTimeMillis();
        if (baselineRequest) {
            prefs.markChecked(requestedSlot, now, "尚未建立基準 · 請再試一次");
            prefs.setGlobalStatus("尚未找到「" + slot.name + "」聊天室");
            Toast.makeText(this, "沒有找到「" + slot.name + "」聊天室，請再試一次", Toast.LENGTH_LONG).show();
        } else {
            prefs.markChecked(requestedSlot, now, "第二畫面尋路逾時 · 尚未找到聊天室");
            prefs.setGlobalStatus("LINE 已開啟，但尚未定位到「" + slot.name + "」");
        }
        clearRequest(false);
    };

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (!MonitorForegroundService.ACTION_POLL.equals(action) && !ACTION_BASELINE.equals(action)) return;

            int slot = intent.getIntExtra(MonitorForegroundService.EXTRA_SLOT, -1);
            if (slot < 0 || slot >= Prefs.MAX_SLOTS) return;
            requestedSlot = slot;
            requestStartedAt = System.currentTimeMillis();
            clickedTargetDuringRequest = false;
            baselineRequest = ACTION_BASELINE.equals(action);
            resetRequestNavigationState();
            handler.removeCallbacks(timeoutRunnable);
            handler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        health = new HealthDiagnostics(this);
        ensureNotificationChannel();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MonitorForegroundService.ACTION_POLL);
        filter.addAction(ACTION_BASELINE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(requestReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(requestReceiver, filter);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(watchdogRunnable);
        handler.post(watchdogRunnable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!LINE_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!prefs.globalEnabled() || prefs.paused()) return;

        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - lastScanAt < DEBOUNCE_MS) return;
        lastScanAt = nowElapsed;

        long now = System.currentTimeMillis();
        if (requestedSlot >= 0) {
            evaluateRequestedSlot(now);
            return;
        }
        evaluateVisibleMonitoredChat(now);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(watchdogRunnable);
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void evaluateRequestedSlot(long now) {
        if (requestedSlot < 0) return;
        Prefs.Slot slot = prefs.slot(requestedSlot);
        if (!slot.active()) {
            clearRequest(false);
            return;
        }
        if (!baselineRequest && !slot.backgroundActive()) {
            clearRequest(false);
            return;
        }

        boolean secondaryMode = !baselineRequest && isSecondaryBackgroundMode(slot);
        ScanResult scan = scanAvailableLineWindows(slot.name, secondaryMode);
        if (scan.targetVisible) {
            if (!slot.armed || baselineRequest) establishBaseline(slot, scan, now);
            else processSlot(slot, scan, now, true);
            return;
        }

        if (clickedTargetDuringRequest) {
            if (now - requestTargetClickAt < TARGET_OPEN_WAIT_MS) return;
            clickedTargetDuringRequest = false;
        }

        if (now - requestLastNavigationAt < NAVIGATION_COOLDOWN_MS) return;

        // Background hard refresh must only navigate the second Display. Never
        // fall back to a main-screen LINE root just because the secondary root is
        // temporarily missing.
        AccessibilityNodeInfo root = findLineRootForAutomation(secondaryMode);
        if (root != null) {
            if (clickTargetInTree(root, slot.name)) {
                clickedTargetDuringRequest = true;
                requestTargetClickAt = now;
                requestLastNavigationAt = now;
                prefs.markChecked(slot.index, now, baselineRequest
                    ? "正在開啟聊天室建立基準"
                    : "已找到目標 · 正在進入第二畫面聊天室");
                return;
            }

            if (!requestChatsTabOpened) {
                if (clickChatsTabInTree(root)) {
                    requestChatsTabOpened = true;
                    requestScrollToTopSteps = 0;
                    requestScrollForwardSteps = 0;
                    requestLastNavigationAt = now;
                    prefs.markChecked(slot.index, now, "已復位到 LINE 聊天列表 · 正在搜尋「" + slot.name + "」");
                    return;
                }

                if (requestBackSteps < MAX_BACK_STEPS && clickBackInTree(root)) {
                    requestBackSteps++;
                    requestLastNavigationAt = now;
                    prefs.markChecked(slot.index, now,
                        "正在退出目前 LINE 頁面 · 尋找聊天列表 " + requestBackSteps + "/" + MAX_BACK_STEPS);
                    return;
                }
            } else {
                if (requestScrollToTopSteps < MAX_SCROLL_TO_TOP_STEPS) {
                    if (scrollLargestScrollableNode(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                        requestScrollToTopSteps++;
                        requestLastNavigationAt = now;
                        prefs.markChecked(slot.index, now, "聊天列表復位中 · 正在回到頂端");
                        return;
                    }
                    requestScrollToTopSteps = MAX_SCROLL_TO_TOP_STEPS;
                }

                if (requestScrollForwardSteps < MAX_SCROLL_FORWARD_STEPS) {
                    if (scrollLargestScrollableNode(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                        requestScrollForwardSteps++;
                        requestLastNavigationAt = now;
                        prefs.markChecked(slot.index, now,
                            "聊天列表搜尋中 · 第 " + (requestScrollForwardSteps + 1) + " 頁");
                        return;
                    }
                    requestScrollForwardSteps = MAX_SCROLL_FORWARD_STEPS;
                }

                prefs.markChecked(slot.index, now, "已回聊天列表 · 目前可見清單仍找不到「" + slot.name + "」");
            }
        }

        if (now - requestStartedAt > REQUEST_TIMEOUT_MS) timeoutRunnable.run();
    }

    private AccessibilityNodeInfo findLineRootForAutomation(boolean secondaryOnly) {
        if (!secondaryOnly) {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (isLineRoot(active)) return active;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all != null) {
                int preferredDisplay = VirtualDisplayEngine.displayId();
                if (preferredDisplay >= 0) {
                    AccessibilityNodeInfo preferred = firstLineRoot(all.get(preferredDisplay));
                    if (preferred != null) return preferred;
                    if (secondaryOnly) return null;
                }

                for (int i = 0; i < all.size(); i++) {
                    int displayId = all.keyAt(i);
                    if (displayId == 0) continue;
                    AccessibilityNodeInfo root = firstLineRoot(all.valueAt(i));
                    if (root != null) return root;
                }
            }
        }

        return null;
    }

    private AccessibilityNodeInfo firstLineRoot(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (isLineRoot(root)) return root;
        }
        return null;
    }

    private void establishBaseline(Prefs.Slot slot, ScanResult scan, long now) {
        prefs.armSlot(slot.index, scan.readCount, scan.maxReadBottom, scan.incomingSignature, now);
        prefs.appendHistory(formatDateTime(now) + "｜" + slot.name + "｜基準已建立");
        prefs.setGlobalStatus(slot.name + " 基準已建立");
        Toast.makeText(this, "「" + slot.name + "」基準已自動建立 ✓", Toast.LENGTH_LONG).show();
        if (requestedSlot >= 0) clearRequest(false);
    }

    private boolean isSecondaryBackgroundMode(Prefs.Slot slot) {
        if (slot == null || !slot.backgroundActive()) return false;
        if (!prefs.virtualDisplayRunning()) return false;
        return prefs.virtualDisplayId() >= 0;
    }

    private void evaluateVisibleMonitoredChat(long now) {
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (!slot.active()) continue;

            boolean secondaryMode = isSecondaryBackgroundMode(slot);
            ScanResult scan = scanAvailableLineWindows(slot.name, secondaryMode);
            if (!scan.targetVisible) continue;

            if (!slot.armed) {
                establishBaseline(slot, scan, now);
                manualVisibleSlot = slot.index;
                manualVisibleSince = now;
                manualLastSeenAt = now;
                return;
            }

            if (secondaryMode) {
                manualVisibleSlot = -1;
                manualVisibleSince = 0L;
                manualLastSeenAt = 0L;
                processSlot(prefs.slot(slot.index), scan, now, false);
                return;
            }

            boolean newVisibleSession = manualVisibleSlot != slot.index
                || manualLastSeenAt <= 0L
                || now - manualLastSeenAt > MANUAL_SESSION_GAP_MS;

            manualLastSeenAt = now;

            if (newVisibleSession) {
                manualVisibleSlot = slot.index;
                manualVisibleSince = now;
                prefs.updateReadBaseline(slot.index, scan.readCount, scan.maxReadBottom, now);
                prefs.updateIncomingSignature(slot.index, scan.incomingSignature, now);
                prefs.markChecked(slot.index, now, "監控已對準聊天室");
                prefs.setGlobalStatus("即時監控「" + slot.name + "」");
                return;
            }

            if (now - manualVisibleSince < MANUAL_STABLE_MS) return;
            processSlot(prefs.slot(slot.index), scan, now, false);
            return;
        }

        if (manualLastSeenAt > 0L && now - manualLastSeenAt > MANUAL_SESSION_GAP_MS) {
            manualVisibleSlot = -1;
            manualVisibleSince = 0L;
            manualLastSeenAt = 0L;
        }
    }

    private void processSlot(Prefs.Slot slot, ScanResult scan, long now, boolean automatedPoll) {
        if (!slot.armed) {
            establishBaseline(slot, scan, now);
            return;
        }

        boolean secondaryMode = isSecondaryBackgroundMode(slot);
        if (secondaryMode) {
            int displayId = prefs.virtualDisplayId();
            health.markTargetChatScan(
                slot.index,
                displayId,
                buildDiagnosticTreeSignature(scan),
                now
            );

            // Main-screen and secondary Display layouts can differ. The first
            // real target-chat scan on each new Display is a takeover calibration,
            // never an event.
            if (!health.isSecondaryCalibrated(slot.index, displayId)) {
                prefs.updateReadBaseline(slot.index, scan.readCount, scan.maxReadBottom, now);
                prefs.updateIncomingSignature(slot.index, scan.incomingSignature, now);
                health.markSecondaryCalibrated(slot.index, displayId);
                prefs.markChecked(slot.index, now, "第二畫面基準已同步 · 監控中");
                prefs.setGlobalStatus("第二畫面已接管「" + slot.name + "」· 基準同步完成");
                if (automatedPoll) clearRequest(false);
                return;
            }
        }

        boolean eventFired = false;
        boolean detectorRebased = false;

        if (slot.readEnabled) {
            if (prefs.needsReadDetectorRebaseline(slot.index)) {
                prefs.rebaselineReadDetector(slot.index, scan.readCount, scan.maxReadBottom, now);
                detectorRebased = true;
            } else {
                ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(slot.baseCount, slot.baseMaxY);
                ReadDetector.Snapshot current = new ReadDetector.Snapshot(scan.readCount, scan.maxReadBottom);
                float density = getResources().getDisplayMetrics().density;
                int thresholdPx = secondaryMode
                    ? Math.max(6, (int) (6 * density))
                    : Math.max(24, (int) (36 * density));

                if (ReadDetector.isNewRead(baseline, current, thresholdPx)) {
                    String interval = slot.lastUnreadSeenAt > 0
                        ? formatTime(slot.lastUnreadSeenAt) + "～" + formatTime(now)
                        : "未知～" + formatTime(now);
                    prefs.appendHistory(formatDateTime(now) + "｜" + slot.name + "｜✦ 已讀｜可能區間 " + interval);
                    if (slot.notifyEnabled) {
                        notifyEvent(slot, "✦ " + slot.name + " 已讀了", "首次確認 " + formatTime(now) + " · 可能區間 " + interval, 100 + slot.index);
                    }
                    prefs.updateReadBaseline(slot.index, scan.readCount, scan.maxReadBottom, now);
                    eventFired = true;
                } else {
                    prefs.markUnreadSeen(slot.index, now);
                }
            }
        }

        Prefs.Slot fresh = prefs.slot(slot.index);
        if (fresh.messageEnabled && !scan.incomingSignature.isEmpty()) {
            String oldSig = fresh.incomingSignature;
            if (!oldSig.isEmpty() && !oldSig.equals(scan.incomingSignature)) {
                String preview = scan.incomingPreview.isEmpty() ? "偵測到聊天室內容更新" : scan.incomingPreview;
                String history = formatDateTime(now) + "｜" + slot.name + "｜💬 新訊息";
                if (fresh.saveMessageContentEnabled && !scan.incomingPreview.isEmpty()) {
                    history += "｜" + sanitizeHistoryText(scan.incomingPreview);
                }
                prefs.appendHistory(history);
                if (fresh.notifyEnabled) {
                    notifyEvent(fresh, "💬 " + slot.name, preview, 200 + slot.index);
                }
                eventFired = true;
            }
            prefs.updateIncomingSignature(slot.index, scan.incomingSignature, now);
        }

        String status = eventFired
            ? "剛偵測到更新"
            : detectorRebased ? "已讀偵測器已重新校準 · 監控中" : "監控中";
        prefs.markChecked(slot.index, now, status);
        prefs.setGlobalStatus("剛檢查「" + slot.name + "」");

        if (automatedPoll) clearRequest(false);
    }

    private String buildDiagnosticTreeSignature(ScanResult scan) {
        if (scan == null) return "";
        String raw = (scan.targetVisible ? "1" : "0")
            + "|" + scan.readCount
            + "|" + scan.maxReadBottom
            + "|" + scan.incomingSignature;
        return Integer.toHexString(raw.hashCode());
    }

    private String sanitizeHistoryText(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 240) clean = clean.substring(0, 240) + "…";
        return clean;
    }

    private void clearRequest(boolean returnHome) {
        requestedSlot = -1;
        requestStartedAt = 0L;
        clickedTargetDuringRequest = false;
        baselineRequest = false;
        resetRequestNavigationState();
        handler.removeCallbacks(timeoutRunnable);
        if (returnHome) handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 550L);
    }

    private void resetRequestNavigationState() {
        requestChatsTabOpened = false;
        requestBackSteps = 0;
        requestScrollToTopSteps = 0;
        requestScrollForwardSteps = 0;
        requestLastNavigationAt = 0L;
        requestTargetClickAt = 0L;
    }

    private boolean clickTargetInTree(AccessibilityNodeInfo root, String target) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(target);
        if (matches == null) return false;
        for (AccessibilityNodeInfo node : matches) {
            if (node == null) continue;
            String text = node.getText() == null ? "" : node.getText().toString();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            if (!matchesTarget(text, target) && !matchesTarget(desc, target)) continue;
            if (clickNodeOrAncestor(node)) return true;
        }
        return false;
    }

    private boolean clickChatsTabInTree(AccessibilityNodeInfo root) {
        if (root == null) return false;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int minY = rootBounds.top + (int) (rootBounds.height() * 0.52f);

        for (String label : CHAT_TAB_LABELS) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            if (matches == null) continue;
            for (AccessibilityNodeInfo node : matches) {
                if (node == null) continue;
                String text = normalize(node.getText() == null ? "" : node.getText().toString());
                String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
                if (!CHAT_TAB_LABELS.contains(text) && !CHAT_TAB_LABELS.contains(desc)) continue;
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.centerY() < minY) continue;
                if (clickNodeOrAncestor(node)) return true;
            }
        }
        return false;
    }

    private boolean clickBackInTree(AccessibilityNodeInfo root) {
        if (root == null) return false;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int maxY = rootBounds.top + (int) (rootBounds.height() * 0.28f);

        for (String label : BACK_LABELS) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            if (matches == null) continue;
            for (AccessibilityNodeInfo node : matches) {
                if (node == null) continue;
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.centerY() > maxY) continue;
                String text = normalize(node.getText() == null ? "" : node.getText().toString());
                String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
                if (!BACK_LABELS.contains(text) && !BACK_LABELS.contains(desc)) continue;
                if (clickNodeOrAncestor(node)) return true;
            }
        }

        // Some LINE builds expose the back arrow without a textual label. In that
        // case choose a small clickable action in the top-left header of this LINE
        // root. This stays inside the secondary Display tree and avoids a global
        // Android Back action that could affect the user's main screen.
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        int maxX = rootBounds.left + (int) (rootBounds.width() * 0.28f);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            if (node.isVisibleToUser() && node.isClickable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.centerX() <= maxX && r.centerY() <= maxY
                    && r.width() > 0 && r.height() > 0
                    && r.width() < rootBounds.width() * 0.35f
                    && r.height() < rootBounds.height() * 0.20f) {
                    long area = (long) r.width() * (long) r.height();
                    if (area < bestArea) {
                        bestArea = area;
                        best = node;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        return best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = node;
        for (int depth = 0; depth < 6 && clickable != null; depth++) {
            if (clickable.isClickable() && clickable.isVisibleToUser()) {
                return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            clickable = clickable.getParent();
        }
        return false;
    }

    private boolean scrollLargestScrollableNode(AccessibilityNodeInfo root, int action) {
        if (root == null) return false;
        AccessibilityNodeInfo best = null;
        long bestArea = -1L;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            if (node.isVisibleToUser() && node.isScrollable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                long area = (long) Math.max(0, r.width()) * (long) Math.max(0, r.height());
                if (area > bestArea) {
                    bestArea = area;
                    best = node;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        return best != null && best.performAction(action);
    }

    private ScanResult scanAvailableLineWindows(String target) {
        return scanAvailableLineWindows(target, false);
    }

    private ScanResult scanAvailableLineWindows(String target, boolean preferSecondary) {
        boolean sawAcceptedLineWindow = false;
        boolean targetVisible = false;
        int readCount = 0;
        int maxReadBottom = -1;
        List<MessageToken> incoming = new ArrayList<>();

        int preferredDisplayId = prefs.virtualDisplayRunning() ? prefs.virtualDisplayId() : -1;
        boolean preferredDisplayReported = false;
        boolean lineOnPreferredDisplay = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all != null) {
                for (int displayIndex = 0; displayIndex < all.size(); displayIndex++) {
                    int displayId = all.keyAt(displayIndex);
                    List<AccessibilityWindowInfo> windows = all.valueAt(displayIndex);
                    if (displayId == preferredDisplayId) preferredDisplayReported = true;
                    if (windows == null) continue;

                    for (AccessibilityWindowInfo window : windows) {
                        if (window == null) continue;
                        AccessibilityNodeInfo root = window.getRoot();
                        if (!isLineRoot(root)) continue;

                        boolean onPreferred = preferredDisplayId >= 0 && displayId == preferredDisplayId;
                        if (onPreferred) {
                            lineOnPreferredDisplay = true;
                            prefs.markSecondaryLineSeen(displayId, System.currentTimeMillis());
                            prefs.updateVirtualDisplayStatus(
                                "Display " + displayId + " · Accessibility 已看到 LINE root");
                        }

                        if (preferSecondary && preferredDisplayId >= 0 && !onPreferred) continue;

                        sawAcceptedLineWindow = true;
                        ScanResult result = scanTree(root, target);
                        if (!result.targetVisible) continue;
                        targetVisible = true;
                        readCount += result.readCount;
                        maxReadBottom = Math.max(maxReadBottom, result.maxReadBottom);
                        incoming.addAll(result.incomingTokens);
                    }
                }
            }
        } else {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (!isLineRoot(root)) continue;
                    sawAcceptedLineWindow = true;
                    ScanResult result = scanTree(root, target);
                    if (!result.targetVisible) continue;
                    targetVisible = true;
                    readCount += result.readCount;
                    maxReadBottom = Math.max(maxReadBottom, result.maxReadBottom);
                    incoming.addAll(result.incomingTokens);
                }
            }
        }

        if (preferredDisplayId >= 0 && !lineOnPreferredDisplay) {
            long now = System.currentTimeMillis();
            if (now - lastSecondaryDiagnosticAt >= SECONDARY_DIAGNOSTIC_MS) {
                lastSecondaryDiagnosticAt = now;
                if (preferredDisplayReported) {
                    prefs.updateVirtualDisplayStatus(
                        "Display " + preferredDisplayId + " 已被 Accessibility 看見 · 目前沒有 LINE window");
                } else {
                    prefs.updateVirtualDisplayStatus(
                        "Display " + preferredDisplayId + " 已建立 · Accessibility 尚未回報這個 Display");
                }
            }
        }

        if (!sawAcceptedLineWindow && !preferSecondary) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isLineRoot(root)) {
                ScanResult result = scanTree(root, target);
                if (result.targetVisible) {
                    targetVisible = true;
                    readCount = result.readCount;
                    maxReadBottom = result.maxReadBottom;
                    incoming.addAll(result.incomingTokens);
                }
            }
        }

        Collections.sort(incoming, Comparator.comparingInt(t -> t.top));
        return buildScanResult(targetVisible, readCount, maxReadBottom, incoming);
    }

    private boolean isLineRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && LINE_PACKAGE.contentEquals(root.getPackageName());
    }

    private ScanResult scanTree(AccessibilityNodeInfo root, String target) {
        int readCount = 0;
        int maxReadBottom = -1;
        boolean targetVisible = false;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int headerLimit = rootBounds.top + Math.max(1, (int) (rootBounds.height() * 0.25f));
        int leftMessageLimit = rootBounds.left + (int) (rootBounds.width() * 0.62f);
        int readMarkerMinX = rootBounds.left + (int) (rootBounds.width() * 0.32f);
        int composerGuard = Math.max(72, (int) (rootBounds.height() * 0.10f));
        int messageBottomLimit = rootBounds.bottom - composerGuard;
        List<MessageToken> candidates = new ArrayList<>();
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            String text = normalize(node.getText() == null ? "" : node.getText().toString());
            String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if ((matchesTarget(text, target) || matchesTarget(desc, target)) && r.top <= headerLimit) {
                targetVisible = true;
            }

            boolean inReadArea = r.top > headerLimit
                && r.bottom < messageBottomLimit
                && r.centerX() > readMarkerMinX;
            if (inReadArea && (isReadLabel(text) || isReadLabel(desc))) {
                readCount++;
                maxReadBottom = Math.max(maxReadBottom, r.bottom);
            }

            if (r.top > headerLimit && r.bottom < messageBottomLimit && r.centerX() < leftMessageLimit) {
                String token = "";
                if (usefulIncomingToken(text)) {
                    token = text;
                } else if (!isLikelyInteractiveControl(node) && usefulIncomingToken(desc)) {
                    token = desc;
                }
                if (!token.isEmpty()) candidates.add(new MessageToken(r.top, token));
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }

        Collections.sort(candidates, Comparator.comparingInt(t -> t.top));
        return buildScanResult(targetVisible, readCount, maxReadBottom, candidates);
    }

    private boolean isLikelyInteractiveControl(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        CharSequence cls = node.getClassName();
        String name = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
        if (name.contains("button") || name.contains("edittext")) return true;
        return node.isClickable() && node.getText() == null;
    }

    private ScanResult buildScanResult(boolean targetVisible, int readCount, int maxReadBottom, List<MessageToken> tokens) {
        List<MessageToken> clean = dedupe(tokens);
        int start = Math.max(0, clean.size() - 12);
        StringBuilder sig = new StringBuilder();
        for (int i = start; i < clean.size(); i++) {
            MessageToken token = clean.get(i);
            if (sig.length() > 0) sig.append('|');
            sig.append(token.value);
        }
        String preview = clean.isEmpty() ? "" : clean.get(clean.size() - 1).value;
        return new ScanResult(
            targetVisible,
            readCount,
            maxReadBottom,
            Integer.toHexString(sig.toString().hashCode()),
            preview,
            clean
        );
    }

    private List<MessageToken> dedupe(List<MessageToken> values) {
        List<MessageToken> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MessageToken value : values) {
            String key = value.top + "\u0000" + value.value;
            if (seen.add(key)) out.add(value);
        }
        return out;
    }

    private boolean usefulIncomingToken(String value) {
        if (value == null || value.isEmpty()) return false;
        if (isReadLabel(value)) return false;
        if (TIME_LIKE.matcher(value).matches()) return false;
        if (isUiControlToken(value)) return false;
        return value.length() <= 500;
    }

    private boolean isUiControlToken(String value) {
        String normalized = normalize(value);
        if (UI_LABELS.contains(normalized)) return true;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("顯示加號選單")
            || lower.contains("加號選單")
            || lower.contains("從照片、影片按鈕")
            || lower.contains("open plus menu")
            || lower.contains("photos and videos button")
            || lower.contains("プラスメニューを表示")
            || lower.contains("写真・動画ボタン")
            || lower.contains("사진 및 동영상 버튼");
    }

    private boolean isReadLabel(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        if (READ_LABELS.contains(normalized)) return true;

        if (containsDelimitedReadMarker(normalized, "已讀")) return true;
        if (containsDelimitedReadMarker(normalized, "已读")) return true;
        if (containsDelimitedReadMarker(normalized, "既読")) return true;
        if (containsDelimitedReadMarker(normalized, "읽음")) return true;

        String lower = normalized.toLowerCase(Locale.ROOT);
        return containsDelimitedReadMarker(lower, "read");
    }

    private boolean containsDelimitedReadMarker(String value, String marker) {
        if (value == null || marker == null || value.isEmpty() || marker.isEmpty()) return false;
        int from = 0;
        while (from < value.length()) {
            int at = value.indexOf(marker, from);
            if (at < 0) return false;
            int end = at + marker.length();
            boolean leftOk = at == 0 || isReadBoundary(value.charAt(at - 1));
            boolean rightOk = end >= value.length()
                || isReadBoundary(value.charAt(end))
                || Character.isDigit(value.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
        return false;
    }

    private boolean isReadBoundary(char c) {
        return Character.isWhitespace(c)
            || c == ',' || c == '，' || c == '、'
            || c == ':' || c == '：' || c == '·' || c == '•'
            || c == '-' || c == '－' || c == '|' || c == '｜';
    }

    private boolean matchesTarget(String raw, String target) {
        String value = normalize(raw);
        String wanted = normalize(target);
        if (value.isEmpty() || wanted.isEmpty()) return false;
        return value.equals(wanted) || value.contains(wanted);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replace("\u200B", "").replace("\u200E", "").replace("\u200F", "")
            .replace('\u00A0', ' ').trim();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "LINE Radar 已讀與訊息提醒",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("監控對象已讀與新訊息的重要提醒");
            nm.createNotificationChannel(channel);
        }
    }

    private void notifyEvent(Prefs.Slot slot, String title, String text, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, id, intent, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(Notification.PRIORITY_HIGH);

        if (slot.vibrateEnabled) b.setVibrate(new long[]{0, 160, 90, 220});
        nm.notify(7311 + id, b.build());
    }

    private static Set<String> setOf(String... values) {
        Set<String> out = new HashSet<>();
        Collections.addAll(out, values);
        return out;
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }

    private static String formatDateTime(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }

    private static final class MessageToken {
        final int top;
        final String value;
        MessageToken(int top, String value) {
            this.top = top;
            this.value = value == null ? "" : value;
        }
    }

    private static final class ScanResult {
        final boolean targetVisible;
        final int readCount;
        final int maxReadBottom;
        final String incomingSignature;
        final String incomingPreview;
        final List<MessageToken> incomingTokens;

        ScanResult(boolean targetVisible, int readCount, int maxReadBottom,
                   String incomingSignature, String incomingPreview, List<MessageToken> incomingTokens) {
            this.targetVisible = targetVisible;
            this.readCount = readCount;
            this.maxReadBottom = maxReadBottom;
            this.incomingSignature = incomingSignature == null ? "" : incomingSignature;
            this.incomingPreview = incomingPreview == null ? "" : incomingPreview;
            this.incomingTokens = incomingTokens == null ? new ArrayList<>() : incomingTokens;
        }
    }
}
