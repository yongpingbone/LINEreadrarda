package com.example.linereadradar;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class VirtualDisplayProbeAccessibilityService extends AccessibilityService {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String PREF_FILE = "radar_probe";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        scanNow();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        write("Probe accessibility connected. Waiting for LINE windows...");
        scanNow();
    }

    @Override
    public void onInterrupt() {}

    private void scanNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            write("Android version too old for getWindowsOnAllDisplays()");
            return;
        }
        try {
            SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all == null || all.size() == 0) {
                write("No accessibility windows returned");
                return;
            }

            StringBuilder out = new StringBuilder();
            int lineWindows = 0;
            for (int i = 0; i < all.size(); i++) {
                int displayId = all.keyAt(i);
                List<AccessibilityWindowInfo> windows = all.valueAt(i);
                if (windows == null) continue;
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null || root.getPackageName() == null) continue;
                    if (!LINE_PACKAGE.contentEquals(root.getPackageName())) continue;

                    lineWindows++;
                    Counts c = countTree(root);
                    if (out.length() > 0) out.append('\n');
                    out.append("Display ").append(displayId)
                        .append(": LINE visible to Accessibility")
                        .append(" | nodes=").append(c.nodes)
                        .append(" | 已讀=").append(c.readCount)
                        .append(" | text=").append(c.textNodes);
                }
            }

            if (lineWindows == 0) {
                write("Accessibility sees displays, but no LINE window yet");
            } else {
                write(out.toString());
            }
        } catch (Throwable t) {
            write(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    private Counts countTree(AccessibilityNodeInfo root) {
        int nodes = 0;
        int read = 0;
        int textNodes = 0;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && nodes < 5000) {
            AccessibilityNodeInfo node = stack.pop();
            nodes++;
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0)) textNodes++;
            if ((text != null && "已讀".contentEquals(text)) || (desc != null && "已讀".contentEquals(desc))) read++;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        return new Counts(nodes, read, textNodes);
    }

    private void write(String value) {
        getSharedPreferences(PREF_FILE, MODE_PRIVATE).edit()
            .putString("accessibility", value)
            .putLong("accessibility_at", System.currentTimeMillis())
            .apply();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }

    private static final class Counts {
        final int nodes;
        final int readCount;
        final int textNodes;
        Counts(int nodes, int readCount, int textNodes) {
            this.nodes = nodes;
            this.readCount = readCount;
            this.textNodes = textNodes;
        }
    }
}
