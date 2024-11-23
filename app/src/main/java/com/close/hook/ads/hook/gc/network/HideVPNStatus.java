package com.close.hook.ads.hook.gc.network;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.Random;
import java.util.regex.Pattern;

import com.close.hook.ads.hook.util.HookUtil;

public class HideVPNStatus {
    private static final String LOG_PREFIX = "[HideVPNStatus] ";
    private static final String EMPTY_STRING = "";
    private static final String DEFAULT_PROXY_PORT = "-1";
    private static final Pattern VPN_INTERFACE = Pattern.compile("^(tun\\d*|ppp\\d*)");

    public static void proxy() {
        bypassSystemProxyCheck();
        modifyNetworkInfo();
        modifyNetworkCapabilities();
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
        HookUtil.hookAllMethods(System.class, "getProperty", "before", param -> {
            String propertyName = (String) param.args[0];
            if ("http.proxyHost".equals(propertyName)) {
                param.setResult(EMPTY_STRING);
            } else if ("http.proxyPort".equals(propertyName)) {
                param.setResult(DEFAULT_PROXY_PORT);
            }
        });

        HookUtil.hookAllMethods(System.class, "setProperty", "before", param -> {
            String propertyName = (String) param.args[0];
            if ("http.proxyHost".equals(propertyName) || "http.proxyPort".equals(propertyName)) {
                param.setResult(EMPTY_STRING);
            }
        });
    }

    private static void modifyNetworkInfo() {
        HookUtil.hookAllMethods(NetworkInfo.class, "getType", "before", param -> {
            Object result = param.getResult();
            if (result instanceof Integer && (int) result == ConnectivityManager.TYPE_VPN) {
                param.setResult(ConnectivityManager.TYPE_WIFI);
            }
        });

        HookUtil.hookAllMethods(NetworkInfo.class, "getTypeName", "before", param -> {
            Object result = param.getResult();
            if (result instanceof String && "VPN".equalsIgnoreCase((String) result)) {
                param.setResult("WIFI");
            }
        });

        HookUtil.hookAllMethods(NetworkInfo.class, "getSubtypeName", "before", param -> {
            Object result = param.getResult();
            if (result instanceof String && "VPN".equalsIgnoreCase((String) result)) {
                param.setResult("WIFI");
            }
        });

        HookUtil.hookAllMethods(ConnectivityManager.class, "getNetworkInfo", "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof Integer && (int) param.args[0] == ConnectivityManager.TYPE_VPN) {
                param.setResult(null);
            }
        });
    }

    private static void modifyNetworkCapabilities() {
        HookUtil.hookAllMethods(NetworkCapabilities.class, "hasTransport", "before", param -> {
            Object arg = param.args[0];
            if (arg instanceof Integer && (int) arg == NetworkCapabilities.TRANSPORT_VPN) {
                param.setResult(false);
            }
        });

        HookUtil.hookAllMethods(NetworkCapabilities.class, "hasCapability", "before", param -> {
            Object arg = param.args[0];
            if (arg instanceof Integer && (int) arg == NetworkCapabilities.NET_CAPABILITY_NOT_VPN) {
                param.setResult(true);
            }
        });

        HookUtil.hookAllMethods(NetworkInterface.class, "isVirtual", "before", param -> 
            param.setResult(false)
        );

        HookUtil.hookAllMethods(NetworkInterface.class, "isUp", "before", param -> {
            NetworkInterface networkInterface = (NetworkInterface) param.thisObject;
            String interfaceName = networkInterface.getName();
            if (interfaceName != null && VPN_INTERFACE.matcher(interfaceName).matches()) {
                param.setResult(false);
            }
        });

        HookUtil.hookAllMethods(NetworkInterface.class, "getName", "before", param -> {
            String originalName = (String) param.getResult();
            if (originalName != null && VPN_INTERFACE.matcher(originalName).matches()) {
                param.setResult(getRandomString(originalName.length()));
            }
        });
    }
}
