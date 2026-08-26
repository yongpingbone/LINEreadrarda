package com.example.linereadradar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Safe background message source for LINE Radar.
 *
 * Important: this service observes notifications only. It never opens a LINE chat, never clicks a
 * notification and never invokes a PendingIntent from LINE. Therefore receiving a message through
 * this path cannot itself mark the conversation read.
 */
public class LineNotificationListener extends NotificationListenerService {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String CHANNEL_ID = "line_read_radar_events_v069";
    private static final long DUPLICATE_WINDOW_MS = 6000L;

    private final Map<Integer, String> lastFingerprint = new HashMap<>();
    private final Map<Integer, Long> lastFingerprintAt = new HashMap<>();
    private Prefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ensureNotificationChannel();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (prefs == null) prefs = new Prefs(this);
        prefs.setGlobalStatus("安全背景模式 · LINE 通知監控已連線");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (prefs == null) prefs = new Prefs(this);
        prefs.setGlobalStatus("LINE 通知存取已中斷 · 新訊息背景監控暫停");
        notifyDisconnect();
        try { requestRebind(new android.content.ComponentName(this, LineNotificationListener.class)); }
        catch (Throwable ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !LINE_PACKAGE.equals(sbn.getPackageName())) return;
        if (prefs == null) prefs = new Prefs(this);
        if (!prefs.globalEnabled() || prefs.paused()) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        if (extras == null) return;

        String title = firstNonEmpty(
            text(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)),
            text(extras.getCharSequence(Notification.EXTRA_TITLE)),
            text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        );
        String body = firstNonEmpty(
            text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
            text(extras.getCharSequence(Notification.EXTRA_TEXT))
        );
        if (title.isEmpty() && body.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (!slot.backgroundActive() || !slot.messageEnabled) continue;
            if (!matchesSlot(slot.name, title)) continue;

            String fingerprint = normalize(title) + "\u0000" + normalize(body) + "\u0000" + sbn.getKey();
            String old = lastFingerprint.get(i);
            long oldAt = lastFingerprintAt.containsKey(i) ? lastFingerprintAt.get(i) : 0L;
            if (fingerprint.equals(old) && now - oldAt <= DUPLICATE_WINDOW_MS) continue;
            lastFingerprint.put(i, fingerprint);
            lastFingerprintAt.put(i, now);

            String preview = body.isEmpty() ? "收到新的 LINE 訊息" : body;
            String history = formatDateTime(now) + "｜" + slot.name + "｜💬 新訊息";
            if (slot.saveMessageContentEnabled && !body.isEmpty()) {
                history += "｜" + sanitize(body);
            }
            prefs.appendHistory(history);
            prefs.markChecked(i, now, "新訊息由通知安全補抓 · 未開啟聊天室");
            prefs.setGlobalStatus("安全背景模式 · 剛收到「" + slot.name + "」新訊息");

            if (slot.notifyEnabled) {
                notifyMessage(slot, preview, 200 + i);
            }
        }
    }

    private boolean matchesSlot(String slotName, String title) {
        String wanted = normalize(slotName);
        String candidate = normalize(title);
        if (wanted.isEmpty() || candidate.isEmpty()) return false;
        if (candidate.equals(wanted)) return true;
        // LINE may decorate the title with unread counts or group/member context. Keep matching
        // conservative so a short name does not fan out to unrelated conversations.
        if (wanted.length() >= 3 && candidate.startsWith(wanted + " ")) return true;
        if (wanted.length() >= 3 && candidate.startsWith(wanted + "（")) return true;
        if (wanted.length() >= 3 && candidate.startsWith(wanted + "(")) return true;
        return false;
    }

    private void notifyMessage(Prefs.Slot slot, String preview, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, id, openApp, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("💬 " + slot.name)
            .setContentText(preview)
            .setStyle(new Notification.BigTextStyle().bigText(preview))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(Notification.PRIORITY_HIGH);
        if (slot.vibrateEnabled) b.setVibrate(new long[]{0, 160, 90, 220});
        nm.notify(7311 + id, b.build());
    }

    private void notifyDisconnect() {
        if (prefs == null || prefs.paused() || prefs.backgroundActiveCount() <= 0) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 7390, openApp, flags);

        String text = "通知存取已中斷，新訊息背景監控暫停";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        nm.notify(7390, b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 監控中斷")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build());
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "LINE Radar 已讀與訊息提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("監控對象已讀、新訊息與監控中斷提醒");
        nm.createNotificationChannel(channel);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace("\u200B", "").replace("\u200E", "").replace("\u200F", "")
            .replace('\u00A0', ' ').trim();
    }

    private static String sanitize(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 240 ? clean.substring(0, 240) + "…" : clean;
    }

    private static String formatDateTime(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(new Date(ms));
    }
}
