package com.example.linereadradar;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Build;

final class VirtualDisplayEngine {
    private static ImageReader imageReader;
    private static VirtualDisplay virtualDisplay;

    static synchronized Result create(Context context) {
        release();
        try {
            int width = 720;
            int height = 1280;
            int density = Math.max(240, context.getResources().getDisplayMetrics().densityDpi);
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return Result.fail("DisplayManager unavailable");

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            virtualDisplay = dm.createVirtualDisplay(
                "LINE-Radar-NoRead",
                width,
                height,
                density,
                imageReader.getSurface(),
                flags
            );
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                release();
                return Result.fail("createVirtualDisplay returned null");
            }
            return Result.ok(virtualDisplay.getDisplay().getDisplayId(),
                "Virtual Display ready: " + virtualDisplay.getDisplay().getDisplayId());
        } catch (Throwable t) {
            release();
            return Result.fail(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    static synchronized Result launchLine(Activity activity) {
        if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
            return Result.fail("請先建立 Virtual Display");
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
            return Result.ok(displayId, "已要求 LINE 啟動到 Display " + displayId);
        } catch (SecurityException e) {
            return Result.fail("Android 不允許目前這個 Virtual Display 啟動 LINE。已改用 PUBLIC Display，但仍需實機支援：" + safe(e.getMessage()));
        } catch (Throwable t) {
            return Result.fail(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    static synchronized int displayId() {
        return virtualDisplay == null || virtualDisplay.getDisplay() == null
            ? -1 : virtualDisplay.getDisplay().getDisplayId();
    }

    static synchronized boolean alive() {
        return displayId() >= 0;
    }

    static synchronized void release() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
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
