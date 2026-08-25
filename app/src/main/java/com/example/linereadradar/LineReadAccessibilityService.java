package com.example.linereadradar;

import android.accessibilityservice.AccessibilityService;
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
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class LineReadAccessibilityService extends AccessibilityService {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String READ_TEXT = "已讀";
    private static final String CHANNEL_ID = "line_read_radar_events_v041";
    private static final long DEBOUNCE_MS = 250L;
    private static final long POLL_TIMEOUT_MS = 12000L;
    private static final long MANUAL_SESSION_GAP_MS = 1600L;
    private static final long MANUAL_STABLE_MS = 1000L;
    private static final Pattern TIME_LIKE = Pattern.compile("^(上午|下午)?\\s*\\d{1,2}:\\d{2}$|^\\d{1,2}/\\d{1,2}$|^昨天$|^今天$");

    private Prefs prefs;
    private long lastScanAt = 0L;
    private int requestedSlot = -1;
    private long requestStartedAt = 0L;
    private boolean clickedTargetDuringRequest = false;

    private int manualVisibleSlot = -1;
    private long manualVisibleSince = 0L;
    private long manualLastSeenAt = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable timeoutRunnable = () -> {
        if (requestedSlot < 0) return;
        Prefs.Slot slot = prefs.slot(requestedSlot);
        prefs.markChecked(requestedSlot, System.currentTimeMillis(), "找不到聊天室，等待下次或手動開啟 LINE");
        prefs.setGlobalStatus("⚠ 找不到「" + slot.name + "」，等待下次巡檢");
        clearPollRequest(false);
    };

    private final BroadcastReceiver pollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !MonitorForegroundService.ACTION_POLL.equals(intent.getAction())) return;
            int slot = intent.getIntExtra(MonitorForegroundService.EXTRA_SLOT, -1);
            if (slot < 0 || slot >= Prefs.MAX_SLOTS) return;
            requestedSlot = slot;
            requestStartedAt = System.currentTimeMillis();
            clickedTargetDuringRequest = false;
            handler.removeCallbacks(timeoutRunnable);
            handler.postDelayed(timeoutRunnable, POLL_TIMEOUT_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ensureNotificationChannel();
        IntentFilter filter = new IntentFilter(MonitorForegroundService.ACTION_POLL);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pollReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(pollReceiver, filter);
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
        try { unregisterReceiver(pollReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void evaluateRequestedSlot(long now) {
        if (requestedSlot < 0) return;
        Prefs.Slot slot = prefs.slot(requestedSlot);
        if (!slot.active()) {
            clearPollRequest(false);
            return;
        }

        ScanResult scan = scanAvailableLineWindows(slot.name);
        if (scan.targetVisible) {
            processSlot(slot, scan, now, true);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isLineRoot(root) && !clickedTargetDuringRequest) {
            if (clickTargetInTree(root, slot.name)) {
                clickedTargetDuringRequest = true;
                prefs.setGlobalStatus("🔎 已找到「" + slot.name + "」，正在開啟聊天室");
                return;
            }
        }

        if (now - requestStartedAt > POLL_TIMEOUT_MS) timeoutRunnable.run();
    }

    private void evaluateVisibleMonitoredChat(long now) {
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (!slot.active()) continue;
            ScanResult scan = scanAvailableLineWindows(slot.name);
            if (!scan.targetVisible) continue;

            boolean newVisibleSession = manualVisibleSlot != slot.index
                || manualLastSeenAt <= 0L
                || now - manualLastSeenAt > MANUAL_SESSION_GAP_MS;

            manualLastSeenAt = now;

            if (newVisibleSession) {
                manualVisibleSlot = slot.index;
                manualVisibleSince = now;
                if (!slot.armed) {
                    prefs.armSlot(slot.index, scan.readCount, scan.maxReadBottom, scan.incomingSignature, now);
                    prefs.appendHistory(formatDateTime(now) + "｜" + slot.name + "｜建立即時監控基準");
                } else {
                    prefs.updateReadBaseline(slot.index, scan.readCount, scan.maxReadBottom, now);
                    prefs.updateIncomingSignature(slot.index, scan.incomingSignature, now);
                    prefs.markChecked(slot.index, now, "即時監控已對準聊天室");
                }
                prefs.setGlobalStatus("🟢 即時監控「" + slot.name + "」");
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
            prefs.armSlot(slot.index, scan.readCount, scan.maxReadBottom, scan.incomingSignature, now);
            prefs.appendHistory(formatDateTime(now) + "｜" + slot.name + "｜建立監控基準");
            prefs.setGlobalStatus("🟢 " + slot.name + " 基準已建立");
            if (automatedPoll) clearPollRequest(true);
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
                prefs.appendHistory(formatDateTime(now) + "｜" + slot.name + "｜💬 偵測到新訊息內容變化");
                if (fresh.notifyEnabled) {
                    notifyEvent(fresh, "💬 " + slot.name + " 有新訊息", "聊天室於 " + formatTime(now) + " 偵測到更新", 200 + slot.index);
                }
                eventFired = true;
            }
            prefs.updateIncomingSignature(slot.index, scan.incomingSignature, now);
        }

        prefs.markChecked(slot.index, now, eventFired ? "剛偵測到更新" : "監控中");
        prefs.setGlobalStatus("🟢 剛檢查「" + slot.name + "」");
        if (automatedPoll) clearPollRequest(true);
    }

    private void clearPollRequest(boolean returnHome) {
        requestedSlot = -1;
        requestStartedAt = 0L;
        clickedTargetDuringRequest = false;
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
        List<String> incoming = new ArrayList<>();

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
                if (!result.incomingSignature.isEmpty()) incoming.add(result.incomingSignature);
            }
        }

        if (!sawLineWindow) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isLineRoot(root)) {
                ScanResult result = scanTree(root, target);
                targetVisible = result.targetVisible;
                readCount = result.readCount;
                maxReadBottom = result.maxReadBottom;
                if (!result.incomingSignature.isEmpty()) incoming.add(result.incomingSignature);
            }
        }

        return new ScanResult(targetVisible, readCount, maxReadBottom, join(incoming));
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
        int leftMessageLimit = rootBounds.left + (int) (rootBounds.width() * 0.58f);
        List<String> incomingTokens = new ArrayList<>();
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            String text = normalize(node.getText() == null ? "" : node.getText().toString());
            String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if ((matchesTarget(text, target) || matchesTarget(desc, target)) && r.top <= headerLimit) targetVisible = true;

            if (READ_TEXT.equals(text) || READ_TEXT.equals(desc)) {
                readCount++;
                maxReadBottom = Math.max(maxReadBottom, r.bottom);
            }

            if (targetVisible && r.top > headerLimit && r.centerX() < leftMessageLimit) {
                String token = usefulIncomingToken(text) ? text : (usefulIncomingToken(desc) ? desc : "");
                if (!token.isEmpty()) incomingTokens.add(r.top + ":" + token);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }

        int start = Math.max(0, incomingTokens.size() - 12);
        StringBuilder sig = new StringBuilder();
        for (int i = start; i < incomingTokens.size(); i++) {
            if (sig.length() > 0) sig.append('|');
            sig.append(incomingTokens.get(i));
        }
        return new ScanResult(targetVisible, readCount, maxReadBottom, Integer.toHexString(sig.toString().hashCode()));
    }

    private boolean usefulIncomingToken(String value) {
        if (value == null || value.isEmpty()) return false;
        if (READ_TEXT.equals(value)) return false;
        if (TIME_LIKE.matcher(value).matches()) return false;
        if (value.equals("聊天") || value.equals("主頁") || value.equals("VOOM") || value.equals("錢包")) return false;
        return value.length() <= 300;
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

    private String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('|');
            out.append(value);
        }
        return out.toString();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "LINE Radar 事件提醒", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("已讀與聊天室新訊息補抓提醒");
            nm.createNotificationChannel(channel);
        }
    }

    private void notifyEvent(Prefs.Slot slot, String title, String text, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, id, intent, flags);

        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new android.app.Notification.Builder(this, CHANNEL_ID)
            : new android.app.Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPriority(android.app.Notification.PRIORITY_HIGH);

        if (slot.vibrateEnabled) b.setVibrate(new long[]{0, 160, 90, 220});
        nm.notify(7311 + id, b.build());
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }

    private static String formatDateTime(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }

    private static final class ScanResult {
        final boolean targetVisible;
        final int readCount;
        final int maxReadBottom;
        final String incomingSignature;

        ScanResult(boolean targetVisible, int readCount, int maxReadBottom, String incomingSignature) {
            this.targetVisible = targetVisible;
            this.readCount = readCount;
            this.maxReadBottom = maxReadBottom;
            this.incomingSignature = incomingSignature == null ? "" : incomingSignature;
        }
    }
}
