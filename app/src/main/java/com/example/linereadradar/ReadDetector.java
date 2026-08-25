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
        if (current.readCount > baseline.readCount) return true;
        return baseline.maxReadBottom >= 0
            && current.maxReadBottom > baseline.maxReadBottom + minVerticalAdvancePx;
    }

    private ReadDetector() {}
}
