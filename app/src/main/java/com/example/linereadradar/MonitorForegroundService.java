package com.example.linereadradar;

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

    private static final String CHANNEL_ID = "line_read_radar_background_v057";
    private static final int NOTIFICATION_ID = 7310;
    private static final long HEARTBEAT_MS = 2000L;
    private static final long SINGLE_TARGET_ALIGN_MS = 5000L;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable stateChecker = new Runnable() {
        @Override
        public void run() {
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

            int displayId = VirtualDisplayEngine.displayId();
            if (displayId < 0) {
                // Background mode must not steal the user's main screen. Wait until the
                // user-approved second display exists instead of launching LINE here.
                prefs.setGlobalStatus("等待第二畫面 · 背景監控不會搶主畫面");
                updateNotification();
                handler.postDelayed(this, HEARTBEAT_MS);
                return;
            }

            // This is the intended background mode: LINE stays on the second display while
            // the user is free to use any app on the main display. Accessibility scans the
            // second display continuously; this heartbeat only re-aligns the requested chat.
            prefs.setGlobalStatus("🟢 第二畫面持續監控中 · Display " + displayId);

            long now = System.currentTimeMillis();
            long alignInterval = prefs.backgroundActiveCount() == 1
                ? SINGLE_TARGET_ALIGN_MS
                : Math.max(SINGLE_TARGET_ALIGN_MS, prefs.pollIntervalMs());
            if (now - prefs.lastPollAt() >= alignInterval) {
                alignTargetOnSecondDisplay(now);
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

    private void alignTargetOnSecondDisplay(long now) {
        int slot = prefs.nextBackgroundSlot();
        if (slot < 0) return;

        Prefs.Slot s = prefs.slot(slot);
        prefs.setLastPollAt(now);
        prefs.markChecked(slot, now, "第二畫面持續監控中");

        Intent command = new Intent(ACTION_POLL);
        command.setPackage(getPackageName());
        command.putExtra(EXTRA_SLOT, slot);
        sendBroadcast(command);

        // Do NOT start LINE on the main display here. The whole point of background mode is
        // that the second display keeps LINE alive while the user keeps using the main screen.
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "背景持續監控",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("LINE Radar 使用第二畫面持續監控，不影響主畫面使用");
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
        if (prefs.paused()) {
            detail = "全部監控已暫停";
        } else if (hasUnarmedActiveSlot()) {
            detail = "首次設定中 · 到 LINE 選擇指定聊天室即可";
        } else if (VirtualDisplayEngine.displayId() >= 0) {
            detail = "第二畫面持續監控中 · 主畫面可正常使用";
        } else {
            detail = "等待建立第二畫面 · 不會搶主畫面";
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar ✦")
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
