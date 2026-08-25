package com.example.linereadradar;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "radar_prefs";
    private static final String K_TARGET = "target";
    private static final String K_ENABLED = "enabled";
    private static final String K_ARMED = "armed";
    private static final String K_BASE_COUNT = "base_count";
    private static final String K_BASE_MAX_Y = "base_max_y";
    private static final String K_ARMED_AT = "armed_at";
    private static final String K_HISTORY = "history";

    private final SharedPreferences p;

    Prefs(Context context) {
        p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String target() { return p.getString(K_TARGET, "").trim(); }
    boolean enabled() { return p.getBoolean(K_ENABLED, false); }
    boolean armed() { return p.getBoolean(K_ARMED, false); }
    int baseCount() { return p.getInt(K_BASE_COUNT, 0); }
    int baseMaxY() { return p.getInt(K_BASE_MAX_Y, -1); }
    long armedAt() { return p.getLong(K_ARMED_AT, 0L); }
    String history() { return p.getString(K_HISTORY, ""); }

    void start(String target) {
        p.edit()
            .putString(K_TARGET, target.trim())
            .putBoolean(K_ENABLED, true)
            .putBoolean(K_ARMED, false)
            .remove(K_BASE_COUNT)
            .remove(K_BASE_MAX_Y)
            .remove(K_ARMED_AT)
            .apply();
    }

    void stop() {
        p.edit().putBoolean(K_ENABLED, false).putBoolean(K_ARMED, false).apply();
    }

    void arm(int count, int maxY, long now) {
        p.edit()
            .putBoolean(K_ARMED, true)
            .putInt(K_BASE_COUNT, count)
            .putInt(K_BASE_MAX_Y, maxY)
            .putLong(K_ARMED_AT, now)
            .apply();
    }

    void finish() {
        p.edit().putBoolean(K_ENABLED, false).putBoolean(K_ARMED, false).apply();
    }

    void appendHistory(String line) {
        String old = history();
        String combined = line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = combined.split("\\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < lines.length && i < 50; i++) {
            if (i > 0) trimmed.append('\n');
            trimmed.append(lines[i]);
        }
        p.edit().putString(K_HISTORY, trimmed.toString()).apply();
    }
}
