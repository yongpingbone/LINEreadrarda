package com.example.linereadradar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReadDetectorTest {
    @Test
    public void firstReadAppearingFromNoLabelIsDetected() {
        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(0, -1);
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(1, 900);
        assertTrue(ReadDetector.isNewRead(baseline, current, 20));
    }

    @Test
    public void increasedReadCountDoesNotRequireVerticalMovement() {
        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(1, 900);
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(2, 900);
        assertTrue(ReadDetector.isNewRead(baseline, current, 20));
    }

    @Test
    public void recyclerCountDropWithNewBottomMarkerIsDetected() {
        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(3, 800);
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(2, 900);
        assertTrue(ReadDetector.isNewRead(baseline, current, 20));
    }

    @Test
    public void sameCountWithClearlyLowerMarkerIsDetected() {
        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(1, 700);
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(1, 760);
        assertTrue(ReadDetector.isNewRead(baseline, current, 20));
    }

    @Test
    public void smallLayoutShiftIsIgnored() {
        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(1, 700);
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(1, 710);
        assertFalse(ReadDetector.isNewRead(baseline, current, 20));
    }

    @Test
    public void noVisibleReadMarkerIsNotDetected() {
        ReadDetector.Snapshot baseline = new ReadDetector.Snapshot(1, 700);
        ReadDetector.Snapshot current = new ReadDetector.Snapshot(0, -1);
        assertFalse(ReadDetector.isNewRead(baseline, current, 20));
    }
}
