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
        if (current.readCount <= 0 || current.maxReadBottom < 0) return false;

        // A read marker appearing where none was previously visible is the strongest
        // possible transition and must never depend on a previous Y coordinate.
        if (baseline.readCount <= 0 || baseline.maxReadBottom < 0) return true;

        // If LINE exposes one extra read marker, report it even if recycler/layout
        // movement keeps the newest marker at approximately the same Y coordinate.
        if (current.readCount > baseline.readCount) return true;

        // LINE aggressively recycles Accessibility rows. The visible read count can
        // stay the same or even drop while the newest outgoing message becomes read.
        // The durable signal in that case is a new bottom-most read marker.
        int advance = Math.max(1, minVerticalAdvancePx);
        return current.maxReadBottom > baseline.maxReadBottom + advance;
    }

    private ReadDetector() {}
}
