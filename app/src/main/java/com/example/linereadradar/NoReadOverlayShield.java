package com.example.linereadradar;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Experimental no-read surface that does not start another Activity.
 *
 * v0.6.16 proved the translucent Activity shield gets focus but removes LINE from the
 * Accessibility window tree on Samsung/One UI. This prototype instead asks the already-enabled
 * LINE Radar AccessibilityService to add TYPE_ACCESSIBILITY_OVERLAY on the secondary display.
 * No SYSTEM_ALERT_WINDOW permission is requested.
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
            if (shieldView == null || shieldDisplayId <= 0) return;
            recordState("accessibility-overlay-heartbeat");
            MAIN.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static boolean show(Context context, int displayId) {
        if (displayId <= 0) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> show(context, displayId));
            return true;
        }

        AccessibilityService service = HealthDiagnostics.activeAccessibilityService();
        Context healthContext = service != null ? service : context;
        if (healthContext == null) return false;
        health = new HealthDiagnostics(healthContext);

        if (service == null) {
            health.markShieldState(displayId, false, false, false, false,
                "accessibility-service-unavailable", System.currentTimeMillis());
            return false;
        }

        if (isAliveOnDisplay(displayId)) {
            try { shieldView.requestFocus(); } catch (Throwable ignored) {}
            recordState("accessibility-overlay-already-attached");
            return true;
        }

        closeInternal("accessibility-overlay-replace");

        try {
            DisplayManager dm = (DisplayManager) service.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(displayId);
            if (display == null) {
                health.markShieldState(displayId, false, false, false, false,
                    "accessibility-overlay-display-missing", System.currentTimeMillis());
                return false;
            }

            Context displayContext = service.createDisplayContext(display);
            Context windowContext = displayContext;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    null
                );
            }

            WindowManager wm = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                health.markShieldState(displayId, false, false, false, false,
                    "accessibility-overlay-window-manager-missing", System.currentTimeMillis());
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
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("LINE Radar Accessibility No-Read Shield");

            windowManager = wm;
            shieldView = view;
            shieldDisplayId = displayId;
            windowFocused = false;
            wm.addView(view, lp);
            view.requestFocus();

            MAIN.removeCallbacks(heartbeat);
            recordState("accessibility-overlay-attached");
            MAIN.postDelayed(heartbeat, HEARTBEAT_MS);
            return true;
        } catch (Throwable t) {
            String detail = "accessibility-overlay-add-failed:" + t.getClass().getSimpleName()
                + ":" + safe(t.getMessage());
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
        if (Looper.myLooper() == Looper.getMainLooper()) closeInternal("accessibility-overlay-close");
        else MAIN.post(() -> closeInternal("accessibility-overlay-close"));
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
        recordState(focused
            ? "accessibility-overlay-focus=true"
            : "accessibility-overlay-focus=false");
    }

    private static void recordState(String event) {
        HealthDiagnostics h = health;
        int displayId = shieldDisplayId;
        if (h == null || displayId <= 0) return;
        // The legacy "resumed" field is reused as "overlay attached" so Radar Lab remains
        // backwards-compatible while the Activity prototype is retired.
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

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
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
