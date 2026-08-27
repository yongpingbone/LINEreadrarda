package com.example.linereadradar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * v0.7 single-target background read monitor.
 *
 * Priority order:
 * 1) keep one monitored LINE chat alive on a Shizuku VirtualDisplay during screen-off;
 * 2) preserve the already-proven ReadDetector path;
 * 3) avoid unnecessary heat while the phone screen is on.
 *
 * No-Read is best-effort only in the non-root build. This service deliberately does not start the
 * retired Activity/Accessibility Overlay shields. If LINE marks incoming messages read while the
 * monitored chat is rendered, screen-off read monitoring wins by product decision.
 */
public class ProjectionForegroundService extends Service {
    private static final String ACTION_START = "com.example.linereadradar.START_V07_READ_MONITOR";
    private static final String CHANNEL_ID = "line_radar_background_silent_v071";
    private static final String DISCONNECT_CHANNEL_ID = "line_radar_disconnect_v071";
    private static final int NOTIFICATION_ID = 7355;
    private static final int DISCONNECT_NOTIFICATION_ID = 7356;
    private static final long HEALTH_CHECK_MS = 5000L;
    private static final long LINE_RETRY_MS = 10000L;
    private static final long TARGET_RECOVERY_MS = 12000L;
    private static final long POLL_OPEN_DELAY_MS = 350L;

    private Prefs prefs;
    private HealthDiagnostics health;
    private PowerManager powerManager;
    private PowerManager.WakeLock screenOffWakeLock;
    private boolean displaySetupInProgress;
    private boolean displayQueryInProgress;
    private boolean lineLaunchInProgress;
    private boolean recoveryInProgress;
    private boolean disconnectAlerted;
    private long lastLineLaunchAttemptAt;
    private long lastTargetRecoveryAt;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                acquireScreenOffWakeLock();
                if (prefs != null && prefs.backgroundActiveCount() > 0) {
                    prefs.setGlobalStatus("熄屏已讀監控中 · 第二畫面維持運作");
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                releaseScreenOffWakeLock();
            }
            updateNotification();
        }
    };

    private final Runnable healthCheck = new Runnable() {
        @Override public void run() {
            try {
                if (prefs == null || !prefs.globalEnabled() || prefs.paused()
                    || prefs.backgroundActiveCount() <= 0) {
                    stopSelf();
                    return;
                }
                if (!ShizukuBridge.binderReady()) {
                    prefs.setGlobalStatus("Shizuku 已停止 · 熄屏監控暫停");
                    maybeAlertDisconnect("Shizuku 服務中斷，熄屏已讀監控暫停");
                } else if (!ShizukuBridge.permissionGranted()) {
                    prefs.setGlobalStatus("Shizuku 授權失效 · 熄屏監控暫停");
                    maybeAlertDisconnect("Shizuku 授權失效，熄屏已讀監控暫停");
                } else {
                    reconcileRemoteDisplay();
                }
                updateNotification();
            } catch (Throwable ignored) {}
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
        VirtualDisplayEngine.attach(context);
        VirtualDisplayEngine.release();
        try { context.stopService(new Intent(context, ProjectionForegroundService.class)); }
        catch (Throwable ignored) {}
        new Prefs(context).setVirtualDisplayStopped("第二畫面已關閉");
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        health = new HealthDiagnostics(this);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        VirtualDisplayEngine.attach(this);
        ensureChannels();
        registerScreenReceiver();
        if (powerManager != null && !powerManager.isInteractive()) acquireScreenOffWakeLock();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        handler.removeCallbacks(healthCheck);
        handler.post(healthCheck);
        return START_STICKY;
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
    }

    private void reconcileRemoteDisplay() {
        if (displaySetupInProgress || displayQueryInProgress) return;
        displayQueryInProgress = true;
        VirtualDisplayEngine.restore(this, remote -> {
            displayQueryInProgress = false;
            if (!remote.success || remote.displayId <= 0) {
                createDisplayAndLaunchLine();
                return;
            }

            int displayId = remote.displayId;
            long now = System.currentTimeMillis();
            boolean lineReady = isLineVerified(displayId);
            boolean targetReady = health.hasRecentBackgroundTarget(prefs, displayId, now);

            if (lineReady && targetReady) {
                disconnectAlerted = false;
                prefs.updateVirtualDisplayStatus("Display " + displayId + " · 單人已讀監控中");
                boolean screenOff = powerManager != null && !powerManager.isInteractive();
                prefs.setGlobalStatus(screenOff
                    ? "熄屏已讀監控中 · Display " + displayId
                    : "背景已讀監控中 · Display " + displayId);
                return;
            }

            if (!lineReady) {
                if (now - lastLineLaunchAttemptAt >= LINE_RETRY_MS) launchLine(displayId);
                return;
            }

            if (!recoveryInProgress && now - lastTargetRecoveryAt >= TARGET_RECOVERY_MS) {
                recoverTarget(displayId);
            }
        });
    }

    private void createDisplayAndLaunchLine() {
        if (displaySetupInProgress) return;
        displaySetupInProgress = true;
        prefs.setGlobalStatus("正在建立熄屏監控第二畫面");
        VirtualDisplayEngine.create(this, display -> {
            displaySetupInProgress = false;
            if (!display.success || display.displayId <= 0) {
                prefs.setGlobalStatus("第二畫面建立失敗 · " + display.message);
                maybeAlertDisconnect("第二畫面建立失敗，熄屏已讀監控暫停");
                return;
            }
            health.resetForNewDisplay(display.displayId);
            launchLine(display.displayId);
        });
    }

    private void launchLine(int displayId) {
        if (displayId <= 0 || lineLaunchInProgress) return;
        lineLaunchInProgress = true;
        lastLineLaunchAttemptAt = System.currentTimeMillis();
        prefs.updateVirtualDisplayStatus("Display " + displayId + " · 正在啟動 LINE");
        VirtualDisplayEngine.launchLine(this, result -> {
            lineLaunchInProgress = false;
            if (!result.success) {
                prefs.updateVirtualDisplayStatus("Display " + displayId + " · LINE 啟動失敗 · " + result.message);
                return;
            }
            handler.postDelayed(() -> recoverTarget(displayId), POLL_OPEN_DELAY_MS);
        });
    }

    private void recoverTarget(int displayId) {
        if (displayId <= 0 || recoveryInProgress || prefs.backgroundActiveCount() <= 0) return;
        int slot = prefs.nextBackgroundSlot();
        if (slot < 0) return;
        recoveryInProgress = true;
        lastTargetRecoveryAt = System.currentTimeMillis();
        Prefs.Slot target = prefs.slot(slot);
        prefs.markChecked(slot, lastTargetRecoveryAt, "正在對準第二畫面聊天室");

        ShizukuBridge.pulseLineOnDisplay(displayId, pulse -> {
            handler.postDelayed(() -> {
                Intent command = new Intent(MonitorForegroundService.ACTION_POLL);
                command.setPackage(getPackageName());
                command.putExtra(MonitorForegroundService.EXTRA_SLOT, slot);
                sendBroadcast(command);
                health.markHardRefresh(displayId, System.currentTimeMillis());
                prefs.setGlobalStatus("第二畫面正在對準「" + target.name + "」");
                recoveryInProgress = false;
            }, POLL_OPEN_DELAY_MS);
        });
    }

    private boolean isLineVerified(int displayId) {
        return displayId > 0
            && prefs.secondaryLineDisplayId() == displayId
            && System.currentTimeMillis() - prefs.secondaryLineSeenAt() <= 15000L;
    }

    private void maybeAlertDisconnect(String reason) {
        if (disconnectAlerted || prefs == null || prefs.backgroundActiveCount() <= 0 || prefs.paused()) return;
        disconnectAlerted = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 7356, open, flags);
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

    private void acquireScreenOffWakeLock() {
        try {
            if (powerManager == null) return;
            if (screenOffWakeLock == null) {
                screenOffWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "LineRadar:ScreenOffReadMonitor");
                screenOffWakeLock.setReferenceCounted(false);
            }
            if (!screenOffWakeLock.isHeld()) screenOffWakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseScreenOffWakeLock() {
        try {
            if (screenOffWakeLock != null && screenOffWakeLock.isHeld()) screenOffWakeLock.release();
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(healthCheck);
        releaseScreenOffWakeLock();
        try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel silent = new NotificationChannel(
            CHANNEL_ID, "熄屏已讀監控（靜默）", NotificationManager.IMPORTANCE_MIN);
        silent.setDescription("維持使用者主動開啟的單人熄屏已讀監控");
        silent.setSound(null, null);
        silent.enableVibration(false);
        silent.setShowBadge(false);
        silent.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(silent);

        NotificationChannel disconnect = new NotificationChannel(
            DISCONNECT_CHANNEL_ID, "監控中斷提醒", NotificationManager.IMPORTANCE_HIGH);
        disconnect.setDescription("只有熄屏已讀監控真的中斷時才提醒");
        disconnect.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(disconnect);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 55, open, flags);
        int displayId = VirtualDisplayEngine.displayId();
        boolean screenOff = powerManager != null && !powerManager.isInteractive();
        String text;
        if (!ShizukuBridge.binderReady()) text = "Shizuku 未啟動 · 監控暫停";
        else if (!ShizukuBridge.permissionGranted()) text = "等待 Shizuku 授權";
        else if (displaySetupInProgress) text = "正在建立第二畫面";
        else if (displayId <= 0) text = "正在接回第二畫面";
        else if (screenOff) text = "Display " + displayId + " · 熄屏已讀監控中";
        else text = "Display " + displayId + " · 單人背景已讀監控中";

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 已讀監控")
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
