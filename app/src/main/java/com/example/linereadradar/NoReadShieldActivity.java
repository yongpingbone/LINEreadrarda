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
 * Transparent focus shield for the Shizuku secondary display.
 *
 * The activity is intentionally focusable and translucent. LINE remains rendered underneath,
 * while this Activity becomes the foreground/focused Activity on that display. This prototype is
 * only considered protecting when it is resumed AND owns window focus; merely having an Activity
 * instance alive is not enough evidence for the no-read experiment.
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
            recordState("heartbeat");
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static boolean isAliveOnDisplay(int displayId) {
        NoReadShieldActivity activity = current.get();
        return activity != null && activity.isAliveOnDisplayInternal(displayId);
    }

    static boolean isProtectingOnDisplay(int displayId) {
        NoReadShieldActivity activity = current.get();
        return activity != null
            && activity.isAliveOnDisplayInternal(displayId)
            && activity.resumed
            && activity.windowFocused;
    }

    // Kept for existing callers while the prototype migrates from "alive" to "protecting".
    static boolean isActiveOnDisplay(int displayId) {
        return isProtectingOnDisplay(displayId);
    }

    static void closeShield() {
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

        // Do not use FLAG_NOT_TOUCHABLE here. The shield must own focus and consume any accidental
        // input delivered to the hidden display instead of allowing it to reach LINE underneath.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnTouchListener((v, event) -> true);
        setContentView(root);

        recordState("onCreate");
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        resumed = true;
        recordState("onResume");
    }

    @Override
    protected void onPause() {
        resumed = false;
        recordState("onPause");
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        recordState(hasFocus ? "windowFocus=true" : "windowFocus=false");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Belt-and-suspenders guard: never pass secondary-display touches through to LINE.
        return true;
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            super.onTopResumedActivityChanged(isTopResumedActivity);
            topResumed = isTopResumedActivity;
            recordState(isTopResumedActivity ? "topResumed=true" : "topResumed=false");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(heartbeat);
        resumed = false;
        windowFocused = false;
        topResumed = false;
        recordState("onDestroy");
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
