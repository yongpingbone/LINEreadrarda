package com.example.linereadradar;

import android.app.Activity;
import android.view.Display;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class NoReadXposedHook implements IXposedHookLoadPackage {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static volatile boolean protectSecondaryDisplay = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !LINE_PACKAGE.equals(lpparam.packageName)) return;

        boolean displayGateHooked = hookActivityDisplayGate();
        boolean requestHooked = hookSendChatChecked(lpparam);
        boolean markHooked = hookKnownMarkAsRead(lpparam);
        XposedBridge.log(
            "LINE Radar NoRead loaded. displayGate=" + displayGateHooked
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
                    int displayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
                    protectSecondaryDisplay = displayId != Display.DEFAULT_DISPLAY;
                    if (protectSecondaryDisplay) {
                        XposedBridge.log("LINE Radar NoRead armed on secondary display " + displayId);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    int displayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        protectSecondaryDisplay = false;
                        XposedBridge.log("LINE Radar NoRead disarmed after secondary display pause");
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            XposedBridge.log("LINE Radar NoRead display gate unavailable: " + t);
            return false;
        }
    }

    private boolean hookSendChatChecked(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> requestClass = lpparam.classLoader.loadClass("org.apache.thrift.l");
            XposedBridge.hookAllMethods(requestClass, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!protectSecondaryDisplay) return;
                    if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                    if ("sendChatChecked".equals(String.valueOf(param.args[0]))) {
                        param.args[0] = "noop";
                        XposedBridge.log("LINE Radar NoRead blocked sendChatChecked on secondary display");
                    }
                }
            });
            return true;
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
                    if (!protectSecondaryDisplay) return;
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
