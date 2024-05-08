package com.close.hook.ads.hook.gc;

import android.app.UiModeManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import java.io.File;

import java.lang.reflect.Constructor;

import com.close.hook.ads.hook.util.HookUtil;

public class AntiEmulatorDetection {

    public static void handle() {
        HookUtil.setStaticObjectField(Build.class, "FINGERPRINT", "no_emulator");
        HookUtil.setStaticObjectField(Build.class, "MODEL", "Real Device");
        HookUtil.setStaticObjectField(Build.class, "MANUFACTURER", "Real Manufacturer");
        HookUtil.setStaticObjectField(Build.class, "PRODUCT", "Real Product");
        HookUtil.setStaticObjectField(Build.class, "BRAND", "Real Brand");
        HookUtil.setStaticObjectField(Build.class, "DEVICE", "Real Device");

        HookUtil.hookAllMethods(File.class, "exists", "before", param -> {
            File file = (File) param.thisObject;
            if (file != null && (file.getPath().contains("qemud") || file.getPath().contains("qemu_pipe") ||
                file.getPath().contains("genyd") || file.getPath().contains("genymotion") ||
                file.getPath().contains("andy") || file.getPath().contains("nox") ||
                file.getPath().contains("ttVM_x86") || file.getPath().contains("vbox86"))) {
                param.setResult(false);
            }
        });

        HookUtil.hookAllMethods(System.class, "getProperty", "before", param -> {
            String key = (String) param.args[0];
            if (key.startsWith("init.svc.") || key.startsWith("qemu.") || key.startsWith("ro.hardware.")) {
                param.setResult("0");
            }
        });

        HookUtil.hookAllMethods(TelephonyManager.class, "getNetworkOperatorName", "before", param -> {
            param.setResult("Real Carrier");
        });

        HookUtil.hookAllMethods(SensorManager.class, "getDefaultSensor", "before", param -> {
            if ((Integer) param.args[0] == Sensor.TYPE_LIGHT) {
                param.setResult(createFakeSensor());
            }
        });

        HookUtil.hookAllMethods(UiModeManager.class, "getCurrentModeType", "before", param -> {
            param.setResult(1);
        });

        HookUtil.hookAllMethods("android.app.ApplicationPackageManager", "hasSystemFeature", "before", param -> {
            if ("android.hardware.telephony".equals(param.args[0])) {
                param.setResult(true);
            }
        });

        HookUtil.hookAllMethods(ContextCompat.class, "checkSelfPermission", "before", param -> {
            if ("android.permission.READ_PHONE_STATE".equals(param.args[0])) {
                param.setResult(PackageManager.PERMISSION_GRANTED);
            }
        });
    }

    public static Sensor createFakeSensor() {
        try {
            Constructor<Sensor> constructor = Sensor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create sensor instance.", e);
        }
    }
}
