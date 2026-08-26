package com.example.linereadradar;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

final class VirtualDisplayEngine {
    private static ImageReader imageReader;
    private static VirtualDisplay virtualDisplay;
    private static MediaProjection mediaProjection;
    private static HandlerThread drainThread;
    private static Handler drainHandler;
    private static Context appContext;
    private static boolean intentionalRelease = false;
    private static volatile String status = "尚未建立第二畫面";

    static synchronized Result createWithProjection(Context context, MediaProjection projection) {
        releaseInternal(false);
        if (projection == null) return failAndStore("沒有 MediaProjection 授權");
        try {
            appContext = context.getApplicationContext();
            intentionalRelease = false;
            int width = 720;
            int height = 1280;
            int density = Math.max(240, context.getResources().getDisplayMetrics().densityDpi);

            mediaProjection = projection;
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Context ctx;
                    boolean expected;
                    synchronized (VirtualDisplayEngine.class) {
                        expected = intentionalRelease;
                        ctx = appContext;
                        releaseDisplayResources();
                        mediaProjection = null;
                        if (expected) {
                            status = "第二畫面已關閉";
                        } else {
                            status = "第二畫面已被 Android 停止（鎖屏會終止目前的 MediaProjection）";
                        }
                    }
                    if (ctx != null) {
                        Prefs prefs = new Prefs(ctx);
                        prefs.setVirtualDisplayStopped(status);
                        if (!expected) ProjectionForegroundService.notifyProjectionEnded(ctx, status);
                    }
                }
            }, new Handler(Looper.getMainLooper()));

            drainThread = new HandlerThread("RadarDisplayDrain");
            drainThread.start();
            drainHandler = new Handler(drainThread.getLooper());
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try { image = reader.acquireLatestImage(); }
                catch (Throwable ignored) {}
                finally { if (image != null) image.close(); }
            }, drainHandler);

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "LINE-Radar-NoRead",
                width,
                height,
                density,
                flags,
                imageReader.getSurface(),
                null,
                null
            );

            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                return failAndRelease("MediaProjection 建立 Virtual Display 失敗");
            }
            int id = virtualDisplay.getDisplay().getDisplayId();
            status = "第二畫面已建立 · Display " + id;
            new Prefs(context).setVirtualDisplayRunning(id, status);
            return Result.ok(id, status);
        } catch (Throwable t) {
            return failAndRelease(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    static synchronized Result launchLine(Activity activity) {
        if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
            return Result.fail("請先取得螢幕分享授權並建立第二畫面");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Result.fail("Android 8.0 以下不支援 setLaunchDisplayId");
        }
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage("jp.naver.line.android");
        if (launch == null) return Result.fail("找不到 LINE App");
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            activity.startActivity(launch, options.toBundle());
            status = "第二畫面已建立，LINE 啟動要求已送出 · Display " + displayId;
            new Prefs(activity).setVirtualDisplayRunning(displayId, status);
            return Result.ok(displayId, status);
        } catch (SecurityException e) {
            status = "第二畫面已建立，但 Android 不允許 LINE 在這個 Display 啟動：" + safe(e.getMessage());
            return Result.fail(status);
        } catch (Throwable t) {
            status = t.getClass().getSimpleName() + ": " + safe(t.getMessage());
            return Result.fail(status);
        }
    }

    static synchronized int displayId() {
        return virtualDisplay == null || virtualDisplay.getDisplay() == null
            ? -1 : virtualDisplay.getDisplay().getDisplayId();
    }

    static String status() { return status; }

    static void setExternalStatus(String value) {
        status = value == null ? "未知狀態" : value;
        Context ctx = appContext;
        if (ctx != null && displayId() < 0) new Prefs(ctx).setVirtualDisplayStopped(status);
    }

    static synchronized void release() {
        releaseInternal(true);
    }

    private static synchronized void releaseInternal(boolean userInitiated) {
        intentionalRelease = userInitiated;
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        releaseDisplayResources();
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
        }
        status = userInitiated ? "第二畫面已手動關閉" : "第二畫面已重建";
        if (appContext != null) new Prefs(appContext).setVirtualDisplayStopped(status);
    }

    private static void releaseDisplayResources() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
        if (drainThread != null) {
            try { drainThread.quitSafely(); } catch (Throwable ignored) {}
            drainThread = null;
            drainHandler = null;
        }
    }

    private static Result failAndStore(String message) {
        status = message;
        if (appContext != null) new Prefs(appContext).setVirtualDisplayStopped(message);
        return Result.fail(message);
    }

    private static Result failAndRelease(String message) {
        status = message;
        intentionalRelease = true;
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        releaseDisplayResources();
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
        }
        if (appContext != null) new Prefs(appContext).setVirtualDisplayStopped(message);
        return Result.fail(message);
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }

    static final class Result {
        final boolean success;
        final int displayId;
        final String message;

        private Result(boolean success, int displayId, String message) {
            this.success = success;
            this.displayId = displayId;
            this.message = message;
        }

        static Result ok(int id, String message) { return new Result(true, id, message); }
        static Result fail(String message) { return new Result(false, -1, message); }
    }

    private VirtualDisplayEngine() {}
}
