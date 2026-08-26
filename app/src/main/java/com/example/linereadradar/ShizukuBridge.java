package com.example.linereadradar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

final class ShizukuBridge {
    static final int REQUEST_CODE = 6206;
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

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

    static Result launchLineOnDisplay(Context context, int displayId) {
        if (!permissionGranted()) return Result.fail("Shizuku 尚未取得授權");
        Intent launch = context.getPackageManager().getLaunchIntentForPackage("jp.naver.line.android");
        if (launch == null || launch.getComponent() == null) return Result.fail("找不到 LINE 啟動 Activity");
        ComponentName component = launch.getComponent();
        String flattened = component.flattenToShortString();
        String command = "am start --user 0 --display " + displayId + " -n " + shellQuote(flattened);
        return run(command);
    }

    static Result run(String command) {
        if (!permissionGranted()) return Result.fail("Shizuku 尚未取得授權");
        try {
            Process process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
            String stdout = read(process.getInputStream());
            String stderr = read(process.getErrorStream());
            int code = process.waitFor();
            String detail = !stderr.trim().isEmpty() ? stderr.trim() : stdout.trim();
            if (code == 0) return Result.ok(detail.isEmpty() ? "shell command completed" : detail);
            return Result.fail("shell exit " + code + (detail.isEmpty() ? "" : " · " + detail));
        } catch (Throwable t) {
            return Result.fail(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    private static String read(java.io.InputStream stream) {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                if (out.length() > 3000) break;
            }
        } catch (Throwable ignored) {}
        return out.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
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
