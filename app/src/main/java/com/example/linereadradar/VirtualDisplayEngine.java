package com.example.linereadradar;

import android.content.Context;
import android.os.Handler;

final class VirtualDisplayEngine {
    interface ResultCallback {
        void onResult(Result result);
    }

    private static Context appContext;
    private static volatile int remoteDisplayId = -1;
    private static volatile String status = "尚未建立第二畫面";

    static synchronized void attach(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        if (remoteDisplayId < 0) {
            Prefs prefs = new Prefs(appContext);
            if (prefs.virtualDisplayRunning() && prefs.virtualDisplayId() > 0) {
                remoteDisplayId = prefs.virtualDisplayId();
                status = prefs.virtualDisplayStatus();
            }
        }
    }

    static void create(Context context, ResultCallback callback) {
        attach(context);
        if (!ShizukuBridge.binderReady()) {
            post(callback, Result.fail("Shizuku 尚未啟動"));
            return;
        }
        if (!ShizukuBridge.permissionGranted()) {
            post(callback, Result.fail("LINE Radar 尚未取得 Shizuku 授權"));
            return;
        }

        int density = Math.max(240, context.getResources().getDisplayMetrics().densityDpi);
        ShizukuBridge.ensureVirtualDisplay(context, 720, 1280, density, shell -> {
            Context app = context.getApplicationContext();
            if (!shell.success || shell.displayId <= 0) {
                synchronized (VirtualDisplayEngine.class) { remoteDisplayId = -1; }
                status = shell.message.isEmpty() ? "Shizuku 建立第二畫面失敗" : shell.message;
                new Prefs(app).setVirtualDisplayStopped(status);
                if (callback != null) callback.onResult(Result.fail(status));
                return;
            }

            synchronized (VirtualDisplayEngine.class) { remoteDisplayId = shell.displayId; }
            status = "Display " + shell.displayId
                + " · Shizuku Trusted / Always unlocked · 等待 LINE";
            new Prefs(app).setVirtualDisplayRunning(shell.displayId, status);
            if (callback != null) callback.onResult(Result.ok(shell.displayId, status));
        });
    }

    static void restore(Context context, ResultCallback callback) {
        attach(context);
        if (!ShizukuBridge.binderReady() || !ShizukuBridge.permissionGranted()) {
            synchronized (VirtualDisplayEngine.class) { remoteDisplayId = -1; }
            post(callback, Result.fail("Shizuku 尚未準備完成"));
            return;
        }

        final Context app = context.getApplicationContext();
        ShizukuBridge.queryVirtualDisplay(shell -> {
            if (!shell.success || shell.displayId <= 0) {
                synchronized (VirtualDisplayEngine.class) { remoteDisplayId = -1; }
                status = shell.message.isEmpty() ? "Shizuku 第二畫面不存在" : shell.message;
                new Prefs(app).setVirtualDisplayStopped(status);
                if (callback != null) callback.onResult(Result.fail(status));
                return;
            }

            synchronized (VirtualDisplayEngine.class) { remoteDisplayId = shell.displayId; }
            status = shell.message.isEmpty()
                ? "Display " + shell.displayId + " · Shizuku 第二畫面仍存在"
                : shell.message;
            Prefs prefs = new Prefs(app);
            if (!prefs.virtualDisplayRunning() || prefs.virtualDisplayId() != shell.displayId) {
                prefs.setVirtualDisplayRunning(shell.displayId, status);
            } else {
                prefs.updateVirtualDisplayStatus(status);
            }
            if (callback != null) callback.onResult(Result.ok(shell.displayId, status));
        });
    }

    static void launchLine(Context context, ResultCallback callback) {
        attach(context);
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
                new Prefs(app).updateVirtualDisplayStatus(status);
                if (callback != null) callback.onResult(Result.fail(status));
                return;
            }

            status = "已要求 LINE 進入 Display " + id + " · 等待 Accessibility 驗證";
            new Prefs(app).updateVirtualDisplayStatus(status);
            if (callback != null) callback.onResult(Result.ok(id, status));
        });
    }

    private static void post(ResultCallback callback, Result result) {
        if (callback == null) return;
        new Handler(android.os.Looper.getMainLooper()).post(() -> callback.onResult(result));
    }

    static synchronized int displayId() {
        if (remoteDisplayId > 0) return remoteDisplayId;
        if (appContext != null) {
            Prefs prefs = new Prefs(appContext);
            if (prefs.virtualDisplayRunning() && prefs.virtualDisplayId() > 0) {
                return prefs.virtualDisplayId();
            }
        }
        return -1;
    }

    static synchronized boolean alive() {
        return displayId() >= 0;
    }

    static String status() { return status; }

    static void setExternalStatus(String value) {
        status = value == null ? "未知狀態" : value;
        Context ctx = appContext;
        if (ctx != null) {
            int id = displayId();
            if (id >= 0) new Prefs(ctx).updateVirtualDisplayStatus(status);
            else new Prefs(ctx).setVirtualDisplayStopped(status);
        }
    }

    static synchronized void release() {
        final Context ctx = appContext;
        remoteDisplayId = -1;
        status = "第二畫面已手動關閉";
        if (ctx != null) new Prefs(ctx).setVirtualDisplayStopped(status);

        if (ShizukuBridge.permissionGranted()) {
            ShizukuBridge.releaseVirtualDisplay(result -> {
                if (!result.success && ctx != null) {
                    new Prefs(ctx).setGlobalStatus("第二畫面釋放失敗 · " + result.message);
                }
            });
        }
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
