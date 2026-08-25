package com.example.linereadradar;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class NoReadXposedHook implements IXposedHookLoadPackage {
    private static final String LINE_PACKAGE = "jp.naver.line.android";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !LINE_PACKAGE.equals(lpparam.packageName)) return;

        boolean requestHooked = hookSendChatChecked(lpparam);
        boolean markHooked = hookKnownMarkAsRead(lpparam);
        XposedBridge.log("LINE Radar NoRead loaded. requestHook=" + requestHooked + ", markHook=" + markHooked);
    }

    private boolean hookSendChatChecked(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> requestClass = lpparam.classLoader.loadClass("org.apache.thrift.l");
            XposedBridge.hookAllMethods(requestClass, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                    if ("sendChatChecked".equals(String.valueOf(param.args[0]))) {
                        param.args[0] = "noop";
                        XposedBridge.log("LINE Radar NoRead blocked sendChatChecked");
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
                    param.setResult(null);
                    XposedBridge.log("LINE Radar NoRead blocked known mark-as-read path");
                }
            });
            return true;
        } catch (Throwable t) {
            XposedBridge.log("LINE Radar NoRead mark hook unavailable: " + t);
            return false;
        }
    }
}
