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

    private static final String CHANNEL_ID = "line_radar_legacy_status_silent_v070";
    private static final int NOTIFICATION_ID = 7310;
    private static final long HEARTBEAT_MS = 5000L;
    private static final long RETRY_LOCKED_MS = 15000L;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable stateChecker = new Runnable() {
        @Override
        public void run() {
            if (VirtualDisplayEngine.displayId() >= 0) {
                // ProjectionForegroundService already owns the foreground lifetime and status
                // notification while the second display exists. Avoid a duplicate notification.
                stopForeground(true);
                stopSelf();
                return;
            }

            if (prefs == null || !prefs.globalEnabled() || prefs.activeCount() == 0) {
                stopForeground(true);
                stopSelf();
                return;
            }

            if (prefs.paused()) {
                prefs.setGlobalStatus("全部監控已暫停");
                updateNotification();
                handler.postDelayed(this, HEARTBEAT_MS);
                return;
            }

            if (hasUnarmedActiveSlot()) {
                prefs.setGlobalStatus("首次設定中 · 請到 LINE 選擇指定聊天室");
                updateNotification();
                handler.postDelayed(this, HEARTBEAT_MS);
                return;
            }

            if (prefs.backgroundActiveCount() == 0) {
                prefs.setGlobalStatus("即時監控中");
                stopForeground(true);
                stopSelf();
                return;
            }

            long now = System.currentTimeMillis();
            long since = now - prefs.lastPollAt();
            if (since >= prefs.pollIntervalMs()) attemptPoll(now);

            updateNotification();
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    public static void start(Context context) {
        if (VirtualDisplayEngine.displayId() >= 0) return;
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
        if (VirtualDisplayEngine.displayId() >= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!prefs.globalEnabled() || prefs.activeCount() == 0) {
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

    private boolean hasUnarmedActiveSlot() {
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot s = prefs.slot(i);
            if (s.active() && !s.armed) return true;
        }
        return false;
    }

    private void attemptPoll(long now) {
        if (prefs.backgroundActiveCount() == 0) return;

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isDeviceLocked();
        if (locked) {
            prefs.setGlobalStatus("手機已安全鎖定 · 等待可檢查時機");
            long last = prefs.lastPollAt();
            if (now - last > RETRY_LOCKED_MS) {
                prefs.setLastPollAt(now - prefs.pollIntervalMs() + RETRY_LOCKED_MS);
            }
            return;
        }

        int slot = prefs.nextBackgroundSlot();
        if (slot < 0) return;
        Prefs.Slot s = prefs.slot(slot);
        prefs.setLastPollAt(now);
        prefs.setGlobalStatus("背景準備檢查「" + s.name + "」");
        prefs.markChecked(slot, now, "背景檢查準備中");

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
                prefs.markChecked(slot, now, "Android 阻擋背景開啟 LINE");
                prefs.setGlobalStatus("Android 阻擋背景開啟 LINE · 等待下次");
            }
        } else {
            prefs.markChecked(slot, now, "找不到 LINE App");
            prefs.setGlobalStatus("找不到 LINE App");
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "監控狀態（靜默）",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Android 前景服務必要狀態；不彈出、不震動、不發聲");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 20, openApp, pendingFlags);

        String detail;
        if (prefs.paused()) detail = "全部監控已暫停";
        else if (hasUnarmedActiveSlot()) detail = "首次設定中 · 到 LINE 選擇指定聊天室即可";
        else detail = prefs.globalStatus();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar")
            .setContentText(detail)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(Notification.PRIORITY_MIN)
            .setLocalOnly(true)
            .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }
}
