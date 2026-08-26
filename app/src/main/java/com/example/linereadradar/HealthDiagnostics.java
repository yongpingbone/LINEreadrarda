package com.example.linereadradar;

import android.content.Context;
import android.content.SharedPreferences;

final class HealthDiagnostics {
    private static final String FILE = "radar_health_v067";
    private static final String K_SESSION_DISPLAY = "session_display_id";
    private static final String K_LAST_PULSE_SUCCESS = "last_pulse_success_at";
    private static final String K_LAST_PULSE_DISPLAY = "last_pulse_display_id";
    private static final String K_LAST_HARD_REFRESH = "last_hard_refresh_at";
    private static final String K_LAST_HARD_REFRESH_DISPLAY = "last_hard_refresh_display_id";

    private final SharedPreferences p;

    HealthDiagnostics(Context context) {
        p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private String k(int slot, String suffix) {
        return "slot_" + slot + "_" + suffix;
    }

    void resetForNewDisplay(int displayId) {
        SharedPreferences.Editor e = p.edit()
            .putInt(K_SESSION_DISPLAY, displayId)
            .remove(K_LAST_PULSE_SUCCESS)
            .remove(K_LAST_PULSE_DISPLAY)
            .remove(K_LAST_HARD_REFRESH)
            .remove(K_LAST_HARD_REFRESH_DISPLAY);
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            e.remove(k(i, "target_seen_at"))
                .remove(k(i, "target_display_id"))
                .remove(k(i, "tree_signature"))
                .remove(k(i, "tree_change_at"))
                .remove(k(i, "tree_display_id"))
                .remove(k(i, "calibrated_display_id"));
        }
        e.apply();
    }

    int sessionDisplayId() {
        return p.getInt(K_SESSION_DISPLAY, -1);
    }

    void markPulseSuccess(int displayId, long now) {
        p.edit()
            .putLong(K_LAST_PULSE_SUCCESS, now)
            .putInt(K_LAST_PULSE_DISPLAY, displayId)
            .apply();
    }

    long lastPulseSuccessAt() {
        return p.getLong(K_LAST_PULSE_SUCCESS, 0L);
    }

    int lastPulseDisplayId() {
        return p.getInt(K_LAST_PULSE_DISPLAY, -1);
    }

    void markHardRefresh(int displayId, long now) {
        p.edit()
            .putLong(K_LAST_HARD_REFRESH, now)
            .putInt(K_LAST_HARD_REFRESH_DISPLAY, displayId)
            .apply();
    }

    long lastHardRefreshAt() {
        return p.getLong(K_LAST_HARD_REFRESH, 0L);
    }

    int lastHardRefreshDisplayId() {
        return p.getInt(K_LAST_HARD_REFRESH_DISPLAY, -1);
    }

    void markTargetChatScan(int slot, int displayId, String treeSignature, long now) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS || displayId <= 0) return;
        String signature = treeSignature == null ? "" : treeSignature;
        String oldSignature = p.getString(k(slot, "tree_signature"), "");
        int oldDisplayId = p.getInt(k(slot, "tree_display_id"), -1);

        SharedPreferences.Editor e = p.edit()
            .putLong(k(slot, "target_seen_at"), now)
            .putInt(k(slot, "target_display_id"), displayId);

        if (oldDisplayId != displayId || !signature.equals(oldSignature)) {
            e.putString(k(slot, "tree_signature"), signature)
                .putLong(k(slot, "tree_change_at"), now)
                .putInt(k(slot, "tree_display_id"), displayId);
        }
        e.apply();
    }

    long targetChatSeenAt(int slot) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS) return 0L;
        return p.getLong(k(slot, "target_seen_at"), 0L);
    }

    int targetChatDisplayId(int slot) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS) return -1;
        return p.getInt(k(slot, "target_display_id"), -1);
    }

    long lastTreeSignatureChangeAt(int slot) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS) return 0L;
        return p.getLong(k(slot, "tree_change_at"), 0L);
    }

    String treeSignature(int slot) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS) return "";
        return p.getString(k(slot, "tree_signature"), "");
    }

    boolean isSecondaryCalibrated(int slot, int displayId) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS || displayId <= 0) return false;
        return p.getInt(k(slot, "calibrated_display_id"), -1) == displayId;
    }

    void markSecondaryCalibrated(int slot, int displayId) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS || displayId <= 0) return;
        p.edit().putInt(k(slot, "calibrated_display_id"), displayId).apply();
    }

    long targetFreshWindowMs(Prefs prefs) {
        long poll = prefs == null ? 60000L : prefs.pollIntervalMs();
        return Math.max(15000L, Math.min(180000L, poll + 15000L));
    }

    boolean isTargetRecent(int slot, int displayId, long now, Prefs prefs) {
        if (slot < 0 || slot >= Prefs.MAX_SLOTS || displayId <= 0) return false;
        long seenAt = targetChatSeenAt(slot);
        return targetChatDisplayId(slot) == displayId
            && seenAt > 0L
            && now - seenAt <= targetFreshWindowMs(prefs);
    }

    boolean hasRecentBackgroundTarget(Prefs prefs, int displayId, long now) {
        if (prefs == null || displayId <= 0) return false;
        for (int i = 0; i < Prefs.MAX_SLOTS; i++) {
            Prefs.Slot slot = prefs.slot(i);
            if (!slot.backgroundActive()) continue;
            if (isTargetRecent(i, displayId, now, prefs)) return true;
        }
        return false;
    }
}
