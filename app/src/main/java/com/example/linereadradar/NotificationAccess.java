package com.example.linereadradar;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

final class NotificationAccess {
    static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            String flat = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners"
            );
            if (flat == null || flat.trim().isEmpty()) return false;
            String[] values = flat.split(":");
            for (String value : values) {
                ComponentName component = ComponentName.unflattenFromString(value);
                if (component != null && context.getPackageName().equals(component.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private NotificationAccess() {}
}
