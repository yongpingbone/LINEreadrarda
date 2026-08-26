package com.example.linereadradar;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * Transparent focus shield for the Shizuku secondary display.
 *
 * The activity is intentionally focusable but not touchable. Because the theme is translucent,
 * LINE remains rendered underneath while its Activity is no longer the resumed foreground
 * Activity on that display. This is the no-root No-Read protection path; it does not depend on
 * Xposed/LSPosed.
 */
public class NoReadShieldActivity extends Activity {
    private static volatile WeakReference<NoReadShieldActivity> current = new WeakReference<>(null);

    static boolean isActiveOnDisplay(int displayId) {
        NoReadShieldActivity activity = current.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            return activity.getDisplay() != null && activity.getDisplay().getDisplayId() == displayId;
        } catch (Throwable ignored) {
            return false;
        }
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
        current = new WeakReference<>(this);

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // Keep this window focused so the LINE Activity underneath is paused, but do not let the
        // hidden secondary display receive accidental touch input through this Activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
    }

    @Override
    protected void onDestroy() {
        NoReadShieldActivity activity = current.get();
        if (activity == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}
