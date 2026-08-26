package com.example.linereadradar;

interface IRadarShellService {
    void destroy() = 16777114;
    String startActivityOnDisplay(String packageName, String componentName, int displayId) = 1;
}
