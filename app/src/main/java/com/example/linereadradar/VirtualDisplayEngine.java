package com.example.linereadradar;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

final class VirtualDisplayEngine {
    interface ResultCallback {
        void onResult(Result result);
    }

    private static ImageReader imageReader;
    private static VirtualDisplay virtualDisplay;
    private static HandlerThread drainThread;
    private static Handler drainHandler;
    private static Context appContext;
    private static volatile String status = "尚未建立第二畫面";

    static synchronized Result create(Context context) {
        releaseInternal(false);
        try {
            appContext = context.getApplicationContext();
            int width = 720;
            int height = 1280;
            int density = Math.max(240, context.getResources().getDisplayMetrics().densityDpi);

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

            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return failAndRelease("DisplayManager unavailable");

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

            virtualDisplay = dm.createVirtualDisplay(
                "LINE-Radar-Shizuku",
                width,
                height,
                density,
                imageReader.getSurface(),
                flags
            );

            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                return failAndRelease("Android 建立 Virtual Display 失敗");
            }

            int id = virtualDisplay.getDisplay().getDisplayId();
            status = "第二畫面已建立 · Display " + id + " · 等待 LINE";
            new Prefs(context).setVirtualDisplayRunning(id, status);
            return Result.ok(id, status);
        } catch (Throwable t) {
            return failAndRelease(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    static void launchLine(Context context, ResultCallback callback) {
        final int id = displayId();
        if (id < 0) {
            post(callback, Result.fail("請先建立第二畫面"));
            return;
        }
        if (!ShizukuBridge.binderReady()) {
            post(callback, Result.fail("Shizuku 尚未啟動"));
            return;
        }
        if (!ShizukuBridge.permissionGranted()) {
            post(callback, Result.fail("LINE Radar 尚未取得 Shizuku 授權"));
            return;
        }

        final Context app = context.getApplicationContext();
        ShizukuBridge.launchLineOnDisplay(app, id, shell -> {
            if (!shell.success) {
                status = "第二畫面存在，但 Shizuku 無法把 LINE 放進 Display " + id + "：" + shell.message;
                new Prefs(app).setVirtualDisplayRunning(id, status);
                if (callback != null) callback.onResult(Result.fail(status));
                return;
            }

            status = "已用 Shizuku 要求 LINE 啟動到 Display " + id + " · 等待 Accessibility 驗證";
            new Prefs(app).setVirtualDisplayRunning(id, status);
            if (callback != null) callback.onResult(Result.ok(id, status));
        });
    }

    private static void post(ResultCallback callback, Result result) {
        if (callback == null) return;
        new Handler(android.os.Looper.getMainLooper()).post(() -> callback.onResult(result));
    }

    static synchronized int displayId() {
        return virtualDisplay == null || virtualDisplay.getDisplay() == null
            ? -1 : virtualDisplay.getDisplay().getDisplayId();
    }

    static synchronized boolean alive() {
        return displayId() >= 0;
    }

    static String status() { return status; }

    static void setExternalStatus(String value) {
        status = value == null ? "未知狀態" : value;
        Context ctx = appContext;
        if (ctx != null) {
            if (displayId() >= 0) new Prefs(ctx).setVirtualDisplayRunning(displayId(), status);
            else new Prefs(ctx).setVirtualDisplayStopped(status);
        }
    }

    static synchronized void release() {
        releaseInternal(true);
    }

    private static synchronized void releaseInternal(boolean userInitiated) {
        releaseDisplayResources();
        status = userInitiated ? "第二畫面已手動關閉" : "第二畫面準備重建";
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

    private static Result failAndRelease(String message) {
        status = message;
        releaseDisplayResources();
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
            this.message = message == null ? "" : message;
        }

        static Result ok(int id, String message) { return new Result(true, id, message); }
        static Result fail(String message) { return new Result(false, -1, message); }
    }

    private VirtualDisplayEngine() {}
}
