package com.close.hook.ads.hook.gc;

import android.hardware.SensorManager;
import com.close.hook.ads.hook.util.HookUtil;

public class DisableShakeAd {

    public static void handle() {
        HookUtil.hookAllMethods(SensorManager.class, "registerListener", param -> {
            // 拦截加速度传感器的注册
            if (param.args != null && param.args.length >= 2 && param.args[1] instanceof android.hardware.Sensor) {
                android.hardware.Sensor sensor = (android.hardware.Sensor) param.args[1];
                if (sensor.getType() == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                    // 如果是加速度传感器，取消监听器的注册
                    param.setResult(false);
                }
            }
        });
    }
}
