package com.example.linereadradar;

final class ReadDetector {
    static final class Snapshot {
        final int readCount;
        final int maxReadBottom;

        Snapshot(int readCount, int maxReadBottom) {
            this.readCount = readCount;
            this.maxReadBottom = maxReadBottom;
        }
    }

    static boolean isNewRead(Snapshot baseline, Snapshot current, int minVerticalAdvancePx) {
        if (baseline == null || current == null) return false;
        if (baseline.maxReadBottom < 0 || current.maxReadBottom < 0) return false;

        boolean countAdvanced = current.readCount > baseline.readCount;
        boolean bottomAdvanced = current.maxReadBottom > baseline.maxReadBottom + minVerticalAdvancePx;

        // LINE may rebuild old accessibility nodes just by opening a chat.
        // Count-only or position-only changes are not enough to claim a new read.
        return countAdvanced && bottomAdvanced;
    }

    private ReadDetector() {}
}
