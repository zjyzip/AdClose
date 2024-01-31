package com.close.hook.ads.hook.gc.network;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class OkHttpInterceptorHook {

    private static final String LOG_PREFIX = "[OkHttpInterceptorHook] ";

    public static void handle(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("okhttp3.internal.http.CallServerInterceptor", lpparam.classLoader, "intercept", "okhttp3.Interceptor.Chain", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // 获取请求对象
                    Object chain = param.args[0];
                    Object request = XposedHelpers.callMethod(chain, "request");

                    // 获取并打印请求信息
                    String url = XposedHelpers.callMethod(request, "url").toString();
                    String method = (String) XposedHelpers.callMethod(request, "method");
                    Object requestHeaders = XposedHelpers.callMethod(request, "headers");

                    logFormatted("Request URL", url);
                    logFormatted("Request Method", method);
                    logFormatted("Request Headers", requestHeaders.toString());

                    // 获取并打印响应对象
                    Object response = param.getResult();
                    int code = (int) XposedHelpers.callMethod(response, "code");
                    String message = (String) XposedHelpers.callMethod(response, "message");
                    Object responseHeaders = XposedHelpers.callMethod(response, "headers");

                    logFormatted("Response Code", String.valueOf(code));
                    logFormatted("Response Message", message);
                    logFormatted("Response Headers", responseHeaders.toString());

                }

                private void logFormatted(String label, String content) {
                    XposedBridge.log(LOG_PREFIX + String.format("%-20s: %s", label, content));
                }
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "OkHttp Hooking error: " + e.getMessage());
        }
    }
}
