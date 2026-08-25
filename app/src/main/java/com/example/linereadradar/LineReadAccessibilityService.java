package com.example.linereadradar;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
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
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class LineReadAccessibilityService extends AccessibilityService {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String READ_TEXT = "已讀";
    private static final String CHANNEL_ID = "line_read_radar";
    private static final int NOTIFY_ID = 7311;
    private static final long DEBOUNCE_MS = 300L;
    private static final long BASELINE_STABLE_MS = 650L;
    private static final long TARGET_HINT_MS = 5000L;

    private long lastScanAt = 0L;
    private Prefs prefs;
    private int candidateReadCount = Integer.MIN_VALUE;
    private int candidateMaxReadBottom = Integer.MIN_VALUE;
    private String candidateTarget = "";
    private long candidateSeenAt = 0L;
    private long lastTargetHintAt = 0L;
    private String lastHintTarget = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable baselineVerifier = this::verifyBaselineCandidate;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ensureNotificationChannel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!LINE_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!prefs.enabled() || prefs.target().isEmpty()) return;

        String target = prefs.target();
        long now = System.currentTimeMillis();
        if (eventHintsTarget(event, target)) {
            lastHintTarget = target;
            lastTargetHintAt = now;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - lastScanAt < DEBOUNCE_MS) return;
        lastScanAt = nowElapsed;
        evaluateCurrentLineUi(now);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacks(baselineVerifier);
        super.onDestroy();
    }

    private void evaluateCurrentLineUi(long now) {
        if (!prefs.enabled() || prefs.target().isEmpty()) return;

        String target = prefs.target();
        ScanResult scan = scanAvailableLineWindows(target);
        boolean targetVisible = scan.targetVisible || hasFreshTargetHint(target, now);
        if (!targetVisible) return;

        if (!prefs.armed()) {
            if (!isStableBaseline(scan, target, now)) return;
            armBaseline(scan, target, now);
            return;
        }

        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(prefs.baseCount(), prefs.baseMaxY());
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(scan.readCount, scan.maxReadBottom);
        int thresholdPx = Math.max(24, (int) (48 * getResources().getDisplayMetrics().density));

        if (ReadDetector.isNewRead(baseline, current, thresholdPx)) {
            long armedAt = prefs.armedAt();
            String elapsed = armedAt > 0 ? formatElapsed(now - armedAt) : "未知";
            prefs.appendHistory(formatDateTime(now) + "｜" + target + "｜首次偵測已讀｜監控 " + elapsed);
            prefs.finish();
            handler.removeCallbacks(baselineVerifier);
            notifyStatus(target + " 已讀了", "首次偵測：" + formatTime(now) + "｜監控時間：" + elapsed, true);
        }
    }

    private boolean isStableBaseline(ScanResult scan, String target, long now) {
        boolean same = target.equals(candidateTarget)
            && scan.readCount == candidateReadCount
            && scan.maxReadBottom == candidateMaxReadBottom;

        if (!same) {
            candidateTarget = target;
            candidateReadCount = scan.readCount;
            candidateMaxReadBottom = scan.maxReadBottom;
            candidateSeenAt = now;
            scheduleBaselineVerification();
            return false;
        }

        if (now - candidateSeenAt >= BASELINE_STABLE_MS) return true;
        scheduleBaselineVerification();
        return false;
    }

    private void scheduleBaselineVerification() {
        handler.removeCallbacks(baselineVerifier);
        handler.postDelayed(baselineVerifier, BASELINE_STABLE_MS + 100L);
    }

    private void verifyBaselineCandidate() {
        if (!prefs.enabled() || prefs.armed() || prefs.target().isEmpty()) return;

        long now = System.currentTimeMillis();
        String target = prefs.target();
        ScanResult scan = scanAvailableLineWindows(target);
        boolean targetVisible = scan.targetVisible || hasFreshTargetHint(target, now);
        if (!targetVisible) return;

        boolean same = target.equals(candidateTarget)
            && scan.readCount == candidateReadCount
            && scan.maxReadBottom == candidateMaxReadBottom;

        if (same && now - candidateSeenAt >= BASELINE_STABLE_MS) {
            armBaseline(scan, target, now);
            return;
        }

        candidateTarget = target;
        candidateReadCount = scan.readCount;
        candidateMaxReadBottom = scan.maxReadBottom;
        candidateSeenAt = now;
        scheduleBaselineVerification();
    }

    private void armBaseline(ScanResult scan, String target, long now) {
        if (!prefs.enabled() || prefs.armed()) return;
        prefs.arm(scan.readCount, scan.maxReadBottom, now);
        resetCandidate();
        handler.removeCallbacks(baselineVerifier);
        notifyStatus("監控已就緒", "已鎖定「" + target + "」目前的已讀基準。", false);
    }

    private void resetCandidate() {
        candidateTarget = "";
        candidateReadCount = Integer.MIN_VALUE;
        candidateMaxReadBottom = Integer.MIN_VALUE;
        candidateSeenAt = 0L;
    }

    private boolean eventHintsTarget(AccessibilityEvent event, String target) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return false;

        CharSequence desc = event.getContentDescription();
        if (matchesTarget(desc == null ? "" : desc.toString(), target)) return true;

        List<CharSequence> eventText = event.getText();
        if (eventText != null) {
            for (CharSequence cs : eventText) {
                if (matchesTarget(cs == null ? "" : cs.toString(), target)) return true;
            }
        }
        return false;
    }

    private boolean hasFreshTargetHint(String target, long now) {
        return target.equals(lastHintTarget)
            && lastTargetHintAt > 0L
            && now - lastTargetHintAt <= TARGET_HINT_MS;
    }

    private ScanResult scanAvailableLineWindows(String target) {
        boolean sawLineWindow = false;
        boolean targetVisible = false;
        int readCount = 0;
        int maxReadBottom = -1;

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
            }
        }

        if (!sawLineWindow) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (isLineRoot(root)) {
                ScanResult result = scanTree(root, target);
                targetVisible = result.targetVisible;
                readCount = result.readCount;
                maxReadBottom = result.maxReadBottom;
            }
        }

        return new ScanResult(targetVisible, readCount, maxReadBottom);
    }

    private boolean isLineRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        return LINE_PACKAGE.contentEquals(root.getPackageName());
    }

    private ScanResult scanTree(AccessibilityNodeInfo root, String target) {
        int readCount = 0;
        int maxReadBottom = -1;
        boolean targetVisible = false;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int headerLimit = rootBounds.top + Math.max(1, (int) (rootBounds.height() * 0.25f));
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            CharSequence textCs = node.getText();
            CharSequence descCs = node.getContentDescription();
            String text = textCs == null ? "" : textCs.toString();
            String desc = descCs == null ? "" : descCs.toString();
            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if ((matchesTarget(text, target) || matchesTarget(desc, target)) && r.top <= headerLimit) {
                targetVisible = true;
            }

            if (READ_TEXT.equals(normalize(text)) || READ_TEXT.equals(normalize(desc))) {
                readCount++;
                maxReadBottom = Math.max(maxReadBottom, r.bottom);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        return new ScanResult(targetVisible, readCount, maxReadBottom);
    }

    private boolean matchesTarget(String raw, String target) {
        String value = normalize(raw);
        String wanted = normalize(target);
        if (value.isEmpty() || wanted.isEmpty()) return false;
        return value.equals(wanted) || value.contains(wanted);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value
            .replace("\u200B", "")
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace('\u00A0', ' ')
            .trim();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "LINE 已讀提醒", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("指定 LINE 聊天室首次偵測到新的已讀狀態時通知");
            nm.createNotificationChannel(channel);
        }
    }

    private void notifyStatus(String title, String text, boolean highPriority) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new android.app.Notification.Builder(this, CHANNEL_ID)
            : new android.app.Notification.Builder(this);

        b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE);

        if (highPriority) {
            b.setPriority(android.app.Notification.PRIORITY_HIGH);
            b.setVibrate(new long[]{0, 200, 100, 250});
        }
        nm.notify(NOTIFY_ID, b.build());
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }

    private static String formatDateTime(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }

    private static String formatElapsed(long ms) {
        long total = Math.max(0L, ms / 1000L);
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) return h + "小時" + m + "分" + s + "秒";
        if (m > 0) return m + "分" + s + "秒";
        return s + "秒";
    }

    private static final class ScanResult {
        final boolean targetVisible;
        final int readCount;
        final int maxReadBottom;

        ScanResult(boolean targetVisible, int readCount, int maxReadBottom) {
            this.targetVisible = targetVisible;
            this.readCount = readCount;
            this.maxReadBottom = maxReadBottom;
        }
    }
}
