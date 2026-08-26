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
    private static final String CHANNEL_ID = "line_radar_projection_v057";
    private static final int NOTIFICATION_ID = 7355;

    public static void start(Context context, int resultCode, Intent resultData) {
        Intent i = new Intent(context, ProjectionForegroundService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_RESULT_CODE, resultCode);
        i.putExtra(EXTRA_RESULT_DATA, resultData);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, ProjectionForegroundService.class);
        i.setAction(ACTION_STOP);
        try { context.startService(i); } catch (Throwable ignored) {
            context.stopService(new Intent(context, ProjectionForegroundService.class));
            VirtualDisplayEngine.release();
        }
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
                if (prefs.globalEnabled() && prefs.backgroundActiveCount() > 0 && !prefs.paused()) {
                    prefs.setGlobalStatus("🟢 第二畫面持續監控中 · Display " + result.displayId);
                    MonitorForegroundService.start(this);
                }
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
        VirtualDisplayEngine.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Virtual Display 持續監控",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("維持 LINE Radar 第二畫面，讓主畫面可正常使用其他 App");
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
        return b.setSmallIcon(R.drawable.ic_notify_chat_star)
            .setContentTitle("LINE Radar · 第二畫面運作中")
            .setContentText("LINE 可留在第二 Display，主畫面可正常使用")
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
