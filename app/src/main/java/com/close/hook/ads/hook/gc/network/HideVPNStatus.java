package com.close.hook.ads.hook.gc.network;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.Random;
import java.util.regex.Pattern;

import com.close.hook.ads.hook.util.HookUtil;

public class HideVPNStatus {

    public static void proxy() {
        HookUtil.hookAllMethods(System.class, "getProperty", "before", param -> {
            String property = (String) param.args[0];
            if (property.equals("http.proxyHost") || property.equals("http.proxyPort")) {
                param.setResult("-1");
            }
        });

        HookUtil.hookAllMethods(System.class, "setProperty", "before", param -> {
            String property = (String) param.args[0];
            if (property.equals("http.proxyHost") || property.equals("http.proxyPort")) {
                param.setResult("");
            }
        });

        HookUtil.hookAllMethods(NetworkInfo.class, "getType", "before", param -> {
            if (param.getResult().equals(ConnectivityManager.TYPE_VPN)) {
                param.setResult(ConnectivityManager.TYPE_WIFI);
            }
        });

        HookUtil.hookAllMethods(NetworkInfo.class, "getTypeName", "before", param -> {
            if (param.getResult().equals("VPN")) {
                param.setResult("WIFI");
            }
        });

        HookUtil.hookAllMethods(NetworkInfo.class, "getSubtypeName", "before", param -> {
            if (param.getResult().equals("VPN")) {
                param.setResult("WIFI");
            }
        });

        HookUtil.hookAllMethods(ConnectivityManager.class, "getNetworkInfo", "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof Integer && (int) param.args[0] == ConnectivityManager.TYPE_VPN) {
                param.setResult(null);
            }
        });

        HookUtil.hookAllMethods(NetworkCapabilities.class, "hasTransport", "before", param -> {
            if (param.args[0].equals(NetworkCapabilities.TRANSPORT_VPN)) {
                param.setResult(false);
            }
        });

        HookUtil.hookAllMethods(NetworkCapabilities.class, "hasCapability", "before", param -> {
            if (param.args[0].equals(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                param.setResult(true);
            }
        });

        HookUtil.hookAllMethods(NetworkInterface.class, "isVirtual", "before", param -> param.setResult(false));
        HookUtil.hookAllMethods(NetworkInterface.class, "isUp", "before", param -> {
            NetworkInterface networkInterface = (NetworkInterface) param.thisObject;
            if (networkInterface != null && Pattern.compile("^(tun[0-9]+|ppp[0-9]+)").matcher(networkInterface.getName()).matches()) {
                param.setResult(false);
            }
        });

        HookUtil.hookAllMethods(NetworkInterface.class, "getName", "before", param -> {
            String originalName = (String) param.getResult();
            if (originalName != null && Pattern.compile("^(tun[0-9]+|ppp[0-9]+)").matcher(originalName).matches()) {
                param.setResult(getRandomString(originalName.length()));
            }
        });
    }

    private static String getRandomString(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
