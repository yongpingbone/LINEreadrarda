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
    private static final String CHANNEL_ID = "line_radar_background_silent_v070";
    private static final String DISCONNECT_CHANNEL_ID = "line_radar_disconnect_v070";
    private static final int NOTIFICATION_ID = 7355;
    private static final int DISCONNECT_NOTIFICATION_ID = 7356;
    private static final long HEALTH_CHECK_MS = 2500L;
    private static final long LINE_RETRY_MS = 4500L;
    private static final long LINE_PULSE_MS = 2500L;
    private static final long POLL_OPEN_DELAY_MS = 650L;

    private Prefs prefs;
    private HealthDiagnostics health;
    private PowerManager.WakeLock wakeLock;
    private boolean displaySetupInProgress = false;
    private boolean displayQueryInProgress = false;
    private boolean lineLaunchInProgress = false;
    private boolean linePulseInProgress = false;
    private boolean secondaryPollInProgress = false;
    private boolean sessionWasReady = false;
    private boolean disconnectAlerted = false;
    private long lastLineLaunchAttemptAt = 0L;
    private long lastLinePulseAt = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (!ShizukuBridge.binderReady()) {
                VirtualDisplayEngine.setExternalStatus("Shizuku 已停止 · 背景與息屏監控暫停");
                maybeAlertDisconnect("Shizuku 服務已中斷，背景監控暫停");
                updateNotification();
                handler.postDelayed(this, HEALTH_CHECK_MS);
                return;
            }

            if (!ShizukuBridge.permissionGranted()) {
                VirtualDisplayEngine.setExternalStatus("等待 LINE Radar 的 Shizuku 授權");
                maybeAlertDisconnect("Shizuku 授權失效，背景監控暫停");
                updateNotification();
                handler.postDelayed(this, HEALTH_CHECK_MS);
                return;
            }

            reconcileRemoteDisplay();
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
        VirtualDisplayEngine.attach(context);
        VirtualDisplayEngine.release();
        try { context.stopService(new Intent(context, ProjectionForegroundService.class)); }
        catch (Throwable ignored) {}
        new Prefs(context).setVirtualDisplayStopped("第二畫面已關閉");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        health = new HealthDiagnostics(this);
        VirtualDisplayEngine.attach(this);
        ensureChannels();
        restoreReadyState();
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
            maybeAlertDisconnect("Shizuku 服務已中斷，背景監控暫停");
        } else if (!ShizukuBridge.permissionGranted()) {
            VirtualDisplayEngine.setExternalStatus("Shizuku 已啟動 · 等待 Radar 授權");
            maybeAlertDisconnect("Shizuku 授權失效，背景監控暫停");
        } else {
            reconcileRemoteDisplay();
        }

        handler.removeCallbacks(healthCheck);
        handler.postDelayed(healthCheck, HEALTH_CHECK_MS);
        updateNotification();
        return START_STICKY;
    }

    private void restoreReadyState() {
        int id = prefs.virtualDisplayId();
        long now = System.currentTimeMillis();
        sessionWasReady = id > 0
            && prefs.virtualDisplayRunning()
            && prefs.secondaryLineDisplayId() == id
            && now - prefs.secondaryLineSeenAt() <= 15000L
            && health.hasRecentBackgroundTarget(prefs, id, now);
    }

    private void reconcileRemoteDisplay() {
        if (displaySetupInProgress || displayQueryInProgress) return;
        displayQueryInProgress = true;

        VirtualDisplayEngine.restore(this, remote -> {
            displayQueryInProgress = false;
            if (!remote.success || remote.displayId <= 0) {
                maybeAlertDisconnect("第二螢幕已中斷，Radar 正在自動重建");
                createDisplayAndLaunchLine();
                return;
            }

            int id = remote.displayId;

            // Recovery actions are intentionally gated by Display/Shizuku/background
            // health, not by Accessibility. Their purpose is to recover when
            // Accessibility has stopped seeing LINE.
            if (shouldRunRecovery(id)) {
                pulseLineTask(id);
                maybeHardRefreshMonitoredChat(id);
            }

            boolean lineVerified = isLineVerified(id);
            boolean targetReady = hasRecentTargetChat(id);

            if (lineVerified && targetReady) {
                markMonitorHealthy();
                prefs.updateVirtualDisplayStatus(
                    "Display " + id + " · 目標聊天室已定位 · 背景＋息屏監控就緒");
                prefs.setGlobalStatus("背景＋息屏持續監控中 · Display " + id);
            } else if (lineVerified) {
                maybeAlertDisconnect("目標聊天室已停止更新，Radar 正在重新定位");
                prefs.updateVirtualDisplayStatus(
                    "Display " + id + " · LINE 已驗證 · 尚未定位到監控聊天室");
                prefs.setGlobalStatus("LINE 已驗證 · 正在定位監控聊天室");
            } else {
                maybeAlertDisconnect("LINE 第二螢幕已失聯超過 15 秒，Radar 正在自我修復");
                prefs.updateVirtualDisplayStatus(
                    "Display " + id + " · LINE root 超過 15 秒未回報 · 自我修復中");
                prefs.setGlobalStatus("第二畫面自我修復中 · Display " + id);
                long now = System.currentTimeMillis();
                if (!lineLaunchInProgress && now - lastLineLaunchAttemptAt >= LINE_RETRY_MS) {
                    retryLineLaunch();
                }
            }
            updateNotification();
        });
    }

    private void markMonitorHealthy() {
        sessionWasReady = true;
        disconnectAlerted = false;
    }

    private void maybeAlertDisconnect(String reason) {
        if (!sessionWasReady || disconnectAlerted) return;
        if (prefs == null || prefs.paused() || prefs.backgroundActiveCount() <= 0) return;
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

        Notification n = b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 監控中斷")
            .setContentText(reason)
            .setStyle(new Notification.BigTextStyle().bigText(reason))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build();
        nm.notify(DISCONNECT_NOTIFICATION_ID, n);
    }

    private boolean shouldRunRecovery(int id) {
        return id > 0
            && prefs.globalEnabled()
            && !prefs.paused()
            && prefs.backgroundActiveCount() > 0
            && prefs.virtualDisplayRunning()
            && prefs.virtualDisplayId() == id
            && ShizukuBridge.binderReady()
            && ShizukuBridge.permissionGranted();
    }

    private boolean hasRecentTargetChat(int id) {
        return health.hasRecentBackgroundTarget(prefs, id, System.currentTimeMillis());
    }

    private void pulseLineTask(int id) {
        long now = System.currentTimeMillis();
        if (id <= 0 || linePulseInProgress || secondaryPollInProgress || lineLaunchInProgress
            || now - lastLinePulseAt < LINE_PULSE_MS) return;

        lastLinePulseAt = now;
        linePulseInProgress = true;
        ShizukuBridge.pulseLineOnDisplay(id, pulse -> {
            linePulseInProgress = false;
            if (pulse.success) {
                long successAt = System.currentTimeMillis();
                health.markPulseSuccess(id, successAt);
                if (hasRecentTargetChat(id)) {
                    prefs.setGlobalStatus("背景持續監控中 · Display " + id + " · LINE task 活躍");
                } else {
                    prefs.setGlobalStatus("LINE task 活躍 · 正在定位監控聊天室");
                }
                return;
            }

            prefs.updateVirtualDisplayStatus(
                "Display " + id + " · LINE task heartbeat 失敗 · " + pulse.message);
            long retryNow = System.currentTimeMillis();
            if (!lineLaunchInProgress && retryNow - lastLineLaunchAttemptAt >= LINE_RETRY_MS) {
                retryLineLaunch();
            }
        });
    }

    private void maybeHardRefreshMonitoredChat(int id) {
        if (id <= 0 || secondaryPollInProgress || lineLaunchInProgress) return;
        if (!shouldRunRecovery(id)) return;

        long now = System.currentTimeMillis();
        if (now - prefs.lastPollAt() < prefs.pollIntervalMs()) return;

        int slot = prefs.nextBackgroundSlot();
        if (slot < 0) return;

        Prefs.Slot target = prefs.slot(slot);
        prefs.setLastPollAt(now);
        prefs.markChecked(slot, now, "第二畫面重新對準中");
        secondaryPollInProgress = true;

        VirtualDisplayEngine.launchLine(this, launch -> {
            if (!launch.success) {
                secondaryPollInProgress = false;
                prefs.markChecked(slot, System.currentTimeMillis(), "第二畫面重新對準失敗");
                prefs.updateVirtualDisplayStatus(
                    "Display " + id + " · 無法重新喚醒 LINE · " + launch.message);
                return;
            }

            handler.postDelayed(() -> {
                long refreshAt = System.currentTimeMillis();
                Intent command = new Intent(MonitorForegroundService.ACTION_POLL);
                command.setPackage(getPackageName());
                command.putExtra(MonitorForegroundService.EXTRA_SLOT, slot);
                sendBroadcast(command);
                health.markHardRefresh(id, refreshAt);
                prefs.setGlobalStatus("第二畫面正在尋找「" + target.name + "」");
                secondaryPollInProgress = false;
            }, POLL_OPEN_DELAY_MS);
        });
    }

    private void createDisplayAndLaunchLine() {
        if (displaySetupInProgress) return;
        displaySetupInProgress = true;
        prefs.setGlobalStatus("正在建立 Shizuku 常駐第二畫面");

        VirtualDisplayEngine.create(this, display -> {
            if (!display.success) {
                displaySetupInProgress = false;
                prefs.setVirtualDisplayStopped(display.message);
                prefs.setGlobalStatus("第二畫面建立失敗 · " + display.message);
                maybeAlertDisconnect("第二螢幕建立失敗，背景監控已中斷");
                updateNotification();
                return;
            }

            health.resetForNewDisplay(display.displayId);

            lastLineLaunchAttemptAt = System.currentTimeMillis();
            lineLaunchInProgress = true;
            VirtualDisplayEngine.launchLine(this, launch -> {
                displaySetupInProgress = false;
                lineLaunchInProgress = false;
                if (!launch.success) {
                    prefs.updateVirtualDisplayStatus(launch.message);
                    prefs.setGlobalStatus("第二畫面已建立 · LINE 尚未進入");
                    maybeAlertDisconnect("LINE 無法回到第二螢幕，背景監控已中斷");
                    updateNotification();
                    return;
                }

                prefs.updateVirtualDisplayStatus(
                    "Display " + display.displayId
                        + " · Trusted / Always unlocked · 等待 Accessibility 驗證 LINE");
                prefs.setGlobalStatus("第二畫面等待 LINE 驗證 · Display " + display.displayId);
                MonitorForegroundService.stop(this);
                updateNotification();
            });
        });
    }

    private void retryLineLaunch() {
        int id = VirtualDisplayEngine.displayId();
        if (id < 0 || lineLaunchInProgress || prefs.backgroundActiveCount() <= 0) return;

        lastLineLaunchAttemptAt = System.currentTimeMillis();
        lineLaunchInProgress = true;
        prefs.updateVirtualDisplayStatus("Display " + id + " · 正在把 LINE 拉回第二畫面");
        VirtualDisplayEngine.launchLine(this, launch -> {
            lineLaunchInProgress = false;
            if (!launch.success) {
                prefs.updateVirtualDisplayStatus(launch.message);
            } else {
                prefs.updateVirtualDisplayStatus(
                    "Display " + id + " · LINE 已重新送入 · 等待 Accessibility 最新畫面");
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
        displayQueryInProgress = false;
        lineLaunchInProgress = false;
        linePulseInProgress = false;
        secondaryPollInProgress = false;

        // Deliberately DO NOT release the Shizuku-owned Virtual Display here.
        // Android may recreate this foreground service while the phone is locked.
        // The display must survive independently in the daemon UserService.
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LineRadar:AlwaysOnMonitor");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ExperimentalLabActivity.class);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 55, open, f);

        String text;
        int id = VirtualDisplayEngine.displayId();
        boolean lineVerified = isLineVerified(id);
        boolean targetReady = hasRecentTargetChat(id);

        if (!ShizukuBridge.binderReady()) text = "Shizuku 未啟動 · 監控暫停";
        else if (!ShizukuBridge.permissionGranted()) text = "等待 Shizuku 授權";
        else if (displaySetupInProgress) text = "正在建立 Always-unlocked 第二畫面";
        else if (displayQueryInProgress && id < 0) text = "正在接回 Shizuku 第二畫面";
        else if (secondaryPollInProgress) text = "Display " + id + " · 正在重新對準聊天室";
        else if (lineLaunchInProgress) text = "Display " + id + " · 正在把 LINE 拉回第二畫面";
        else if (linePulseInProgress) text = "Display " + id + " · LINE task heartbeat";
        else if (id < 0) text = "正在建立第二畫面";
        else if (lineVerified && targetReady) text = "Display " + id + " · 目標聊天室已定位 · 背景＋息屏監控";
        else if (lineVerified) text = "Display " + id + " · LINE 已驗證 · 正在定位監控聊天室";
        else if (shouldRunRecovery(id)) text = "Display " + id + " · LINE root 未回報 · 自我修復中";
        else text = prefs.virtualDisplayStatus();

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
