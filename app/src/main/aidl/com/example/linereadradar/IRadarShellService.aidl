package com.example.linereadradar;

interface IRadarShellService {
    void destroy() = 16777114;
    String startActivityOnDisplay(String packageName, String componentName, int displayId) = 1;
    int ensureVirtualDisplay(int width, int height, int densityDpi) = 2;
    int getVirtualDisplayId() = 3;
    String getVirtualDisplayStatus() = 4;
    void releaseVirtualDisplay() = 5;
    String pulseAppTaskOnDisplay(String packageName, int displayId) = 6;
    String inputTapOnDisplay(int displayId, int x, int y) = 7;
    String inputSwipeOnDisplay(int displayId, int startX, int startY, int endX, int endY, int durationMs) = 8;
    String inputBackOnDisplay(int displayId) = 9;
    String startIsolatedActivityOnDisplay(String packageName, String componentName, int displayId) = 10;
    String getAppTaskTopology(String packageName, int preferredDisplayId) = 11;
}
