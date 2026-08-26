package com.example.linereadradar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

public class ProjectionForegroundService extends Service {
    private static final String ACTION_START = "com.example.linereadradar.START_PROJECTION";
    private static final String ACTION_STOP = "com.example.linereadradar.STOP_PROJECTION";
    private static final String EXTRA_RESULT_CODE = "projection_result_code";
    private static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String CHANNEL_ID = "line_radar_projection_v058";
    private static final String ALERT_CHANNEL_ID = "line_radar_projection_alert_v058";
    private static final int NOTIFICATION_ID = 7355;
    private static final int ALERT_ID = 7356;

    public static void start(Context context, int resultCode, Intent resultData) {
        Intent i = new Intent(context, ProjectionForegroundService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_RESULT_CODE, resultCode);
        i.putExtra(EXTRA_RESULT_DATA, resultData);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        new Prefs(context).setVirtualDisplayStopped("第二畫面已手動關閉");
        Intent i = new Intent(context, ProjectionForegroundService.class);
        i.setAction(ACTION_STOP);
        try { context.startService(i); } catch (Throwable ignored) {
            context.stopService(new Intent(context, ProjectionForegroundService.class));
            VirtualDisplayEngine.release();
        }
    }

    static void notifyProjectionEnded(Context context, String reason) {
        if (context == null) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "第二畫面中斷提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("只有第二畫面意外停止時才提醒");
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 56, open, f);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, ALERT_CHANNEL_ID)
            : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar：第二畫面已停止")
            .setContentText("鎖屏或系統停止了目前的第二 Display，這段時間無法即時監控")
            .setStyle(new Notification.BigTextStyle().bigText(
                "鎖屏或系統停止了目前的 MediaProjection 第二 Display。解鎖後需重新啟動第二畫面；真正的息屏持續模式需要 Shizuku/shell Display。\n" + (reason == null ? "" : reason)
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true);
        nm.notify(ALERT_ID, b.build());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            VirtualDisplayEngine.release();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultData == null) {
            VirtualDisplayEngine.setExternalStatus("螢幕分享授權資料遺失");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                VirtualDisplayEngine.setExternalStatus("MediaProjectionManager unavailable");
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                VirtualDisplayEngine.setExternalStatus("Android 沒有提供 MediaProjection");
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }

            VirtualDisplayEngine.Result result = VirtualDisplayEngine.createWithProjection(this, projection);
            if (result.success) {
                Prefs prefs = new Prefs(this);
                prefs.setVirtualDisplayRunning(result.displayId, "第二畫面運作中 · Display " + result.displayId);
                prefs.setGlobalStatus("第二畫面持續監控中 · Display " + result.displayId);

                // MediaProjection FGS already keeps this process alive. Do not keep a second
                // foreground service notification just for monitoring state.
                MonitorForegroundService.stop(this);
                updateNotification();
            }
        } catch (Throwable t) {
            VirtualDisplayEngine.setExternalStatus(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (VirtualDisplayEngine.displayId() >= 0) VirtualDisplayEngine.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "第二畫面監控",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("維持 LINE Radar 第二畫面，只保留一張必要的常駐狀態通知");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ExperimentalLabActivity.class);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 55, open, f);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        int id = VirtualDisplayEngine.displayId();
        String text = id >= 0
            ? "Display " + id + " 運作中 · 主畫面可正常使用其他 App"
            : "正在建立第二畫面";
        return b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 背景持續監控")
            .setContentText(text)
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

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
