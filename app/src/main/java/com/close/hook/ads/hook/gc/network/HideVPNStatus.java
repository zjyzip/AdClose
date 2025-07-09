package com.close.hook.ads.hook.gc.network;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.regex.Pattern;

import com.close.hook.ads.hook.util.HookUtil;

public class HideVPNStatus {

    public static void proxy() {
        bypassSystemProxyCheck();
        modifyNetworkInfo();
        modifyNetworkCapabilities();
        modifyNetworkInterface();
    }

    private static void bypassSystemProxyCheck() {
        hookSystemProperty("http.proxyHost", "");
        hookSystemProperty("http.proxyPort", "-1");
    }

    private static void hookSystemProperty(String propertyName, String value) {
        HookUtil.hookAllMethods(System.class, "getProperty", "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof String && propertyName.equals(param.args[0])) {
                param.setResult(value);
            }
        });
        HookUtil.hookAllMethods(System.class, "setProperty", "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof String && propertyName.equals(param.args[0])) {
                param.setResult(null);
            }
        });
    }

    private static void modifyNetworkInfo() {
        hookNetworkInfoProperty("getType", ConnectivityManager.TYPE_VPN, ConnectivityManager.TYPE_WIFI);
        hookNetworkInfoProperty("getTypeName", "VPN", "WIFI");
        hookNetworkInfoProperty("getSubtypeName", "VPN", "WIFI");
        
        HookUtil.hookAllMethods(ConnectivityManager.class, "getNetworkInfo", "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof Integer && (int) param.args[0] == ConnectivityManager.TYPE_VPN) {
                param.setResult(null);
            }
        });
    }

    private static void hookNetworkInfoProperty(String methodName, Object targetValue, Object replacementValue) {
        HookUtil.hookAllMethods(NetworkInfo.class, methodName, "before", param -> {
            if (param.getResult() != null && targetValue.equals(param.getResult())) {
                param.setResult(replacementValue);
            }
        });
    }

    private static void modifyNetworkCapabilities() {
        hookNetworkCapabilitiesBoolean("hasTransport", NetworkCapabilities.TRANSPORT_VPN, false);
        hookNetworkCapabilitiesBoolean("hasCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN, true);
    }

    private static void hookNetworkCapabilitiesBoolean(String methodName, int capabilityOrTransport, boolean resultValue) {
        HookUtil.hookAllMethods(NetworkCapabilities.class, methodName, "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof Integer && (int) param.args[0] == capabilityOrTransport) {
                param.setResult(resultValue);
            }
        });
    }

    private static void modifyNetworkInterface() {
        Pattern vpnInterfacePattern = Pattern.compile("^(tun[0-9]+|ppp[0-9]+)");

        HookUtil.hookAllMethods(NetworkInterface.class, "isVirtual", "before", param -> param.setResult(false));

        HookUtil.hookAllMethods(NetworkInterface.class, "isUp", "before", param -> {
            if (param.thisObject instanceof NetworkInterface) {
                NetworkInterface networkInterface = (NetworkInterface) param.thisObject;
                if (vpnInterfacePattern.matcher(networkInterface.getName()).matches()) {
                    param.setResult(false);
                }
            }
        });

        HookUtil.hookAllMethods(NetworkInterface.class, "getName", "before", param -> {
            if (param.getResult() instanceof String) {
                String originalName = (String) param.getResult();
                if (originalName != null && vpnInterfacePattern.matcher(originalName).matches()) {
                    param.setResult("");
                }
            }
        });
    }
}
