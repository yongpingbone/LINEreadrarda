package com.example.linereadradar;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RadarShellUserService extends IRadarShellService.Stub {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final long INPUT_PROCESS_TIMEOUT_MS = 2200L;
    private static final long AM_PROCESS_TIMEOUT_MS = 3000L;

    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_OWN_FOCUS = 1 << 14;

    private final Object displayLock = new Object();
    private Context context;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread drainThread;
    private Handler drainHandler;
    private volatile String displayStatus = "Shizuku 第二畫面尚未建立";
    private volatile int virtualWidth = 720;
    private volatile int virtualHeight = 1280;

    public RadarShellUserService() {}

    @Keep
    public RadarShellUserService(Context context) {
        this.context = context;
    }

    @Override
    public void destroy() {
        releaseDisplayInternal();
        System.exit(0);
    }

    @Override
    public int ensureVirtualDisplay(int width, int height, int densityDpi) {
        synchronized (displayLock) {
            int existing = currentDisplayIdLocked();
            if (existing > 0) {
                displayStatus = "Display " + existing + " · Shizuku 常駐 · Always unlocked";
                return existing;
            }

            if (context == null) {
                displayStatus = "ERR: UserService Context unavailable";
                return -1;
            }

            int safeWidth = Math.max(320, width);
            int safeHeight = Math.max(480, height);
            int safeDensity = Math.max(160, densityDpi);

            try {
                Context shellContext = shellContext();
                DisplayManager dm = (DisplayManager) shellContext.getSystemService(Context.DISPLAY_SERVICE);
                if (dm == null) {
                    displayStatus = "ERR: shell DisplayManager unavailable";
                    return -1;
                }

                drainThread = new HandlerThread("RadarShellDisplayDrain");
                drainThread.start();
                drainHandler = new Handler(drainThread.getLooper());
                imageReader = ImageReader.newInstance(
                    safeWidth, safeHeight, PixelFormat.RGBA_8888, 3);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try { image = reader.acquireLatestImage(); }
                    catch (Throwable ignored) {}
                    finally { if (image != null) image.close(); }
                }, drainHandler);

                int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | FLAG_SUPPORTS_TOUCH
                    | FLAG_TRUSTED
                    | FLAG_OWN_DISPLAY_GROUP
                    | FLAG_ALWAYS_UNLOCKED
                    | FLAG_OWN_FOCUS;

                virtualDisplay = dm.createVirtualDisplay(
                    "LINE-Radar-Shizuku-AlwaysOn",
                    safeWidth,
                    safeHeight,
                    safeDensity,
                    imageReader.getSurface(),
                    flags
                );
                virtualWidth = safeWidth;
                virtualHeight = safeHeight;

                int id = currentDisplayIdLocked();
                if (id <= 0) {
                    displayStatus = "ERR: Android returned no Shizuku Virtual Display";
                    releaseDisplayResourcesLocked();
                    return -1;
                }

                displayStatus = "Display " + id
                    + " · Shizuku shell 建立 · Trusted · Always unlocked · Own focus";
                return id;
            } catch (SecurityException se) {
                displayStatus = "ERR SecurityException: " + safe(se.getMessage());
                releaseDisplayResourcesLocked();
                return -1;
            } catch (Throwable t) {
                displayStatus = "ERR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
                releaseDisplayResourcesLocked();
                return -1;
            }
        }
    }

    @Override
    public int getVirtualDisplayId() {
        synchronized (displayLock) {
            return currentDisplayIdLocked();
        }
    }

    @Override
    public String getVirtualDisplayStatus() {
        return displayStatus;
    }

    @Override
    public void releaseVirtualDisplay() {
        releaseDisplayInternal();
    }

    @Override
    public String startActivityOnDisplay(String packageName, String componentName, int displayId) {
        if (!LINE_PACKAGE.equals(packageName)) return "ERR:2\npackage not allowed";
        if (componentName == null || !componentName.startsWith(packageName + "/")) {
            return "ERR:2\ninvalid component";
        }
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;

        Process process = null;
        try {
            process = new ProcessBuilder(
                "am", "start",
                "--user", "0",
                "--display", String.valueOf(displayId),
                "--activity-exclude-from-recents",
                "--activity-no-animation",
                "-n", componentName
            ).redirectErrorStream(true).start();

            if (!process.waitFor(AM_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                return "ERR:8\nam start timeout after " + AM_PROCESS_TIMEOUT_MS + "ms";
            }

            StringBuilder out = readOutput(process);
            int code = process.exitValue();
            if (code == 0) return "OK\n" + out;
            return "ERR:" + code + "\n" + out;
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        } finally {
            terminateProcess(process);
        }
    }

    @Override
    public String pulseAppTaskOnDisplay(String packageName, int displayId) {
        if (!LINE_PACKAGE.equals(packageName)) return "ERR:2\npackage not allowed";
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;

        try {
            Context shellContext = shellContext();
            ActivityManager am = (ActivityManager) shellContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "ERR:3\nActivityManager unavailable";

            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            if (tasks == null) return "ERR:3\nno running task list";

            boolean sawLineTaskWithoutDisplayId = false;
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null || task.id <= 0) continue;
                ComponentName top = task.topActivity;
                ComponentName base = task.baseActivity;
                boolean matches = (top != null && LINE_PACKAGE.equals(top.getPackageName()))
                    || (base != null && LINE_PACKAGE.equals(base.getPackageName()));
                if (!matches) continue;

                int taskDisplayId = resolveTaskDisplayId(task);
                if (taskDisplayId < 0) {
                    sawLineTaskWithoutDisplayId = true;
                    continue;
                }
                if (taskDisplayId != displayId) continue;

                am.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_NO_USER_ACTION);
                return "OK\ntask " + task.id + " moved to front on Display " + displayId;
            }

            if (sawLineTaskWithoutDisplayId) {
                return "ERR:6\nLINE task found but Android hid its display id";
            }
            return "ERR:4\nLINE task not found on Display " + displayId;
        } catch (SecurityException se) {
            return "ERR:5\nSecurityException: " + safe(se.getMessage());
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        }
    }

    @Override
    public String inputTapOnDisplay(int displayId, int x, int y) {
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;
        if (!validPoint(x, y)) return "ERR:2\ninvalid tap coordinates";
        return runInput("tap", displayId,
            String.valueOf(x), String.valueOf(y));
    }

    @Override
    public String inputSwipeOnDisplay(int displayId, int startX, int startY,
                                      int endX, int endY, int durationMs) {
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;
        if (!validPoint(startX, startY) || !validPoint(endX, endY)) {
            return "ERR:2\ninvalid swipe coordinates";
        }
        int duration = Math.max(80, Math.min(1200, durationMs));
        return runInput("swipe", displayId,
            String.valueOf(startX), String.valueOf(startY),
            String.valueOf(endX), String.valueOf(endY),
            String.valueOf(duration));
    }

    @Override
    public String inputBackOnDisplay(int displayId) {
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;
        return runInput("keyevent", displayId, "BACK");
    }

    private String runInput(String command, int displayId, String... args) {
        Process process = null;
        try {
            String[] cmd = new String[4 + args.length];
            cmd[0] = "input";
            cmd[1] = "-d";
            cmd[2] = String.valueOf(displayId);
            cmd[3] = command;
            System.arraycopy(args, 0, cmd, 4, args.length);
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();

            if (!process.waitFor(INPUT_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                return "ERR:8\ninput -d " + displayId + " " + command
                    + " timeout after " + INPUT_PROCESS_TIMEOUT_MS + "ms";
            }

            StringBuilder out = readOutput(process);
            int code = process.exitValue();
            if (code == 0) {
                return "OK\ninput -d " + displayId + " " + command
                    + (out.length() == 0 ? "" : "\n" + out);
            }
            return "ERR:" + code + "\n" + out;
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        } finally {
            terminateProcess(process);
        }
    }

    private void terminateProcess(Process process) {
        if (process == null) return;
        try {
            if (process.isAlive()) process.destroy();
            if (process.isAlive() && !process.waitFor(250L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(250L, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
            try { process.destroyForcibly(); } catch (Throwable ignoredAgain) {}
        }
    }

    private String validateDisplay(int displayId) {
        if (displayId <= 0) return "ERR:2\ninvalid display";
        synchronized (displayLock) {
            int currentId = currentDisplayIdLocked();
            if (currentId <= 0 || currentId != displayId) {
                return "ERR:2\nShizuku display is not alive: expected " + displayId
                    + ", current " + currentId;
            }
        }
        return null;
    }

    private boolean validPoint(int x, int y) {
        int width = Math.max(1, virtualWidth);
        int height = Math.max(1, virtualHeight);
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private StringBuilder readOutput(Process process) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                if (out.length() > 4000) break;
            }
        }
        return out;
    }

    private int resolveTaskDisplayId(ActivityManager.RunningTaskInfo task) {
        if (task == null) return -1;

        try {
            Method method = task.getClass().getMethod("getDisplayId");
            Object value = method.invoke(task);
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}

        Class<?> type = task.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("displayId");
                field.setAccessible(true);
                return field.getInt(task);
            } catch (NoSuchFieldException notHere) {
                type = type.getSuperclass();
            } catch (Throwable blocked) {
                return -1;
            }
        }
        return -1;
    }

    private Context shellContext() throws Exception {
        if (context == null) throw new IllegalStateException("UserService Context unavailable");
        return context.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
    }

    private void releaseDisplayInternal() {
        synchronized (displayLock) {
            releaseDisplayResourcesLocked();
            displayStatus = "Shizuku 第二畫面已釋放";
        }
    }

    private int currentDisplayIdLocked() {
        try {
            return virtualDisplay == null || virtualDisplay.getDisplay() == null
                ? -1 : virtualDisplay.getDisplay().getDisplayId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void releaseDisplayResourcesLocked() {
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
        virtualWidth = 720;
        virtualHeight = 1280;
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
