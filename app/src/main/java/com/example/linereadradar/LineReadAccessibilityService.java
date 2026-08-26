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
    private static final String CHANNEL_ID = "line_read_radar_events_v053";
    private static final long DEBOUNCE_MS = 250L;
    private static final long WATCHDOG_MS = 700L;
    private static final long REQUEST_TIMEOUT_MS = 15000L;
    private static final long MANUAL_SESSION_GAP_MS = 1600L;
    private static final long MANUAL_STABLE_MS = 1000L;
    private static final Pattern TIME_LIKE = Pattern.compile(
        "^(上午|下午|AM|PM|am|pm)?\\s*\\d{1,2}:\\d{2}$|^\\d{1,2}/\\d{1,2}$|^昨天$|^今天$|^Yesterday$|^Today$|^昨日$|^今日$|^어제$|^오늘$"
    );

    private static final Set<String> READ_LABELS = setOf(
        "已讀", "已读", "Read", "READ", "read", "既読", "읽음"
    );

    private static final Set<String> UI_LABELS = setOf(
        "聊天", "主頁", "首页", "VOOM", "錢包", "钱包",
        "Chats", "Home", "Wallet", "Talk", "ホーム", "トーク", "ウォレット",
        "채팅", "홈", "지갑"
    );

    private Prefs prefs;
    private long lastScanAt = 0L;
    private int requestedSlot = -1;
    private long requestStartedAt = 0L;
    private boolean clickedTargetDuringRequest = false;
    private boolean baselineRequest = false;

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
            } catch (Throwable ignored) {
                // Accessibility trees can disappear while LINE changes windows. Retry next tick.
            }
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
            prefs.markChecked(requestedSlot, now, "背景檢查找不到聊天室");
            prefs.setGlobalStatus("找不到「" + slot.name + "」，等待下次背景檢查");
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
            handler.removeCallbacks(timeoutRunnable);
            handler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
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

        ScanResult scan = scanAvailableLineWindows(slot.name);
        if (scan.targetVisible) {
            if (!slot.armed || baselineRequest) establishBaseline(slot, scan, now);
            else processSlot(slot, scan, now, true);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isLineRoot(root) && !clickedTargetDuringRequest) {
            if (clickTargetInTree(root, slot.name)) {
                clickedTargetDuringRequest = true;
                prefs.markChecked(slot.index, now, baselineRequest ? "正在開啟聊天室建立基準" : "背景檢查正在開啟聊天室");
                return;
            }
        }

        if (now - requestStartedAt > REQUEST_TIMEOUT_MS) timeoutRunnable.run();
    }

    private void establishBaseline(Prefs.Slot slot, ScanResult scan, long now) {
        prefs.armSlot(slot.index, scan.readCount, scan.maxReadBottom, scan.incomingSignature, now);
        prefs.appendHistory(formatDateTime(now) + "｜" + slot.name + "｜基準已建立");
        prefs.setGlobalStatus(slot.name + " 基準已建立");
        Toast.makeText(this, "「" + slot.name + "」基準已自動建立 ✓", Toast.LENGTH_LONG).show();
        if (requestedSlot >= 0) clearRequest(false);
    }

    private void evaluateVisibleMonitoredChat(long now) {
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (!slot.active()) continue;
            ScanResult scan = scanAvailableLineWindows(slot.name);
            if (!scan.targetVisible) continue;

            if (!slot.armed) {
                establishBaseline(slot, scan, now);
                manualVisibleSlot = slot.index;
                manualVisibleSince = now;
                manualLastSeenAt = now;
                return;
            }

            boolean newVisibleSession = manualVisibleSlot != slot.index
                || manualLastSeenAt <= 0L
                || now - manualLastSeenAt > MANUAL_SESSION_GAP_MS;

            manualLastSeenAt = now;

            if (newVisibleSession) {
                manualVisibleSlot = slot.index;
                manualVisibleSince = now;
                prefs.setGlobalStatus("即時監控「" + slot.name + "」");
                processSlot(slot, scan, now, false);
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

        boolean eventFired = false;
        if (slot.readEnabled) {
            ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(slot.baseCount, slot.baseMaxY);
            ReadDetector.Snapshot current = new ReadDetector.Snapshot(scan.readCount, scan.maxReadBottom);
            int thresholdPx = Math.max(24, (int) (48 * getResources().getDisplayMetrics().density));
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

        if (!eventFired && slot.readEnabled) {
            prefs.updateReadBaseline(slot.index, scan.readCount, scan.maxReadBottom, now);
        }
        prefs.markChecked(slot.index, now, eventFired ? "剛偵測到更新" : "監控中");
        prefs.setGlobalStatus("剛檢查「" + slot.name + "」");
        if (automatedPoll) clearRequest(true);
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
        handler.removeCallbacks(timeoutRunnable);
        if (returnHome) handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 550L);
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
            AccessibilityNodeInfo clickable = node;
            for (int depth = 0; depth < 6 && clickable != null; depth++) {
                if (clickable.isClickable() && clickable.isVisibleToUser()) {
                    return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                clickable = clickable.getParent();
            }
        }
        return false;
    }

    private ScanResult scanAvailableLineWindows(String target) {
        boolean sawLineWindow = false;
        boolean targetVisible = false;
        int readCount = 0;
        int maxReadBottom = -1;
        List<MessageToken> incoming = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all != null) {
                for (int displayIndex = 0; displayIndex < all.size(); displayIndex++) {
                    List<AccessibilityWindowInfo> windows = all.valueAt(displayIndex);
                    if (windows == null) continue;
                    for (AccessibilityWindowInfo window : windows) {
                        if (window == null) continue;
                        AccessibilityNodeInfo root = window.getRoot();
                        if (!isLineRoot(root)) continue;
                        sawLineWindow = true;
                        ScanResult result = scanTree(root, target);
                        targetVisible |= result.targetVisible;
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
                    sawLineWindow = true;
                    ScanResult result = scanTree(root, target);
                    targetVisible |= result.targetVisible;
                    readCount += result.readCount;
                    maxReadBottom = Math.max(maxReadBottom, result.maxReadBottom);
                    incoming.addAll(result.incomingTokens);
                }
            }
        }

        if (!sawLineWindow) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isLineRoot(root)) {
                ScanResult result = scanTree(root, target);
                targetVisible = result.targetVisible;
                readCount = result.readCount;
                maxReadBottom = result.maxReadBottom;
                incoming.addAll(result.incomingTokens);
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

            if (isReadLabel(text) || isReadLabel(desc)) {
                readCount++;
                maxReadBottom = Math.max(maxReadBottom, r.bottom);
            }

            if (r.top > headerLimit && r.centerX() < leftMessageLimit) {
                String token = usefulIncomingToken(text) ? text : (usefulIncomingToken(desc) ? desc : "");
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

    private ScanResult buildScanResult(boolean targetVisible, int readCount, int maxReadBottom, List<MessageToken> tokens) {
        List<MessageToken> clean = dedupe(tokens);
        int start = Math.max(0, clean.size() - 12);
        StringBuilder sig = new StringBuilder();
        for (int i = start; i < clean.size(); i++) {
            MessageToken token = clean.get(i);
            if (sig.length() > 0) sig.append('|');
            sig.append(token.top).append(':').append(token.value);
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
        if (UI_LABELS.contains(value)) return false;
        return value.length() <= 500;
    }

    private boolean isReadLabel(String value) {
        String normalized = normalize(value);
        if (READ_LABELS.contains(normalized)) return true;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return "read".equals(lower);
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
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "LINE Radar 事件提醒", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("已讀與聊天室新訊息提醒");
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
