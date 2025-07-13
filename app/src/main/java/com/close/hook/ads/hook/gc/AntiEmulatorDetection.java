package com.close.hook.ads.hook.gc;

import android.app.UiModeManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.content.pm.PackageManager;
import android.opengl.GLES10;
import android.view.Display;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.text.TextUtils;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Set;
import java.util.HashSet;

import com.close.hook.ads.hook.util.HookUtil;

public class AntiEmulatorDetection {

    private static final Set<String> EMULATOR_SPECIFIC_FILE_PATHS = Set.of(
            "/system/bin/qemud",
            "/system/bin/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/system/lib/lib_dl_qemu.so",
            "/data/misc/qemu_pipes",
            "/dev/qemu_pipe",
            "/dev/socket/qemud",
            "/vendor/etc/vbox_properties.xml"
    );

    private static final Set<String> EMULATOR_SPECIFIC_BUILD_KEYS_VALUES = Set.of(
            "ro.kernel.qemu=1",
            "qemu.sf.lcd_density",
            "qemu.hw.mainkeys",
            "ro.boot.qemu=1",
            "ro.hardware.qemu",
            "ro.build.description=generic"
    );

    private static final Set<String> EMULATOR_BUILD_KEYWORDS = Set.of(
            "generic",
            "emulator",
            "sdk", "vbox86",
            "goldfish",
            "x86",
            "x86_64",
            "unknown"
    );

    private static final Set<String> EMULATOR_GL_RENDERER_KEYWORDS = Set.of(
            "swiftshader",
            "androidemulator",
            "virtualbox",
            "vmware"
    );

    public static void handle() {
        if (isEmulator()) {
            HookUtil.setStaticObjectField(Build.class, "FINGERPRINT", "samsung/dreamltexx/dreamlte:9/PPR1.180610.011/G960FXXU7CSJ1:user/release-keys");
            HookUtil.setStaticObjectField(Build.class, "MODEL", "SM-G960F");
            HookUtil.setStaticObjectField(Build.class, "MANUFACTURER", "samsung");
            HookUtil.setStaticObjectField(Build.class, "PRODUCT", "dreamltexx");
            HookUtil.setStaticObjectField(Build.class, "BRAND", "samsung");
            HookUtil.setStaticObjectField(Build.class, "DEVICE", "dreamlte");
            HookUtil.setStaticObjectField(Build.class, "HARDWARE", "qcom");
            HookUtil.setStaticObjectField(Build.class, "BOARD", "msm8998");
            HookUtil.setStaticObjectField(Build.class, "ID", "PPR1.180610.011");
            HookUtil.setStaticObjectField(Build.class, "HOST", "abuild.samsung.com");
            HookUtil.setStaticObjectField(Build.class, "TAGS", "release-keys");
            HookUtil.setStaticObjectField(Build.class, "USER", "eng.root");

            HookUtil.hookAllMethods(File.class, "exists", "before", param -> {
                File file = (File) param.thisObject;
                if (file != null) {
                    String path = file.getPath().toLowerCase();
                    for (String emuPath : EMULATOR_SPECIFIC_FILE_PATHS) {
                        if (path.contains(emuPath.toLowerCase())) {
                            param.setResult(false);
                            return;
                        }
                    }
                }
            });

            HookUtil.hookAllMethods(System.class, "getProperty", "before", param -> {
                String key = (String) param.args[0];
                if (key != null) {
                    if (EMULATOR_SPECIFIC_BUILD_KEYS_VALUES.contains(key + "=" + System.getProperty(key))) {
                        param.setResult("0");
                        return;
                    }
                    if (key.startsWith("init.svc.qemu") || key.startsWith("qemu.")) {
                        param.setResult("0");
                        return;
                    }
                }
            });

            HookUtil.hookAllMethods(TelephonyManager.class, "getNetworkOperatorName", "before", param -> {
                param.setResult("SK Telecom");
            });

            HookUtil.hookAllMethods(TelephonyManager.class, "getSimOperatorName", "before", param -> {
                param.setResult("SK Telecom");
            });

            HookUtil.hookAllMethods(TelephonyManager.class, "getNetworkCountryIso", "before", param -> {
                param.setResult("kr");
            });

            HookUtil.hookAllMethods(TelephonyManager.class, "getSimCountryIso", "before", param -> {
                param.setResult("kr");
            });

            HookUtil.hookAllMethods(TelephonyManager.class, "getLine1Number", "before", param -> {
                param.setResult("01012345678");
            });

            HookUtil.hookAllMethods(TelephonyManager.class, "getDeviceId", "before", param -> {
                param.setResult("35xxxxxxxxxxxxx");
            });

            HookUtil.hookAllMethods(SensorManager.class, "getDefaultSensor", "before", param -> {
                int sensorType = (Integer) param.args[0];
                if (sensorType == Sensor.TYPE_LIGHT ||
                    sensorType == Sensor.TYPE_ACCELEROMETER ||
                    sensorType == Sensor.TYPE_GRAVITY ||
                    sensorType == Sensor.TYPE_GYROSCOPE ||
                    sensorType == Sensor.TYPE_MAGNETIC_FIELD ||
                    sensorType == Sensor.TYPE_PROXIMITY ||
                    sensorType == Sensor.TYPE_ROTATION_VECTOR ||
                    sensorType == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
                    param.setResult(createFakeSensor(sensorType));
                }
            });

            HookUtil.hookAllMethods(UiModeManager.class, "getCurrentModeType", "before", param -> {
                param.setResult(UiModeManager.MODE_NIGHT_NO);
            });

            HookUtil.hookAllMethods("android.app.ApplicationPackageManager", "hasSystemFeature", "before", param -> {
                String feature = (String) param.args[0];
                if ("android.hardware.telephony".equals(feature) ||
                    "android.hardware.camera".equals(feature) ||
                    "android.hardware.camera.any".equals(feature) ||
                    "android.hardware.location.gps".equals(feature) ||
                    "android.hardware.wifi".equals(feature) ||
                    "android.hardware.bluetooth".equals(feature) ||
                    "android.hardware.microphone".equals(feature) ||
                    "android.hardware.touchscreen".equals(feature) ||
                    "android.software.app_widgets".equals(feature) ||
                    "android.software.live_wallpaper".equals(feature)) {
                    param.setResult(true);
                }
            });

            HookUtil.hookAllMethods("android.app.ApplicationPackageManager", "checkPermission", "before", param -> {
                String permName = (String) param.args[0];
                if ("android.permission.READ_PHONE_STATE".equals(permName) ||
                    "android.permission.ACCESS_FINE_LOCATION".equals(permName) ||
                    "android.permission.ACCESS_COARSE_LOCATION".equals(permName) ||
                    "android.permission.CAMERA".equals(permName) ||
                    "android.permission.RECORD_AUDIO".equals(permName) ||
                    "android.permission.GET_ACCOUNTS".equals(permName) ||
                    "android.permission.READ_CONTACTS".equals(permName)) {
                    param.setResult(PackageManager.PERMISSION_GRANTED);
                }
            });

            HookUtil.hookAllMethods(Display.class, "getMetrics", "before", param -> {
                DisplayMetrics metrics = (DisplayMetrics) param.args[0];
                metrics.xdpi = 420.0f;
                metrics.ydpi = 420.0f;
                metrics.density = 2.625f;
                metrics.densityDpi = 420;
                metrics.widthPixels = 1080;
                metrics.heightPixels = 2220;
            });
        }
    }

    public static boolean isEmulator() {
        int emulatorDetectionScore = 0;

        if (Build.FINGERPRINT.toLowerCase().contains("generic/vbox86") ||
            Build.FINGERPRINT.toLowerCase().contains("generic_x86/vbox86") ||
            Build.MODEL.toLowerCase().contains("android sdk built for x86") ||
            Build.MANUFACTURER.toLowerCase().contains("genymotion") ||
            Build.BRAND.toLowerCase().contains("genymotion") ||
            Build.PRODUCT.toLowerCase().contains("genymotion") ||
            Build.HARDWARE.toLowerCase().equals("vbox86") ||
            Build.BOARD.toLowerCase().equals("vbox86") ||
            "qemu".equals(Build.HARDWARE.toLowerCase()) ||
            "goldfish".equals(Build.HARDWARE.toLowerCase()) ||
            Build.FINGERPRINT.toLowerCase().contains("generic/sdk") ||
            Build.MODEL.toLowerCase().contains("google_sdk") ||
            Build.PRODUCT.toLowerCase().contains("sdk_x86")) {
            emulatorDetectionScore += 10;
        }

        for (String key_value : EMULATOR_SPECIFIC_BUILD_KEYS_VALUES) {
            String[] parts = key_value.split("=", 2);
            String key = parts[0];
            String expectedValue = (parts.length > 1) ? parts[1] : null;
            String actualValue = System.getProperty(key);

            if (!TextUtils.isEmpty(actualValue)) {
                if (expectedValue != null) {
                    if (actualValue.equals(expectedValue)) {
                        emulatorDetectionScore += 5;
                    }
                } else {
                    emulatorDetectionScore += 3;
                }
            }
        }

        for (String file : EMULATOR_SPECIFIC_FILE_PATHS) {
            if (new File(file).exists()) {
                emulatorDetectionScore += 5;
            }
        }

        String buildInfo = (Build.FINGERPRINT + Build.MODEL + Build.BRAND +
                            Build.DEVICE + Build.PRODUCT + Build.HARDWARE).toLowerCase();
        
        for (String keyword : EMULATOR_BUILD_KEYWORDS) {
            if (buildInfo.contains(keyword)) {
                emulatorDetectionScore += 1;
            }
        }
        
        if (isCPUEmulator()) {
            emulatorDetectionScore += 3;
        }

        if (isEmulatorByOpenGL()) {
            emulatorDetectionScore += 2;
        }

        if (checkTracerPid()) {
            emulatorDetectionScore += 1;
        }
        
        return emulatorDetectionScore >= 5;
    }

    private static boolean isCPUEmulator() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("Hardware") && (line.contains("goldfish") || line.contains("qemu"))) {
                    return true;
                }
            }
        } catch (IOException e) {
        }
        return false;
    }

    private static boolean checkTracerPid() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    int tracerPid = Integer.parseInt(line.substring(line.indexOf(":") + 1).trim());
                    return tracerPid != 0;
                }
            }
        } catch (IOException e) {
        }
        return false;
    }

    private static boolean isEmulatorByOpenGL() {
        String renderer = GLES10.glGetString(GLES10.GL_RENDERER);
        String vendor = GLES10.glGetString(GLES10.GL_VENDOR);

        if (renderer != null && vendor != null) {
            String lowerRenderer = renderer.toLowerCase();
            String lowerVendor = vendor.toLowerCase();

            for (String keyword : EMULATOR_GL_RENDERER_KEYWORDS) {
                if (lowerRenderer.contains(keyword)) {
                    return true;
                }
            }
            if (lowerVendor.contains("google") && lowerRenderer.contains("android")) {
                return true;
            }
        }
        return false;
    }

    public static Sensor createFakeSensor(int sensorType) {
        try {
            Constructor<Sensor> constructor = Sensor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create fake sensor instance.", e);
        }
    }
}
