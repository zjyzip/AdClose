package com.close.hook.ads.hook.gc;

import android.hardware.Sensor;
import android.hardware.SensorManager;

import com.close.hook.ads.hook.util.HookUtil;

public class DisableShakeAd {

    public static void handle() {
        HookUtil.hookAllMethods(SensorManager.class, "registerListener", "before", param -> {
            if (param.args != null && param.args.length >= 2 && param.args[1] instanceof Sensor) {
                Sensor sensor = (Sensor) param.args[1];
                if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    param.setResult(true);
                }
            }
        });
    }
}
