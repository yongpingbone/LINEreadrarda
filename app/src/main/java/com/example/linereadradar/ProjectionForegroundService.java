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
import android.os.PowerManager;

public class ProjectionForegroundService extends Service {
    private static final String ACTION_START = "com.example.linereadradar.START_SHIZUKU_DISPLAY";
    private static final String CHANNEL_ID = "line_radar_shizuku_display_v061";
    private static final int NOTIFICATION_ID = 7355;
    private static final long HEALTH_CHECK_MS = 2500L;
    private static final long LINE_RETRY_MS = 4500L;

    private Prefs prefs;
    private PowerManager.WakeLock wakeLock;
    private boolean displaySetupInProgress = false;
    private boolean lineLaunchInProgress = false;
    private long lastLineLaunchAttemptAt = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (!ShizukuBridge.binderReady()) {
                VirtualDisplayEngine.setExternalStatus("Shizuku 已停止 · 請重新啟動 Shizuku");
                updateNotification();
                handler.postDelayed(this, HEALTH_CHECK_MS);
                return;
            }

            if (!ShizukuBridge.permissionGranted()) {
                VirtualDisplayEngine.setExternalStatus("等待 LINE Radar 的 Shizuku 授權");
                updateNotification();
                handler.postDelayed(this, HEALTH_CHECK_MS);
                return;
            }

            if (!VirtualDisplayEngine.alive() && !displaySetupInProgress) {
                createDisplayAndLaunchLine();
            } else if (VirtualDisplayEngine.alive()) {
                int id = VirtualDisplayEngine.displayId();
                if (isLineVerified(id)) {
                    prefs.updateVirtualDisplayStatus("Display " + id + " · LINE 已驗證 · 背景監控就緒");
                } else {
                    long now = System.currentTimeMillis();
                    if (!displaySetupInProgress && !lineLaunchInProgress
                        && now - lastLineLaunchAttemptAt >= LINE_RETRY_MS) {
                        retryLineLaunch();
                    }
                }
            }

            updateNotification();
            handler.postDelayed(this, HEALTH_CHECK_MS);
        }
    };

    public static void start(Context context) {
        Intent i = new Intent(context, ProjectionForegroundService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        try { context.stopService(new Intent(context, ProjectionForegroundService.class)); }
        catch (Throwable ignored) {}
        VirtualDisplayEngine.release();
        new Prefs(context).setVirtualDisplayStopped("第二畫面已關閉");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ensureChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!ShizukuBridge.binderReady()) {
            VirtualDisplayEngine.setExternalStatus("Shizuku 尚未啟動 · 請先用無線偵錯啟動 Shizuku");
        } else if (!ShizukuBridge.permissionGranted()) {
            VirtualDisplayEngine.setExternalStatus("Shizuku 已啟動 · 等待 Radar 授權");
        } else if (!VirtualDisplayEngine.alive() && !displaySetupInProgress) {
            createDisplayAndLaunchLine();
        } else if (VirtualDisplayEngine.alive() && !isLineVerified(VirtualDisplayEngine.displayId())) {
            retryLineLaunch();
        }

        handler.removeCallbacks(healthCheck);
        handler.postDelayed(healthCheck, HEALTH_CHECK_MS);
        updateNotification();
        return START_STICKY;
    }

    private void createDisplayAndLaunchLine() {
        if (VirtualDisplayEngine.alive() || displaySetupInProgress) return;
        displaySetupInProgress = true;

        VirtualDisplayEngine.Result display = VirtualDisplayEngine.create(this);
        if (!display.success) {
            displaySetupInProgress = false;
            prefs.setVirtualDisplayStopped(display.message);
            prefs.setGlobalStatus("第二畫面建立失敗");
            updateNotification();
            return;
        }

        lastLineLaunchAttemptAt = System.currentTimeMillis();
        lineLaunchInProgress = true;
        VirtualDisplayEngine.launchLine(this, launch -> {
            displaySetupInProgress = false;
            lineLaunchInProgress = false;
            if (!launch.success) {
                prefs.updateVirtualDisplayStatus(launch.message);
                prefs.setGlobalStatus("第二畫面已建立 · LINE 尚未進入");
                updateNotification();
                return;
            }

            prefs.updateVirtualDisplayStatus(
                "Display " + display.displayId + " · LINE 啟動要求已送出 · 等待 Accessibility 驗證");
            prefs.setGlobalStatus("第二畫面等待 LINE 驗證 · Display " + display.displayId);
            MonitorForegroundService.stop(this);
            updateNotification();
        });
    }

    private void retryLineLaunch() {
        int id = VirtualDisplayEngine.displayId();
        if (id < 0 || lineLaunchInProgress) return;
        if (isLineVerified(id)) return;

        lastLineLaunchAttemptAt = System.currentTimeMillis();
        lineLaunchInProgress = true;
        prefs.updateVirtualDisplayStatus("Display " + id + " · 正在重新送 LINE 到第二畫面");
        VirtualDisplayEngine.launchLine(this, launch -> {
            lineLaunchInProgress = false;
            if (!launch.success) {
                prefs.updateVirtualDisplayStatus(launch.message);
            } else if (!isLineVerified(id)) {
                prefs.updateVirtualDisplayStatus("Display " + id + " · LINE 已重新啟動 · 等待 Accessibility 回報");
            }
            updateNotification();
        });
    }

    private boolean isLineVerified(int id) {
        return id >= 0
            && prefs.secondaryLineDisplayId() == id
            && System.currentTimeMillis() - prefs.secondaryLineSeenAt() <= 15000L;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(healthCheck);
        displaySetupInProgress = false;
        lineLaunchInProgress = false;
        VirtualDisplayEngine.release();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LineRadar:ShizukuDisplay");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Shizuku 第二畫面監控",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("維持非 Root Shizuku 第二 Display，包含息屏監控測試");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ExperimentalLabActivity.class);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 55, open, f);

        String text;
        int id = VirtualDisplayEngine.displayId();
        boolean lineVerified = isLineVerified(id);

        if (!ShizukuBridge.binderReady()) text = "Shizuku 未啟動 · 監控暫停";
        else if (!ShizukuBridge.permissionGranted()) text = "等待 Shizuku 授權";
        else if (displaySetupInProgress) text = "正在建立第二畫面並啟動 LINE";
        else if (lineLaunchInProgress) text = "Display " + id + " · 正在重新送 LINE";
        else if (id < 0) text = "正在建立第二畫面";
        else if (lineVerified) text = "Display " + id + " · LINE 已驗證 · 背景持續監控";
        else text = prefs.virtualDisplayStatus();

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · Shizuku 背景模式")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }
}
