package com.example.linereadradar;

import android.content.Context;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RadarShellUserService extends IRadarShellService.Stub {
    private static final String LINE_PACKAGE = "jp.naver.line.android";

    public RadarShellUserService() {}

    @Keep
    public RadarShellUserService(Context context) {}

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String startActivityOnDisplay(String packageName, String componentName, int displayId) {
        if (!LINE_PACKAGE.equals(packageName)) return "ERR:2\npackage not allowed";
        if (componentName == null || !componentName.startsWith(packageName + "/")) {
            return "ERR:2\ninvalid component";
        }
        if (displayId <= 0) return "ERR:2\ninvalid display";

        Process process = null;
        try {
            process = new ProcessBuilder(
                "am", "start",
                "--user", "0",
                "--display", String.valueOf(displayId),
                "-n", componentName
            ).redirectErrorStream(true).start();

            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                if (out.length() > 4000) break;
            }

            int code = process.waitFor();
            if (code == 0) return "OK\n" + out;
            return "ERR:" + code + "\n" + out;
        } catch (Throwable t) {
            return "ERR:1\n" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }
}
