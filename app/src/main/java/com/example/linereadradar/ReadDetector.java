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

        // Most important transition: there was no visible read label before and one
        // appears now. Do not require a previous Y position for this case.
        if (current.readCount > baseline.readCount) return true;

        // Accessibility may temporarily recycle / remove older LINE nodes. A count
        // drop is layout churn, not a new read event.
        if (current.readCount < baseline.readCount) return false;

        // With the same number of labels, a clearly lower read marker can represent
        // the newest sent message becoming read. Ignore small layout shifts.
        if (baseline.maxReadBottom < 0 || current.maxReadBottom < 0) return false;
        return current.maxReadBottom > baseline.maxReadBottom + minVerticalAdvancePx;
    }

    private ReadDetector() {}
}
