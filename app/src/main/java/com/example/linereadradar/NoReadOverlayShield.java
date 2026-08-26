package com.example.linereadradar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Experimental no-read focus surface that does NOT start an Activity.
 *
 * The previous translucent Activity prototype proved on Samsung/One UI that once Radar became
 * the focused Activity, LINE disappeared from Accessibility.getWindowsOnAllDisplays(). This
 * prototype instead adds a transparent TYPE_APPLICATION_OVERLAY window on the secondary display.
 * The experiment is intentionally narrow: can a non-Activity window own window focus and consume
 * input while LINE remains resumed/rendering and still exposes its Accessibility tree?
 *
 * This is an experimental Shizuku branch implementation. The shell-side launcher temporarily
 * grants this app's SYSTEM_ALERT_WINDOW app-op for the experiment; a future Play-facing design
 * must replace that with an explicit user-facing special-access flow if this mechanism succeeds.
 */
final class NoReadOverlayShield {
    private static final long HEARTBEAT_MS = 2000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WindowManager windowManager;
    private static ShieldFrame shieldView;
    private static volatile int shieldDisplayId = -1;
    private static volatile boolean windowFocused = false;
    private static HealthDiagnostics health;

    private static final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            ShieldFrame view = shieldView;
            if (view == null || shieldDisplayId <= 0) return;
            recordState("overlay-heartbeat");
            MAIN.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static boolean show(Context context, int displayId) {
        if (context == null || displayId <= 0) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> show(context.getApplicationContext(), displayId));
            return true;
        }

        Context app = context.getApplicationContext();
        health = new HealthDiagnostics(app);

        if (isAliveOnDisplay(displayId)) {
            try {
                shieldView.requestFocus();
            } catch (Throwable ignored) {}
            recordState("overlay-already-attached");
            return true;
        }

        closeInternal("overlay-replace");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            health.markShieldState(displayId, false, false, false, false,
                "overlay-permission-missing", System.currentTimeMillis());
            return false;
        }

        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(displayId);
            if (display == null) {
                health.markShieldState(displayId, false, false, false, false,
                    "overlay-display-missing", System.currentTimeMillis());
                return false;
            }

            Context displayContext = app.createDisplayContext(display);
            Context windowContext = displayContext;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                );
            }

            WindowManager wm = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                health.markShieldState(displayId, false, false, false, false,
                    "overlay-window-manager-missing", System.currentTimeMillis());
                return false;
            }

            ShieldFrame view = new ShieldFrame(windowContext);
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setClickable(true);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("LINE Radar No-Read Overlay Shield");

            windowManager = wm;
            shieldView = view;
            shieldDisplayId = displayId;
            windowFocused = false;
            wm.addView(view, lp);
            view.requestFocus();

            MAIN.removeCallbacks(heartbeat);
            recordState("overlay-attached");
            MAIN.postDelayed(heartbeat, HEARTBEAT_MS);
            return true;
        } catch (Throwable t) {
            String detail = "overlay-add-failed:" + t.getClass().getSimpleName();
            health.markShieldState(displayId, false, false, false, false,
                detail, System.currentTimeMillis());
            closeInternal(detail);
            return false;
        }
    }

    static boolean isAliveOnDisplay(int displayId) {
        return displayId > 0 && shieldView != null && shieldDisplayId == displayId;
    }

    static boolean isProtectingOnDisplay(int displayId) {
        return isAliveOnDisplay(displayId) && windowFocused;
    }

    static void close() {
        if (Looper.myLooper() == Looper.getMainLooper()) closeInternal("overlay-close");
        else MAIN.post(() -> closeInternal("overlay-close"));
    }

    private static void closeInternal(String reason) {
        MAIN.removeCallbacks(heartbeat);
        ShieldFrame view = shieldView;
        WindowManager wm = windowManager;
        int oldDisplayId = shieldDisplayId;

        shieldView = null;
        windowManager = null;
        shieldDisplayId = -1;
        windowFocused = false;

        if (view != null && wm != null) {
            try { wm.removeViewImmediate(view); } catch (Throwable ignored) {}
        }

        if (health != null && oldDisplayId > 0) {
            health.markShieldState(oldDisplayId, false, false, false, false,
                reason, System.currentTimeMillis());
        }
    }

    private static void onWindowFocusChanged(boolean focused) {
        windowFocused = focused;
        recordState(focused ? "overlay-focus=true" : "overlay-focus=false");
    }

    private static void recordState(String event) {
        HealthDiagnostics h = health;
        int displayId = shieldDisplayId;
        if (h == null || displayId <= 0) return;
        // HealthDiagnostics still calls this field "resumed" for compatibility with the old
        // Activity prototype. For the overlay prototype, true means the surface is attached.
        h.markShieldState(
            displayId,
            shieldView != null,
            shieldView != null,
            windowFocused,
            false,
            event,
            System.currentTimeMillis()
        );
    }

    private static final class ShieldFrame extends FrameLayout {
        ShieldFrame(Context context) {
            super(context);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            NoReadOverlayShield.onWindowFocusChanged(hasWindowFocus);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return true;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return true;
        }
    }

    private NoReadOverlayShield() {}
}
