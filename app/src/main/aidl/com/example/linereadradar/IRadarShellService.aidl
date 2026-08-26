package com.example.linereadradar;

interface IRadarShellService {
    void destroy() = 16777114;
    String startActivityOnDisplay(String packageName, String componentName, int displayId) = 1;
    int ensureVirtualDisplay(int width, int height, int densityDpi) = 2;
    int getVirtualDisplayId() = 3;
    String getVirtualDisplayStatus() = 4;
    void releaseVirtualDisplay() = 5;
}
