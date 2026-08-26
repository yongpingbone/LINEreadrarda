package com.example.linereadradar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class Prefs {
    static final int MAX_SLOTS = 5;
    static final int READ_DETECTOR_SCHEMA = 2;

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
    private static final String K_VD_RUNNING = "virtual_display_running";
    private static final String K_VD_ID = "virtual_display_id";
    private static final String K_VD_STATUS = "virtual_display_status";
    private static final String K_SECONDARY_LINE_ID = "secondary_line_display_id";
    private static final String K_SECONDARY_LINE_SEEN_AT = "secondary_line_seen_at";

    private final SharedPreferences p;

    Prefs(Context context) {
        p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrateLegacyIfNeeded();
    }

    static final class Slot {
        final int index;
        final String name;
        final boolean enabled;
        final boolean backgroundEnabled;
        final boolean readEnabled;
        final boolean messageEnabled;
        final boolean notifyEnabled;
        final boolean vibrateEnabled;
        final boolean saveMessageContentEnabled;
        final boolean armed;
        final int baseCount;
        final int baseMaxY;
        final long armedAt;
        final String incomingSignature;
        final long lastCheckedAt;
        final long lastUnreadSeenAt;
        final String status;

        Slot(int index, String name, boolean enabled, boolean backgroundEnabled,
             boolean readEnabled, boolean messageEnabled, boolean notifyEnabled,
             boolean vibrateEnabled, boolean saveMessageContentEnabled,
             boolean armed, int baseCount, int baseMaxY, long armedAt,
             String incomingSignature, long lastCheckedAt,
             long lastUnreadSeenAt, String status) {
            this.index = index;
            this.name = name;
            this.enabled = enabled;
            this.backgroundEnabled = backgroundEnabled;
            this.readEnabled = readEnabled;
            this.messageEnabled = messageEnabled;
            this.notifyEnabled = notifyEnabled;
            this.vibrateEnabled = vibrateEnabled;
            this.saveMessageContentEnabled = saveMessageContentEnabled;
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

        boolean backgroundActive() {
            return active() && armed && backgroundEnabled;
        }
    }

    private String k(int index, String suffix) { return "slot_" + index + "_" + suffix; }

    Slot slot(int index) {
        if (index < 0 || index >= MAX_SLOTS) return emptySlot(index);
        return new Slot(
            index,
            p.getString(k(index, "name"), "").trim(),
            p.getBoolean(k(index, "enabled"), true),
            p.getBoolean(k(index, "background"), false),
            p.getBoolean(k(index, "read"), true),
            p.getBoolean(k(index, "messages"), true),
            p.getBoolean(k(index, "notify"), true),
            p.getBoolean(k(index, "vibrate"), false),
            p.getBoolean(k(index, "save_content"), false),
            p.getBoolean(k(index, "armed"), false),
            p.getInt(k(index, "base_count"), 0),
            p.getInt(k(index, "base_max_y"), -1),
            p.getLong(k(index, "armed_at"), 0L),
            p.getString(k(index, "incoming_sig"), ""),
            p.getLong(k(index, "last_checked"), 0L),
            p.getLong(k(index, "last_unread"), 0L),
            p.getString(k(index, "status"), "尚未建立基準")
        );
    }

    private Slot emptySlot(int index) {
        return new Slot(index, "", true, false, true, true, true, false, false,
            false, 0, -1, 0L, "", 0L, 0L, "尚未建立基準");
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

    int backgroundActiveCount() {
        int count = 0;
        for (int i = 0; i < MAX_SLOTS; i++) if (slot(i).backgroundActive()) count++;
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
                    .putBoolean(k(i, "background"), false)
                    .putBoolean(k(i, "read"), true)
                    .putBoolean(k(i, "messages"), true)
                    .putBoolean(k(i, "notify"), true)
                    .putBoolean(k(i, "vibrate"), false)
                    .putBoolean(k(i, "save_content"), false)
                    .putBoolean(k(i, "armed"), false)
                    .putInt(k(i, "read_schema"), READ_DETECTOR_SCHEMA)
                    .putString(k(i, "incoming_sig"), "")
                    .putString(k(i, "status"), "尚未建立基準")
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
            .putBoolean(k(index, "background"), false)
            .putInt(k(index, "read_schema"), READ_DETECTOR_SCHEMA)
            .putString(k(index, "incoming_sig"), "")
            .putString(k(index, "status"), "名稱已更新，請重新建立基準")
            .apply();
    }

    void deleteTarget(int index) {
        if (index < 0 || index >= MAX_SLOTS) return;
        SharedPreferences.Editor e = p.edit();
        String[] suffixes = new String[]{
            "name", "enabled", "background", "read", "messages", "notify", "vibrate", "save_content",
            "armed", "base_count", "base_max_y", "armed_at", "read_schema", "incoming_sig",
            "last_checked", "last_unread", "status"
        };
        for (String suffix : suffixes) e.remove(k(index, suffix));
        e.apply();
    }

    void setSlotEnabled(int index, boolean enabled) {
        p.edit().putBoolean(k(index, "enabled"), enabled)
            .putString(k(index, "status"), enabled ? (slot(index).armed ? "等待可讀聊天室" : "尚未建立基準") : "已暫停")
            .apply();
    }

    void setBackgroundEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "background"), enabled).apply(); }
    void setReadEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "read"), enabled).apply(); }
    void setMessageEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "messages"), enabled).apply(); }
    void setNotifyEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "notify"), enabled).apply(); }
    void setVibrateEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "vibrate"), enabled).apply(); }
    void setSaveMessageContentEnabled(int index, boolean enabled) { p.edit().putBoolean(k(index, "save_content"), enabled).apply(); }

    void armSlot(int index, int count, int maxY, String incomingSignature, long now) {
        p.edit()
            .putBoolean(k(index, "armed"), true)
            .putInt(k(index, "base_count"), count)
            .putInt(k(index, "base_max_y"), maxY)
            .putInt(k(index, "read_schema"), READ_DETECTOR_SCHEMA)
            .putLong(k(index, "armed_at"), now)
            .putLong(k(index, "last_unread"), now)
            .putString(k(index, "incoming_sig"), incomingSignature == null ? "" : incomingSignature)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "基準已建立 · 即時監控中")
            .apply();
    }

    boolean needsReadDetectorRebaseline(int index) {
        if (index < 0 || index >= MAX_SLOTS) return false;
        return p.getInt(k(index, "read_schema"), 1) < READ_DETECTOR_SCHEMA;
    }

    void rebaselineReadDetector(int index, int count, int maxY, long now) {
        if (index < 0 || index >= MAX_SLOTS) return;
        p.edit()
            .putBoolean(k(index, "armed"), true)
            .putInt(k(index, "base_count"), count)
            .putInt(k(index, "base_max_y"), maxY)
            .putInt(k(index, "read_schema"), READ_DETECTOR_SCHEMA)
            .putLong(k(index, "armed_at"), now)
            .putLong(k(index, "last_unread"), now)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "已讀偵測器已重新校準 · 監控中")
            .apply();
    }

    void updateReadBaseline(int index, int count, int maxY, long now) {
        p.edit()
            .putBoolean(k(index, "armed"), true)
            .putInt(k(index, "base_count"), count)
            .putInt(k(index, "base_max_y"), maxY)
            .putInt(k(index, "read_schema"), READ_DETECTOR_SCHEMA)
            .putLong(k(index, "armed_at"), now)
            .putLong(k(index, "last_unread"), now)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "即時監控中")
            .apply();
    }

    void markUnreadSeen(int index, long now) {
        p.edit().putLong(k(index, "last_unread"), now)
            .putLong(k(index, "last_checked"), now)
            .putString(k(index, "status"), "即時監控中").apply();
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
    boolean backgroundPollingEnabled() { return p.getBoolean(K_BACKGROUND_POLLING, true); }

    void setGlobalEnabled(boolean enabled) { p.edit().putBoolean(K_GLOBAL_ENABLED, enabled).putBoolean(K_PAUSED, false).apply(); }
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

    void setVirtualDisplayRunning(int displayId, String status) {
        p.edit()
            .putBoolean(K_VD_RUNNING, true)
            .putInt(K_VD_ID, displayId)
            .putString(K_VD_STATUS, status == null ? "第二畫面運作中" : status)
            .remove(K_SECONDARY_LINE_ID)
            .remove(K_SECONDARY_LINE_SEEN_AT)
            .apply();
    }

    void updateVirtualDisplayStatus(String status) {
        p.edit().putString(K_VD_STATUS, status == null ? "第二畫面運作中" : status).apply();
    }

    void setVirtualDisplayStopped(String reason) {
        p.edit()
            .putBoolean(K_VD_RUNNING, false)
            .putInt(K_VD_ID, -1)
            .putString(K_VD_STATUS, reason == null ? "第二畫面已停止" : reason)
            .remove(K_SECONDARY_LINE_ID)
            .remove(K_SECONDARY_LINE_SEEN_AT)
            .apply();
    }

    boolean virtualDisplayRunning() { return p.getBoolean(K_VD_RUNNING, false); }
    int virtualDisplayId() { return p.getInt(K_VD_ID, -1); }
    String virtualDisplayStatus() { return p.getString(K_VD_STATUS, "尚未建立第二畫面"); }

    void markSecondaryLineSeen(int displayId, long now) {
        p.edit()
            .putInt(K_SECONDARY_LINE_ID, displayId)
            .putLong(K_SECONDARY_LINE_SEEN_AT, now)
            .apply();
    }

    int secondaryLineDisplayId() { return p.getInt(K_SECONDARY_LINE_ID, -1); }
    long secondaryLineSeenAt() { return p.getLong(K_SECONDARY_LINE_SEEN_AT, 0L); }

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

    int nextBackgroundSlot() {
        int start = Math.max(0, Math.min(pollIndex(), MAX_SLOTS - 1));
        for (int offset = 0; offset < MAX_SLOTS; offset++) {
            int index = (start + offset) % MAX_SLOTS;
            if (slot(index).backgroundActive()) {
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
                .putBoolean(k(0, "background"), false)
                .putBoolean(k(0, "read"), true)
                .putBoolean(k(0, "messages"), true)
                .putBoolean(k(0, "notify"), true)
                .putBoolean(k(0, "vibrate"), false)
                .putBoolean(k(0, "save_content"), false)
                .putBoolean(k(0, "armed"), false)
                .putInt(k(0, "read_schema"), READ_DETECTOR_SCHEMA)
                .putString(k(0, "status"), "尚未建立基準");
            if (legacyEnabled) e.putBoolean(K_GLOBAL_ENABLED, true);
        }
        e.putBoolean(K_MIGRATED, true).apply();
    }
}
