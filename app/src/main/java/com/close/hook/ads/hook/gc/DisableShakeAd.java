package com.close.hook.ads.hook.gc;

import android.hardware.SensorManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DisableShakeAd {

    public static void handle(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> sensorManagerClass = lpparam.classLoader.loadClass(SensorManager.class.getName());
            XposedBridge.hookAllMethods(sensorManagerClass, "registerListener", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length == 3) {
                        param.setResult(true);
                    }
                }
            });
        } catch (ClassNotFoundException e) {
            XposedBridge.log("SensorManagerHook: ClassNotFoundException - " + e.getMessage());
        }
    }
}
