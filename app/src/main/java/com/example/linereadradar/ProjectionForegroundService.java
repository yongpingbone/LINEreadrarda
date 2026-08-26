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

/**
 * v0.6.18 P0 safe background runtime.
 *
 * Device evidence from v0.6.16/v0.6.17 proved that keeping the monitored LINE chat rendered on a
 * secondary display still causes incoming messages to be marked read. Focus ownership is not a
 * sufficient no-read boundary. Until a non-UI source for outgoing read receipts is proven, the
 * production-facing background path must not keep LINE inside a chat.
 *
 * New messages are observed by LineNotificationListener. This foreground service only keeps the
 * user's explicit Radar session visible to Android and checks notification-listener health. It
 * deliberately does not create a VirtualDisplay, launch LINE, pulse a LINE task, navigate a chat,
 * or start either Focus Shield prototype.
 */
public class ProjectionForegroundService extends Service {
    private static final String ACTION_START = "com.example.linereadradar.START_SAFE_BACKGROUND";
    private static final String CHANNEL_ID = "line_radar_background_silent_v070";
    private static final String DISCONNECT_CHANNEL_ID = "line_radar_disconnect_v070";
    private static final int NOTIFICATION_ID = 7355;
    private static final int DISCONNECT_NOTIFICATION_ID = 7356;
    private static final long HEALTH_CHECK_MS = 5000L;

    private Prefs prefs;
    private PowerManager.WakeLock wakeLock;
    private boolean notificationAccessWasReady = false;
    private boolean disconnectAlerted = false;
    private boolean legacyDisplayCleanupAttempted = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            boolean access = NotificationAccess.isEnabled(ProjectionForegroundService.this);
            if (access) {
                notificationAccessWasReady = true;
                disconnectAlerted = false;
                if (prefs != null && prefs.backgroundActiveCount() > 0) {
                    prefs.setGlobalStatus("安全背景模式 · LINE 通知監控中 · 不開啟聊天室");
                }
            } else {
                if (prefs != null && prefs.backgroundActiveCount() > 0) {
                    prefs.setGlobalStatus("安全背景模式等待通知存取 · 不會自動開啟 LINE");
                }
                if (notificationAccessWasReady) {
                    maybeAlertDisconnect("通知存取已中斷，新訊息背景監控暫停");
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
        NoReadOverlayShield.close();
        NoReadShieldActivity.closeShield();
        try { context.stopService(new Intent(context, ProjectionForegroundService.class)); }
        catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ensureChannels();
        acquireWakeLock();
        cleanupLegacyDisplayOnce();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        NoReadOverlayShield.close();
        NoReadShieldActivity.closeShield();
        cleanupLegacyDisplayOnce();

        notificationAccessWasReady = NotificationAccess.isEnabled(this);
        if (prefs.backgroundActiveCount() > 0) {
            prefs.setGlobalStatus(notificationAccessWasReady
                ? "安全背景模式 · LINE 通知監控中 · 不開啟聊天室"
                : "安全背景模式等待通知存取 · 不會自動開啟 LINE");
        }

        handler.removeCallbacks(healthCheck);
        handler.post(healthCheck);
        updateNotification();
        return START_STICKY;
    }

    private void cleanupLegacyDisplayOnce() {
        if (legacyDisplayCleanupAttempted) return;
        legacyDisplayCleanupAttempted = true;

        // v0.6.16 MULTIPLE_TASK could leave a Radar-owned secondary display alive. v0.6.18 never
        // launches LINE in background, so release only the Radar VirtualDisplay itself. We do not
        // force-stop LINE and do not remove arbitrary user tasks from recents.
        try {
            VirtualDisplayEngine.attach(this);
            if (VirtualDisplayEngine.alive() || prefs.virtualDisplayRunning()) {
                VirtualDisplayEngine.release();
            }
        } catch (Throwable ignored) {}
        prefs.setVirtualDisplayStopped("v0.6.18 安全模式 · 第二畫面聊天室監控已停用");
    }

    private void maybeAlertDisconnect(String reason) {
        if (disconnectAlerted || prefs == null || prefs.paused() || prefs.backgroundActiveCount() <= 0) return;
        disconnectAlerted = true;

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 7356, openApp, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, DISCONNECT_CHANNEL_ID)
            : new Notification.Builder(this);
        nm.notify(DISCONNECT_NOTIFICATION_ID, b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 監控中斷")
            .setContentText(reason)
            .setStyle(new Notification.BigTextStyle().bigText(reason))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build());
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(healthCheck);
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LineRadar:SafeBackgroundMonitor");
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

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel silent = new NotificationChannel(
            CHANNEL_ID,
            "背景監控狀態（靜默）",
            NotificationManager.IMPORTANCE_MIN
        );
        silent.setDescription("Android 前景服務必要狀態；不彈出、不震動、不發聲");
        silent.setSound(null, null);
        silent.enableVibration(false);
        silent.setShowBadge(false);
        silent.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(silent);

        NotificationChannel disconnect = new NotificationChannel(
            DISCONNECT_CHANNEL_ID,
            "監控中斷提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        disconnect.setDescription("只有背景監控真的中斷時才提醒");
        disconnect.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(disconnect);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 55, open, flags);

        boolean access = NotificationAccess.isEnabled(this);
        String text = access
            ? "安全模式 · LINE 通知監控中 · 不開啟聊天室"
            : "安全模式 · 等待通知存取權限 · 不開啟聊天室";

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 背景監控")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
