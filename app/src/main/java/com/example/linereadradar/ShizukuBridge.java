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
    private static final String LINE_PACKAGE = "jp.naver.line.android";

    private static final int OP_LAUNCH = 1;
    private static final int OP_ENSURE_DISPLAY = 2;
    private static final int OP_QUERY_DISPLAY = 3;
    private static final int OP_RELEASE_DISPLAY = 4;

    interface ResultCallback {
        void onResult(Result result);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Queue<PendingRequest> PENDING = new ArrayDeque<>();
    private static IRadarShellService shellService;
    private static boolean binding;

    private static final Shizuku.UserServiceArgs USER_SERVICE_ARGS =
        new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, RadarShellUserService.class.getName()))
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

    static void ensureVirtualDisplay(Context context, int width, int height, int densityDpi,
                                     ResultCallback callback) {
        if (!permissionGranted()) {
            post(callback, Result.fail("Shizuku 尚未取得授權"));
            return;
        }
        submit(PendingRequest.ensureDisplay(width, height, densityDpi, callback));
    }

    static void queryVirtualDisplay(ResultCallback callback) {
        if (!permissionGranted()) {
            post(callback, Result.fail("Shizuku 尚未取得授權"));
            return;
        }
        submit(PendingRequest.queryDisplay(callback));
    }

    static void releaseVirtualDisplay(ResultCallback callback) {
        if (!permissionGranted()) {
            post(callback, Result.fail("Shizuku 尚未取得授權"));
            return;
        }
        submit(PendingRequest.releaseDisplay(callback));
    }

    static void launchLineOnDisplay(Context context, int displayId, ResultCallback callback) {
        if (!permissionGranted()) {
            post(callback, Result.fail("Shizuku 尚未取得授權"));
            return;
        }
        if (displayId <= 0) {
            post(callback, Result.fail("無效的第二 Display"));
            return;
        }

        Intent launch = context.getPackageManager().getLaunchIntentForPackage(LINE_PACKAGE);
        if (launch == null || launch.getComponent() == null) {
            post(callback, Result.fail("找不到 LINE 啟動 Activity"));
            return;
        }

        submit(PendingRequest.launch(
            LINE_PACKAGE,
            launch.getComponent().flattenToShortString(),
            displayId,
            callback
        ));
    }

    private static void submit(PendingRequest request) {
        IRadarShellService service;
        synchronized (LOCK) {
            service = shellService;
            if (service == null || !service.asBinder().isBinderAlive()) {
                shellService = null;
                PENDING.add(request);
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
        executeOnWorker(service, request);
    }

    private static void drainPending() {
        while (true) {
            PendingRequest pending;
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
            executeOnWorker(service, pending);
        }
    }

    private static void executeOnWorker(IRadarShellService service, PendingRequest request) {
        new Thread(() -> {
            try {
                Result result;
                switch (request.operation) {
                    case OP_ENSURE_DISPLAY: {
                        int id = service.ensureVirtualDisplay(
                            request.width, request.height, request.densityDpi);
                        String detail = safeStatus(service.getVirtualDisplayStatus());
                        result = id > 0
                            ? Result.ok(id, detail)
                            : Result.fail(detail.isEmpty() ? "Shizuku 建立第二 Display 失敗" : detail);
                        break;
                    }
                    case OP_QUERY_DISPLAY: {
                        int id = service.getVirtualDisplayId();
                        String detail = safeStatus(service.getVirtualDisplayStatus());
                        result = id > 0
                            ? Result.ok(id, detail)
                            : Result.fail(detail.isEmpty() ? "Shizuku 第二 Display 不存在" : detail);
                        break;
                    }
                    case OP_RELEASE_DISPLAY: {
                        service.releaseVirtualDisplay();
                        result = Result.ok(-1, "Shizuku 第二 Display 已釋放");
                        break;
                    }
                    case OP_LAUNCH:
                    default: {
                        String raw = service.startActivityOnDisplay(
                            request.packageName,
                            request.componentName,
                            request.displayId
                        );
                        result = parseLaunch(raw);
                        break;
                    }
                }
                post(request.callback, result);
            } catch (Throwable t) {
                synchronized (LOCK) { shellService = null; }
                post(request.callback,
                    Result.fail(t.getClass().getSimpleName() + ": " + safe(t.getMessage())));
            }
        }, "RadarShizukuOp").start();
    }

    private static Result parseLaunch(String raw) {
        if (raw == null) return Result.fail("Shizuku UserService 沒有回傳結果");
        if (raw.startsWith("OK")) {
            String detail = raw.length() > 2 ? raw.substring(2).trim() : "";
            return Result.ok(-1, detail.isEmpty() ? "LINE launch completed" : detail);
        }
        return Result.fail(raw.trim().isEmpty() ? "LINE launch failed" : raw.trim());
    }

    private static void failPending(String message) {
        while (true) {
            PendingRequest pending;
            synchronized (LOCK) { pending = PENDING.poll(); }
            if (pending == null) return;
            post(pending.callback, Result.fail(message));
        }
    }

    private static void post(ResultCallback callback, Result result) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(result));
    }

    private static String safeStatus(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }

    private static final class PendingRequest {
        final int operation;
        final String packageName;
        final String componentName;
        final int displayId;
        final int width;
        final int height;
        final int densityDpi;
        final ResultCallback callback;

        private PendingRequest(int operation, String packageName, String componentName,
                               int displayId, int width, int height, int densityDpi,
                               ResultCallback callback) {
            this.operation = operation;
            this.packageName = packageName;
            this.componentName = componentName;
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.callback = callback;
        }

        static PendingRequest launch(String pkg, String component, int displayId,
                                     ResultCallback callback) {
            return new PendingRequest(OP_LAUNCH, pkg, component, displayId, 0, 0, 0, callback);
        }

        static PendingRequest ensureDisplay(int width, int height, int densityDpi,
                                            ResultCallback callback) {
            return new PendingRequest(OP_ENSURE_DISPLAY, null, null, -1,
                width, height, densityDpi, callback);
        }

        static PendingRequest queryDisplay(ResultCallback callback) {
            return new PendingRequest(OP_QUERY_DISPLAY, null, null, -1, 0, 0, 0, callback);
        }

        static PendingRequest releaseDisplay(ResultCallback callback) {
            return new PendingRequest(OP_RELEASE_DISPLAY, null, null, -1, 0, 0, 0, callback);
        }
    }

    static final class Result {
        final boolean success;
        final String message;
        final int displayId;

        private Result(boolean success, String message, int displayId) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.displayId = displayId;
        }

        static Result ok(int displayId, String message) {
            return new Result(true, message, displayId);
        }

        static Result fail(String message) {
            return new Result(false, message, -1);
        }
    }

    private ShizukuBridge() {}
}
