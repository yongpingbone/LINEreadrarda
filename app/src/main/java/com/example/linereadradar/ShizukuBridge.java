package com.example.linereadradar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Queue;

import rikka.shizuku.Shizuku;

final class ShizukuBridge {
    static final int REQUEST_CODE = 6206;
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    interface ResultCallback {
        void onResult(Result result);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Queue<PendingCommand> PENDING = new ArrayDeque<>();
    private static IRadarShellService shellService;
    private static boolean binding;

    private static final Shizuku.UserServiceArgs USER_SERVICE_ARGS =
        new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, RadarShellUserService.class.getName()))
            .daemon(true)
            .processNameSuffix("radar_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private static final ServiceConnection USER_SERVICE_CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                shellService = IRadarShellService.Stub.asInterface(binder);
                binding = false;
            }
            drainPending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                shellService = null;
                binding = false;
            }
        }
    };

    static boolean binderReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean permissionGranted() {
        if (!binderReady()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean permissionDeniedPermanently() {
        if (!binderReady()) return false;
        try {
            return Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                && Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void requestPermission() {
        if (!binderReady()) return;
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {}
    }

    static String stateText(Context context) {
        if (!isShizukuInstalled(context)) return "未安裝 Shizuku";
        if (!binderReady()) return "Shizuku 尚未啟動";
        if (!permissionGranted()) return permissionDeniedPermanently()
            ? "Shizuku 權限已被拒絕"
            : "等待 Radar 的 Shizuku 授權";
        try {
            int uid = Shizuku.getUid();
            return uid == 2000
                ? "Shizuku 已連線 · Wireless debugging / shell"
                : "Shizuku 已連線 · UID " + uid;
        } catch (Throwable ignored) {
            return "Shizuku 已連線";
        }
    }

    static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void launchLineOnDisplay(Context context, int displayId, ResultCallback callback) {
        if (!permissionGranted()) {
            post(callback, Result.fail("Shizuku 尚未取得授權"));
            return;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage("jp.naver.line.android");
        if (launch == null || launch.getComponent() == null) {
            post(callback, Result.fail("找不到 LINE 啟動 Activity"));
            return;
        }
        String flattened = launch.getComponent().flattenToShortString();
        String command = "am start --user 0 --display " + displayId + " -n " + shellQuote(flattened);
        runAsync(command, callback);
    }

    static void runAsync(String command, ResultCallback callback) {
        if (!permissionGranted()) {
            post(callback, Result.fail("Shizuku 尚未取得授權"));
            return;
        }

        IRadarShellService service;
        synchronized (LOCK) {
            service = shellService;
            if (service == null || !service.asBinder().isBinderAlive()) {
                shellService = null;
                PENDING.add(new PendingCommand(command, callback));
                if (!binding) {
                    binding = true;
                    try {
                        Shizuku.bindUserService(USER_SERVICE_ARGS, USER_SERVICE_CONNECTION);
                    } catch (Throwable t) {
                        binding = false;
                        failPending(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
                    }
                }
                return;
            }
        }
        executeOnWorker(service, command, callback);
    }

    private static void drainPending() {
        while (true) {
            PendingCommand pending;
            IRadarShellService service;
            synchronized (LOCK) {
                service = shellService;
                pending = PENDING.poll();
            }
            if (pending == null) return;
            if (service == null || !service.asBinder().isBinderAlive()) {
                post(pending.callback, Result.fail("Shizuku UserService 連線失敗"));
                continue;
            }
            executeOnWorker(service, pending.command, pending.callback);
        }
    }

    private static void executeOnWorker(IRadarShellService service, String command, ResultCallback callback) {
        new Thread(() -> {
            try {
                String raw = service.execute(command);
                post(callback, parse(raw));
            } catch (Throwable t) {
                synchronized (LOCK) { shellService = null; }
                post(callback, Result.fail(t.getClass().getSimpleName() + ": " + safe(t.getMessage())));
            }
        }, "RadarShizukuCommand").start();
    }

    private static Result parse(String raw) {
        if (raw == null) return Result.fail("Shizuku UserService 沒有回傳結果");
        if (raw.startsWith("OK")) {
            String detail = raw.length() > 2 ? raw.substring(2).trim() : "";
            return Result.ok(detail.isEmpty() ? "shell command completed" : detail);
        }
        String detail = raw.startsWith("ERR:") ? raw.substring(4).trim() : raw.trim();
        return Result.fail(detail.isEmpty() ? "shell command failed" : detail);
    }

    private static void failPending(String message) {
        while (true) {
            PendingCommand pending;
            synchronized (LOCK) { pending = PENDING.poll(); }
            if (pending == null) return;
            post(pending.callback, Result.fail(message));
        }
    }

    private static void post(ResultCallback callback, Result result) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(result));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }

    private static final class PendingCommand {
        final String command;
        final ResultCallback callback;
        PendingCommand(String command, ResultCallback callback) {
            this.command = command;
            this.callback = callback;
        }
    }

    static final class Result {
        final boolean success;
        final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        static Result ok(String message) { return new Result(true, message); }
        static Result fail(String message) { return new Result(false, message); }
    }

    private ShizukuBridge() {}
}
