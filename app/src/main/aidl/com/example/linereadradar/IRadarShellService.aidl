package com.example.linereadradar;

interface IRadarShellService {
    void destroy() = 16777114;
    String execute(String command) = 1;
}
