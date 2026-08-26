package com.example.linereadradar;

import android.app.Activity;
import android.os.SystemClock;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class NoReadXposedHook implements IXposedHookLoadPackage {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final long SECONDARY_TRANSITION_LEASE_MS = 8000L;

    private static final Object GATE_LOCK = new Object();
    private static final WeakHashMap<Activity, Integer> RESUMED_ACTIVITIES = new WeakHashMap<>();
    private static volatile long secondaryProtectionLeaseUntil = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !LINE_PACKAGE.equals(lpparam.packageName)) return;

        boolean displayGateHooked = hookActivityDisplayGate();
        boolean requestHooked = hookSendChatChecked(lpparam);
        boolean markHooked = hookKnownMarkAsRead(lpparam);
        XposedBridge.log(
            "LINE Radar NoRead v2 loaded. displayGate=" + displayGateHooked
                + ", requestHook=" + requestHooked
                + ", markHook=" + markHooked
        );
    }

    private boolean hookActivityDisplayGate() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    int displayId = resolveDisplayId(activity);
                    long now = SystemClock.elapsedRealtime();
                    synchronized (GATE_LOCK) {
                        RESUMED_ACTIVITIES.put(activity, displayId);
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            // A real main-screen LINE activity takes priority so normal user reads still work.
                            secondaryProtectionLeaseUntil = 0L;
                            XposedBridge.log("LINE Radar NoRead main-display LINE resumed; secondary lease cleared");
                        } else if (displayId >= 0) {
                            secondaryProtectionLeaseUntil = now + SECONDARY_TRANSITION_LEASE_MS;
                            XposedBridge.log("LINE Radar NoRead armed on secondary display " + displayId);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    int displayId = resolveDisplayId(activity);
                    long now = SystemClock.elapsedRealtime();
                    synchronized (GATE_LOCK) {
                        RESUMED_ACTIVITIES.remove(activity);
                        if (displayId != Display.DEFAULT_DISPLAY && displayId >= 0) {
                            // Do not disarm immediately. LINE changes activity/fragment while search opens a chat.
                            secondaryProtectionLeaseUntil = Math.max(
                                secondaryProtectionLeaseUntil,
                                now + SECONDARY_TRANSITION_LEASE_MS
                            );
                            XposedBridge.log(
                                "LINE Radar NoRead secondary activity paused; transition lease kept for "
                                    + SECONDARY_TRANSITION_LEASE_MS + "ms"
                            );
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    synchronized (GATE_LOCK) {
                        RESUMED_ACTIVITIES.remove((Activity) param.thisObject);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            XposedBridge.log("LINE Radar NoRead display gate unavailable: " + t);
            return false;
        }
    }

    private boolean shouldProtectSecondaryRead() {
        long now = SystemClock.elapsedRealtime();
        synchronized (GATE_LOCK) {
            boolean secondaryResumed = false;
            boolean defaultResumed = false;

            for (Map.Entry<Activity, Integer> entry : RESUMED_ACTIVITIES.entrySet()) {
                Integer displayId = entry.getValue();
                if (displayId == null || displayId < 0) continue;
                if (displayId == Display.DEFAULT_DISPLAY) defaultResumed = true;
                else secondaryResumed = true;
            }

            // If the user is actively viewing LINE on the phone display, preserve normal LINE behavior.
            if (defaultResumed) return false;

            if (secondaryResumed) {
                secondaryProtectionLeaseUntil = Math.max(
                    secondaryProtectionLeaseUntil,
                    now + SECONDARY_TRANSITION_LEASE_MS
                );
                return true;
            }

            // Covers the short gap between search result -> chat activity transitions on Display > 0.
            return now <= secondaryProtectionLeaseUntil;
        }
    }

    private int resolveDisplayId(Activity activity) {
        if (activity == null) return -1;
        try {
            if (activity.getDisplay() != null) return activity.getDisplay().getDisplayId();
        } catch (Throwable ignored) {}
        try {
            return activity.getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean hookSendChatChecked(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> requestClass = lpparam.classLoader.loadClass("org.apache.thrift.l");
            XC_MethodHook blocker = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!shouldProtectSecondaryRead()) return;
                    if (param.args == null || param.args.length == 0) return;

                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        if (!(arg instanceof String)) continue;
                        if (!"sendChatChecked".equals(arg)) continue;

                        param.args[i] = "noop";
                        XposedBridge.log(
                            "LINE Radar NoRead blocked sendChatChecked via "
                                + param.method.getName() + " arg#" + i
                        );
                        return;
                    }
                }
            };

            int hookedCount = 0;
            for (Method method : requestClass.getDeclaredMethods()) {
                boolean candidate = "b".equals(method.getName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (parameterType == String.class) {
                        candidate = true;
                        break;
                    }
                }
                if (!candidate) continue;
                try {
                    XposedBridge.hookMethod(method, blocker);
                    hookedCount++;
                } catch (Throwable hookError) {
                    XposedBridge.log(
                        "LINE Radar NoRead skipped thrift method " + method.getName() + ": " + hookError
                    );
                }
            }
            XposedBridge.log("LINE Radar NoRead request candidates hooked=" + hookedCount);
            return hookedCount > 0;
        } catch (Throwable t) {
            XposedBridge.log("LINE Radar NoRead request hook unavailable: " + t);
            return false;
        }
    }

    private boolean hookKnownMarkAsRead(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> markClass = lpparam.classLoader.loadClass("KO.d$d");
            XposedBridge.hookAllMethods(markClass, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!shouldProtectSecondaryRead()) return;
                    param.setResult(null);
                    XposedBridge.log("LINE Radar NoRead blocked known mark-as-read path on secondary display");
                }
            });
            return true;
        } catch (Throwable t) {
            XposedBridge.log("LINE Radar NoRead mark hook unavailable: " + t);
            return false;
        }
    }
}
