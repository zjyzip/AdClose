package com.close.hook.ads.hook.gc.network;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.close.hook.ads.hook.util.HookUtil;

public class HideVPNStatus {
    private static final String LOG_PREFIX = "[HideVPNStatus] ";
    private static final String WIFI = "WIFI";
    private static final String EMPTY_STRING = "";
    private static final String DEFAULT_PROXY_PORT = "-1";

    public static void proxy() {
        bypassSystemProxyCheck();
        modifyNetworkInfo();
        modifyNetworkCapabilities();
    }

    private static void replaceResultIfEquals(XC_MethodHook.MethodHookParam param, Object compareTo, Object newValue) {
        if (param.getResult() != null && param.getResult().equals(compareTo)) {
            param.setResult(newValue);
        }
    }

    private static void replaceResultIfEqualsIgnoreCase(XC_MethodHook.MethodHookParam param, String compareTo, String newValue) {
        if (param.getResult() instanceof String && ((String) param.getResult()).equalsIgnoreCase(compareTo)) {
            param.setResult(newValue);
        }
    }

    private static void replaceResultIfStartsWith(XC_MethodHook.MethodHookParam param, String... prefixes) {
        if (param.getResult() instanceof String) {
            String result = (String) param.getResult();
            for (String prefix : prefixes) {
                if (result.startsWith(prefix)) {
                    param.setResult(getRandomString(result.length()));
                    break;
                }
            }
        }
    }

    private static String getRandomString(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static void bypassSystemProxyCheck() {
        HookUtil.hookAllMethods(System.class, "getProperty", param -> {
            String propertyName = (String) param.args[0];
            if ("http.proxyHost".equals(propertyName)) {
                param.setResult(EMPTY_STRING);
            } else if ("http.proxyPort".equals(propertyName)) {
                param.setResult(DEFAULT_PROXY_PORT);
            }
        });

        HookUtil.hookAllMethods(System.class, "setProperty", param -> {
            String propertyName = (String) param.args[0];
            if ("http.proxyHost".equals(propertyName) || "http.proxyPort".equals(propertyName)) {
                param.setResult(EMPTY_STRING);
            }
        });
    }

    private static void modifyNetworkInfo() {
        HookUtil.hookAllMethods(NetworkInfo.class, "getType", param -> 
            replaceResultIfEquals(param, ConnectivityManager.TYPE_VPN, ConnectivityManager.TYPE_WIFI)
        );

        HookUtil.hookAllMethods(NetworkInfo.class, "getTypeName", param -> 
            replaceResultIfEqualsIgnoreCase(param, "VPN", WIFI)
        );

        HookUtil.hookAllMethods(NetworkInfo.class, "getSubtypeName", param -> 
            replaceResultIfEqualsIgnoreCase(param, "VPN", WIFI)
        );

        HookUtil.hookAllMethods(ConnectivityManager.class, "getNetworkInfo", param -> {
            if (param.args.length > 0 && param.args[0].equals(ConnectivityManager.TYPE_VPN)) {
                param.setResult(null);
            }
        });
    }

    private static void modifyNetworkCapabilities() {
        HookUtil.hookAllMethods(NetworkCapabilities.class, "hasTransport", param -> {
            if (param.args[0].equals(NetworkCapabilities.TRANSPORT_VPN)) {
                param.setResult(false);
            }
        });

        HookUtil.hookAllMethods(NetworkCapabilities.class, "hasCapability", param -> {
            if (param.args[0].equals(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                param.setResult(true);
            }
        });

        HookUtil.hookAllMethods(NetworkInterface.class, "isVirtual", param -> 
            param.setResult(false)
        );

        HookUtil.hookAllMethods(NetworkInterface.class, "getName", param -> 
            replaceResultIfStartsWith(param, "tun", "ppp", "pptp")
        );
    }
}
