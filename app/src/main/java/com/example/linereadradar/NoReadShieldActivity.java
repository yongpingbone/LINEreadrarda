package com.example.linereadradar;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * Retained only as the failed v0.6.16 Activity-shield experiment.
 *
 * The live v0.6.17 path uses NoReadOverlayShield. Static status helpers delegate to the overlay
 * first so existing Radar Lab code can keep rendering diagnostics without reviving this Activity.
 */
public class NoReadShieldActivity extends Activity {
    private static final long HEARTBEAT_MS = 2000L;
    private static volatile WeakReference<NoReadShieldActivity> current = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HealthDiagnostics health;
    private boolean resumed;
    private boolean windowFocused;
    private boolean topResumed;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            recordState("legacy-activity-heartbeat");
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static boolean isAliveOnDisplay(int displayId) {
        if (NoReadOverlayShield.isAliveOnDisplay(displayId)) return true;
        NoReadShieldActivity activity = current.get();
        return activity != null && activity.isAliveOnDisplayInternal(displayId);
    }

    static boolean isProtectingOnDisplay(int displayId) {
        if (NoReadOverlayShield.isProtectingOnDisplay(displayId)) return true;
        NoReadShieldActivity activity = current.get();
        return activity != null
            && activity.isAliveOnDisplayInternal(displayId)
            && activity.resumed
            && activity.windowFocused;
    }

    static boolean isActiveOnDisplay(int displayId) {
        return isProtectingOnDisplay(displayId);
    }

    static void closeShield() {
        NoReadOverlayShield.close();
        NoReadShieldActivity activity = current.get();
        if (activity == null) return;
        try {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) activity.finish();
            });
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        health = new HealthDiagnostics(this);
        current = new WeakReference<>(this);

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnTouchListener((v, event) -> true);
        setContentView(root);

        recordState("legacy-activity-onCreate");
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        resumed = true;
        recordState("legacy-activity-onResume");
    }

    @Override
    protected void onPause() {
        resumed = false;
        recordState("legacy-activity-onPause");
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        recordState(hasFocus ? "legacy-activity-focus=true" : "legacy-activity-focus=false");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            super.onTopResumedActivityChanged(isTopResumedActivity);
            topResumed = isTopResumedActivity;
            recordState(isTopResumedActivity
                ? "legacy-activity-topResumed=true"
                : "legacy-activity-topResumed=false");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(heartbeat);
        resumed = false;
        windowFocused = false;
        topResumed = false;
        recordState("legacy-activity-onDestroy");
        NoReadShieldActivity activity = current.get();
        if (activity == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    private boolean isAliveOnDisplayInternal(int displayId) {
        if (displayId <= 0 || isFinishing() || isDestroyed()) return false;
        try {
            return getDisplay() != null && getDisplay().getDisplayId() == displayId;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int currentDisplayId() {
        try {
            return getDisplay() == null ? -1 : getDisplay().getDisplayId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void recordState(String event) {
        if (health == null) return;
        health.markShieldState(
            currentDisplayId(),
            !isFinishing() && !isDestroyed(),
            resumed,
            windowFocused,
            topResumed,
            event,
            System.currentTimeMillis()
        );
    }
}
