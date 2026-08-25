package com.example.linereadradar;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class MonitorForegroundService extends Service {
    static final String ACTION_POLL = "com.example.linereadradar.POLL";
    static final String EXTRA_SLOT = "slot";

    private static final String CHANNEL_ID = "line_read_radar_background_v04";
    private static final int NOTIFICATION_ID = 7310;
    private static final long HEARTBEAT_MS = 5000L;
    private static final long RETRY_LOCKED_MS = 15000L;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable stateChecker = new Runnable() {
        @Override
        public void run() {
            if (prefs == null || !prefs.globalEnabled()) {
                stopForeground(true);
                stopSelf();
                return;
            }

            if (prefs.paused()) {
                prefs.setGlobalStatus("已暫停");
                updateNotification();
                handler.postDelayed(this, HEARTBEAT_MS);
                return;
            }

            long now = System.currentTimeMillis();
            long since = now - prefs.lastPollAt();
            if (since >= prefs.pollIntervalMs()) {
                attemptPoll(now);
            }

            updateNotification();
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MonitorForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.globalEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        handler.removeCallbacks(stateChecker);
        handler.post(stateChecker);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(stateChecker);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void attemptPoll(long now) {
        if (prefs.activeCount() == 0) {
            prefs.setGlobalStatus("沒有啟用中的對象");
            prefs.setLastPollAt(now);
            return;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isDeviceLocked();
        if (locked) {
            prefs.setGlobalStatus("🔒 手機已安全鎖定，解鎖後補抓");
            long last = prefs.lastPollAt();
            if (now - last > RETRY_LOCKED_MS) prefs.setLastPollAt(now - prefs.pollIntervalMs() + RETRY_LOCKED_MS);
            return;
        }

        int slot = prefs.nextActiveSlot();
        if (slot < 0) return;
        Prefs.Slot s = prefs.slot(slot);
        prefs.setLastPollAt(now);
        prefs.setGlobalStatus("🔎 準備檢查「" + s.name + "」");
        prefs.markChecked(slot, now, "等待 LINE 畫面");

        Intent command = new Intent(ACTION_POLL);
        command.setPackage(getPackageName());
        command.putExtra(EXTRA_SLOT, slot);
        sendBroadcast(command);

        Intent launch = getPackageManager().getLaunchIntentForPackage("jp.naver.line.android");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(launch);
            } catch (Exception ignored) {
                prefs.setGlobalStatus("⚠ Android 阻擋背景開啟 LINE，等待手動開啟");
            }
        } else {
            prefs.setGlobalStatus("⚠ 找不到 LINE App");
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "背景監控狀態",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("LINE Radar 背景輪巡與鎖定狀態");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 20, openApp, pendingFlags);

        String title = "LINE Radar ✦";
        String detail;
        if (prefs.paused()) detail = "已暫停 · 設定與紀錄都保留";
        else detail = prefs.globalStatus() + " · " + prefs.activeCount() + " 位監控中";

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }
}
