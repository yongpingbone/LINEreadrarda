package com.example.linereadradar;

import android.content.Context;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RadarShellUserService extends IRadarShellService.Stub {
    public RadarShellUserService() {}

    @Keep
    public RadarShellUserService(Context context) {}

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String execute(String command) {
        if (command == null || command.trim().isEmpty()) return "ERR:2\nempty command";
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start();
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
