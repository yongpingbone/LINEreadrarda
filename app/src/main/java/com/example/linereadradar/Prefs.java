package com.example.linereadradar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class Prefs {
    static final int MAX_SLOTS = 5;

    private static final String FILE = "radar_prefs";
    private static final String K_MIGRATED = "v04_migrated";
    private static final String K_GLOBAL_ENABLED = "global_enabled";
    private static final String K_PAUSED = "paused";
    private static final String K_BACKGROUND_POLLING = "background_polling_enabled";
    private static final String K_HISTORY = "history_v04";
    private static final String K_POLL_INDEX = "poll_index";
    private static final String K_LAST_POLL_AT = "last_poll_at";
    private static final String K_POLL_INTERVAL = "poll_interval_ms";
    private static final String K_STATUS = "global_status";

    private final SharedPreferences p;

    Prefs(Context context) {
        p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrateLegacyIfNeeded();
    }

    static final class Slot {
        final int index;
        final String name;
        final boolean enabled;
        final boolean readEnabled;
        final boolean messageEnabled;
        final boolean notifyEnabled;
        final boolean vibrateEnabled;
        final boolean armed;
        final int baseCount;
        final int baseMaxY;
        final long armedAt;
        final String incomingSignature;
        final long lastCheckedAt;
        final long lastUnreadSeenAt;
        final String status;

        Slot(int index, String name, boolean enabled, boolean readEnabled,
             boolean messageEnabled, boolean notifyEnabled, boolean vibrateEnabled,
             boolean armed, int baseCount, int baseMaxY, long armedAt,
             String incomingSignature, long lastCheckedAt, long lastUnreadSeenAt,
             String status) {
            this.index = index;
            this.name = name;
            this.enabled = enabled;
            this.readEnabled = readEnabled;
            this.messageEnabled = messageEnabled;
            this.notifyEnabled = notifyEnabled;
            this.vibrateEnabled = vibrateEnabled;
            this.armed = armed;
            this.baseCount = baseCount;
            this.baseMaxY = baseMaxY;
            this.armedAt = armedAt;
            this.incomingSignature = incomingSignature;
            this.lastCheckedAt = lastCheckedAt;
            this.lastUnreadSeenAt = lastUnreadSeenAt;
            this.status = status;
        }

        boolean active() {
            return !name.isEmpty() && enabled && (readEnabled || messageEnabled);
        }
    }

    private String k(int index, String suffix) { return "slot_" + index + "_" + suffix; }

    Slot slot(int index) {
        if (index < 0 || index >= MAX_SLOTS) return emptySlot(index);
        return new Slot(
            index,
            p.getString(k(index, "name"), "").trim(),
            p.getBoolean(k(index, "enabled"), true),
            p.getBoolean(k(index, "read"), true),
            p.getBoolean(k(index, "messages"), true),
            p.getBoolean(k(index, "notify"), true),
            p.getBoolean(k(index, "vibrate"), false),
            p.getBoolean(k(index, "armed"), false),
            p.getInt(k(index, "base_count"), 0),
            p.getInt(k(index, "base_max_y"), -1),
            p.getLong(k(index, "armed_at"), 0L),
            p.getString(k(index, "incoming_sig"), ""),
            p.getLong(k(index, "last_checked"), 0L),
            p.getLong(k(index, "last_unread"), 0L),
            p.getString(k(index, "status"), "等待建立基準")
        );
    }

    private Slot emptySlot(int index) {
        return new Slot(index, "", true, true, true, true, false,
            false, 0, -1, 0L, "", 0L, 0L, "等待建立基準");
    }

    List<Slot> slots() {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < MAX_SLOTS; i++) {
            Slot s = slot(i);
            if (!s.name.isEmpty()) out.add(s);
        }
        return out;
    }

    int count() {
        int count = 0;
        for (int i = 0; i < MAX_SLOTS; i++) if (!slot(i).name.isEmpty()) count++;
        return count;
    }

    int activeCount() {
        int count = 0;
        for (int i = 0; i < MAX_SLOTS; i++) if (slot(i).active()) count++;
        return count;
    }

    int addTarget(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return -1;
        for (int i = 0; i < MAX_SLOTS; i++) if (slot(i).name.equalsIgnoreCase(value)) return i;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slot(i).name.isEmpty()) {
                p.edit()
                    .putString(k(i, "name"), value)
                    .putBoolean(k(i, "enabled"), true)
                    .putBoolean(k(i, "read"), true)
                    .putBoolean(k(i, "messages"), true)
                    .putBoolean(k(i, "notify"), true)
                    .putBoolean(k(i, "vibrate"), false)
                    .putBoolean(k(i, "armed"), false)
                    .putString(k(i, "incoming_sig"), "")
                    .putString(k(i, "status"), "等待建立基準")
                    .apply();
                return i;
            }
        }
        return -1;
    }

    void renameTarget(int index, String name) {
        if (index < 0 || index >= MAX_SLOTS) return;
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return;
        p.edit()
            .putString(k(index, "name"), value)
            .putBoolean(k(index, "armed"), false)
            .putString(k(index, "incoming_sig"), "")
            .putString(k(index, "status"), "名稱已更新，等待重新建立基準")
            .apply();
    }

    void deleteTarget(int index) {
        if (index < 0 || index >= MAX_SLOTS) return;
        SharedPreferences.Editor e = p.edit();
        String[] suffixes = new String[]{
            "name", "enabled", "read", "messages", "notify", "vibrate",
            "armed", "base_count", "base_max_y", "armed_at", "incoming_sig",
            "last_checked", "last_unread", "status"
        };
        for (String suffix : suffixes) e.remove(k(index, suffix));
        e.apply();
    }

    void setSlotEnabled(int index, boolean enabled) {
        p.edit().putBoolean(k(index, "enabled"), enabled)
            .putString(k(index, "status"), enabled ? "等待可讀聊天室" : "已暫停")
            .apply();
    }

    void setReadEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "read"), enabled).apply(); }
    void setMessageEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "messages"), enabled).apply(); }
    void setNotifyEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "notify"), enabled).apply(); }
    void setVibrateEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "vibrate"), enabled).apply(); }

    void armSlot(int index, int count, int maxY, String incomingSignature, long now) {
        p.edit()
            .putBoolean(k(index, "armed"), true)
            .putInt(k(index, "base_count"), count)
            .putInt(k(index, "base_max_y"), maxY)
            .putLong(k(index, "armed_at"), now)
            .putLong(k(index, "last_unread"), now)
            .putString(k(index, "incoming_sig"), incomingSignature == null ? "" : incomingSignature)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "監控中")
            .apply();
    }

    void updateReadBaseline(int index, int count, int maxY, long now) {
        p.edit()
            .putBoolean(k(index, "armed"), true)
            .putInt(k(index, "base_count"), count)
            .putInt(k(index, "base_max_y"), maxY)
            .putLong(k(index, "armed_at"), now)
            .putLong(k(index, "last_unread"), now)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "監控中")
            .apply();
    }

    void markUnreadSeen(int index, long now) {
        p.edit().putLong(k(index, "last_unread"), now)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "監控中").apply();
    }

    void updateIncomingSignature(int index, String signature, long now) {
        p.edit().putString(k(index, "incoming_sig"), signature == null ? "" : signature)
            .putLong(k(index, "last_checked"), now).apply();
    }

    void markChecked(int index, long now, String status) {
        p.edit().putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), status == null ? "已檢查" : status).apply();
    }

    boolean globalEnabled() { return p.getBoolean(K_GLOBAL_ENABLED, false); }
    boolean paused() { return p.getBoolean(K_PAUSED, false); }
    boolean backgroundPollingEnabled() { return p.getBoolean(K_BACKGROUND_POLLING, globalEnabled()); }

    void setGlobalEnabled(boolean enabled) {
        p.edit()
            .putBoolean(K_GLOBAL_ENABLED, enabled)
            .putBoolean(K_PAUSED, false)
            .putBoolean(K_BACKGROUND_POLLING, enabled)
            .apply();
    }

    void setPaused(boolean paused) { p.edit().putBoolean(K_PAUSED, paused).apply(); }
    void setBackgroundPollingEnabled(boolean enabled) { p.edit().putBoolean(K_BACKGROUND_POLLING, enabled).apply(); }

    int pollIndex() { return p.getInt(K_POLL_INDEX, 0); }
    void setPollIndex(int index) { p.edit().putInt(K_POLL_INDEX, index).apply(); }
    long lastPollAt() { return p.getLong(K_LAST_POLL_AT, 0L); }
    void setLastPollAt(long at) { p.edit().putLong(K_LAST_POLL_AT, at).apply(); }

    long pollIntervalMs() { return p.getLong(K_POLL_INTERVAL, 60000L); }
    void setPollIntervalMs(long value) {
        long safe = Math.max(30000L, Math.min(value, 300000L));
        p.edit().putLong(K_POLL_INTERVAL, safe).apply();
    }

    String globalStatus() { return p.getString(K_STATUS, "待機中"); }
    void setGlobalStatus(String status) { p.edit().putString(K_STATUS, status).apply(); }
    String history() { return p.getString(K_HISTORY, ""); }

    void appendHistory(String line) {
        String old = history();
        String combined = line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = combined.split("\\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < lines.length && i < 150; i++) {
            if (i > 0) trimmed.append('\n');
            trimmed.append(lines[i]);
        }
        p.edit().putString(K_HISTORY, trimmed.toString()).apply();
    }

    void clearHistory() { p.edit().remove(K_HISTORY).apply(); }

    int nextActiveSlot() {
        int start = Math.max(0, Math.min(pollIndex(), MAX_SLOTS - 1));
        for (int offset = 0; offset < MAX_SLOTS; offset++) {
            int index = (start + offset) % MAX_SLOTS;
            if (slot(index).active()) {
                setPollIndex((index + 1) % MAX_SLOTS);
                return index;
            }
        }
        return -1;
    }

    private void migrateLegacyIfNeeded() {
        if (p.getBoolean(K_MIGRATED, false)) return;
        String legacyTarget = p.getString("target", "").trim();
        boolean legacyEnabled = p.getBoolean("enabled", false);
        SharedPreferences.Editor e = p.edit();
        if (!legacyTarget.isEmpty() && p.getString(k(0, "name"), "").isEmpty()) {
            e.putString(k(0, "name"), legacyTarget)
                .putBoolean(k(0, "enabled"), true)
                .putBoolean(k(0, "read"), true)
                .putBoolean(k(0, "messages"), true)
                .putBoolean(k(0, "notify"), true)
                .putBoolean(k(0, "vibrate"), false)
                .putBoolean(k(0, "armed"), false)
                .putString(k(0, "status"), "已從 v0.3 匯入，等待重新建立基準");
            if (legacyEnabled) e.putBoolean(K_GLOBAL_ENABLED, true);
        }
        e.putBoolean(K_MIGRATED, true).apply();
    }
}
