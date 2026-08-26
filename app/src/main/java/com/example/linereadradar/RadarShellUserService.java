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
import java.util.List;

public class RadarShellUserService extends IRadarShellService.Stub {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String SHELL_PACKAGE = "com.android.shell";

    // Hidden DisplayManager flags. Values are stable platform constants on the
    // Android versions supported by this experiment.
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
        if (displayId <= 0) return "ERR:2\ninvalid display";

        synchronized (displayLock) {
            int currentId = currentDisplayIdLocked();
            if (currentId <= 0 || currentId != displayId) {
                return "ERR:2\nShizuku display is not alive: expected " + displayId
                    + ", current " + currentId;
            }
        }

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

            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                if (out.length() > 4000) break;
            }

            int code = process.waitFor();
            if (code == 0) return "OK\n" + out;
            return "ERR:" + code + "\n" + out;
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public String pulseAppTaskOnDisplay(String packageName, int displayId) {
        if (!LINE_PACKAGE.equals(packageName)) return "ERR:2\npackage not allowed";
        if (displayId <= 0) return "ERR:2\ninvalid display";

        synchronized (displayLock) {
            int currentId = currentDisplayIdLocked();
            if (currentId <= 0 || currentId != displayId) {
                return "ERR:2\nShizuku display is not alive: expected " + displayId
                    + ", current " + currentId;
            }
        }

        try {
            Context shellContext = shellContext();
            ActivityManager am = (ActivityManager) shellContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "ERR:3\nActivityManager unavailable";

            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            if (tasks == null) return "ERR:3\nno running task list";

            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null || task.id <= 0 || task.displayId != displayId) continue;
                ComponentName top = task.topActivity;
                ComponentName base = task.baseActivity;
                boolean matches = (top != null && LINE_PACKAGE.equals(top.getPackageName()))
                    || (base != null && LINE_PACKAGE.equals(base.getPackageName()));
                if (!matches) continue;

                am.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_NO_USER_ACTION);
                return "OK\ntask " + task.id + " moved to front on Display " + displayId;
            }
            return "ERR:4\nLINE task not found on Display " + displayId;
        } catch (SecurityException se) {
            return "ERR:5\nSecurityException: " + safe(se.getMessage());
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        }
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
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
