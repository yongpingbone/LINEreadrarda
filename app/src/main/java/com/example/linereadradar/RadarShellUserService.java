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
    private static final String RADAR_PACKAGE = "com.example.linereadradar";
    private static final String RADAR_SHIELD_COMPONENT = RADAR_PACKAGE + "/.NoReadShieldActivity";
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final long INPUT_PROCESS_TIMEOUT_MS = 2200L;
    private static final long AM_PROCESS_TIMEOUT_MS = 3000L;

    // Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK.
    // Keep the Radar-owned LINE task separate from the user's normal primary-display LINE task.
    private static final String ISOLATED_TASK_FLAGS = "0x18000000";

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
        return startActivityOnDisplayInternal(packageName, componentName, displayId, false);
    }

    @Override
    public String startIsolatedActivityOnDisplay(String packageName, String componentName, int displayId) {
        if (!LINE_PACKAGE.equals(packageName)) return "ERR:2\nisolated launch is LINE-only";
        return startActivityOnDisplayInternal(packageName, componentName, displayId, true);
    }

    private String startActivityOnDisplayInternal(String packageName, String componentName,
                                                  int displayId, boolean isolatedTask) {
        boolean allowedLine = LINE_PACKAGE.equals(packageName)
            && componentName != null
            && componentName.startsWith(packageName + "/");
        boolean allowedShield = RADAR_PACKAGE.equals(packageName)
            && RADAR_SHIELD_COMPONENT.equals(componentName);
        if (!allowedLine && !allowedShield) return "ERR:2\npackage/component not allowed";
        if (isolatedTask && !allowedLine) return "ERR:2\nisolated task only allowed for LINE";

        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;

        Process process = null;
        try {
            ProcessBuilder builder;
            if (isolatedTask) {
                builder = new ProcessBuilder(
                    "am", "start",
                    "--user", "0",
                    "--display", String.valueOf(displayId),
                    "--activity-exclude-from-recents",
                    "--activity-no-animation",
                    "-f", ISOLATED_TASK_FLAGS,
                    "-n", componentName
                );
            } else {
                builder = new ProcessBuilder(
                    "am", "start",
                    "--user", "0",
                    "--display", String.valueOf(displayId),
                    "--activity-exclude-from-recents",
                    "--activity-no-animation",
                    "-n", componentName
                );
            }
            process = builder.redirectErrorStream(true).start();

            if (!process.waitFor(AM_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                return "ERR:8\nam start timeout after " + AM_PROCESS_TIMEOUT_MS + "ms";
            }

            StringBuilder out = readOutput(process);
            int code = process.exitValue();
            if (code == 0) {
                return "OK\n" + (isolatedTask ? "isolated-task launch\n" : "") + out;
            }
            return "ERR:" + code + "\n" + out;
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        } finally {
            terminateProcess(process);
        }
    }

    @Override
    public String getAppTaskTopology(String packageName, int preferredDisplayId) {
        if (!LINE_PACKAGE.equals(packageName)) return "ERR:2\npackage not allowed";
        try {
            Context shellContext = shellContext();
            ActivityManager am = (ActivityManager) shellContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "ERR:3\nActivityManager unavailable";
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            if (tasks == null) return "ERR:3\nno running task list";

            int total = 0;
            int onPreferred = 0;
            int onPrimary = 0;
            int unknownDisplay = 0;
            StringBuilder detail = new StringBuilder();
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null || task.id <= 0) continue;
                ComponentName top = task.topActivity;
                ComponentName base = task.baseActivity;
                boolean matches = (top != null && packageName.equals(top.getPackageName()))
                    || (base != null && packageName.equals(base.getPackageName()));
                if (!matches) continue;
                total++;
                int displayId = resolveTaskDisplayId(task);
                if (displayId == preferredDisplayId) onPreferred++;
                else if (displayId == 0) onPrimary++;
                else if (displayId < 0) unknownDisplay++;
                if (detail.length() > 0) detail.append(" | ");
                detail.append("task=").append(task.id)
                    .append(" display=").append(displayId)
                    .append(" top=").append(top == null ? "?" : top.getClassName());
            }
            return "OK\ntotal=" + total
                + " preferred=" + onPreferred
                + " primary=" + onPrimary
                + " unknown=" + unknownDisplay
                + (detail.length() == 0 ? "" : "\n" + detail);
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
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

                try {
                    am.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_WITH_HOME);
                    return "OK\nLINE task " + task.id + " moved to front on Display " + displayId;
                } catch (Throwable t) {
                    return "ERR:4\nmoveTaskToFront failed: " + t.getClass().getSimpleName()
                        + ": " + safe(t.getMessage());
                }
            }

            if (sawLineTaskWithoutDisplayId) {
                return "ERR:6\nLINE task found but task display id is unavailable; refusing unsafe pulse";
            }
            return "ERR:5\nno LINE task found on Display " + displayId;
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        }
    }

    @Override
    public String inputTapOnDisplay(int displayId, int x, int y) {
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;
        return runInput(new String[]{
            "input", "-d", String.valueOf(displayId), "tap",
            String.valueOf(Math.max(0, x)), String.valueOf(Math.max(0, y))
        });
    }

    @Override
    public String inputSwipeOnDisplay(int displayId, int startX, int startY,
                                      int endX, int endY, int durationMs) {
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;
        int duration = Math.max(80, Math.min(1200, durationMs));
        return runInput(new String[]{
            "input", "-d", String.valueOf(displayId), "swipe",
            String.valueOf(Math.max(0, startX)), String.valueOf(Math.max(0, startY)),
            String.valueOf(Math.max(0, endX)), String.valueOf(Math.max(0, endY)),
            String.valueOf(duration)
        });
    }

    @Override
    public String inputBackOnDisplay(int displayId) {
        String displayError = validateDisplay(displayId);
        if (displayError != null) return displayError;
        return runInput(new String[]{
            "input", "-d", String.valueOf(displayId), "keyevent", "4"
        });
    }

    private String runInput(String[] command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(INPUT_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                return "ERR:8\ninput timeout after " + INPUT_PROCESS_TIMEOUT_MS + "ms";
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

    private String validateDisplay(int displayId) {
        synchronized (displayLock) {
            int current = currentDisplayIdLocked();
            if (displayId <= 0 || current <= 0 || current != displayId) {
                return "ERR:7\nDisplay mismatch requested=" + displayId + " current=" + current;
            }
        }
        return null;
    }

    private int resolveTaskDisplayId(ActivityManager.RunningTaskInfo task) {
        if (task == null) return -1;
        try {
            Field f = ActivityManager.RunningTaskInfo.class.getField("displayId");
            return f.getInt(task);
        } catch (Throwable ignored) {}
        try {
            Method m = ActivityManager.RunningTaskInfo.class.getMethod("getDisplayId");
            Object value = m.invoke(task);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {}
        return -1;
    }

    private Context shellContext() throws Exception {
        if (context == null) throw new IllegalStateException("UserService Context unavailable");
        try {
            Method createPackageContextAsUser = Context.class.getMethod(
                "createPackageContextAsUser", String.class, int.class, android.os.UserHandle.class);
            Object result = createPackageContextAsUser.invoke(
                context,
                SHELL_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
                android.os.Process.myUserHandle()
            );
            if (result instanceof Context) return (Context) result;
        } catch (Throwable ignored) {}
        return context;
    }

    private int currentDisplayIdLocked() {
        try {
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) return -1;
            return virtualDisplay.getDisplay().getDisplayId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void releaseDisplayInternal() {
        synchronized (displayLock) {
            releaseDisplayResourcesLocked();
            displayStatus = "Shizuku 第二畫面已釋放";
        }
    }

    private void releaseDisplayResourcesLocked() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        imageReader = null;
        try { if (drainThread != null) drainThread.quitSafely(); } catch (Throwable ignored) {}
        drainThread = null;
        drainHandler = null;
    }

    private static StringBuilder readOutput(Process process) {
        StringBuilder out = new StringBuilder();
        if (process == null) return out;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void terminateProcess(Process process) {
        if (process == null) return;
        try { process.destroy(); } catch (Throwable ignored) {}
        try {
            if (process.isAlive()) process.destroyForcibly();
        } catch (Throwable ignored) {}
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
