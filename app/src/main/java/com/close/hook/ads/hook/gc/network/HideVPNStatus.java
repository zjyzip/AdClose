package com.close.hook.ads.hook.gc.network;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;

import com.close.hook.ads.hook.util.HookUtil;

public class HideVPNStatus {

	private static final String VPN = "VPN";
	private static final String WIFI = "WIFI";
	private static final String HTTP_PROXY_HOST = "http.proxyHost";
	private static final String HTTP_PROXY_PORT = "http.proxyPort";
	private static final String EMPTY_STRING = "";
	private static final String DEFAULT_PROXY_PORT = "-1";

	private static void replaceResultIfEquals(XC_MethodHook.MethodHookParam param, Object compareTo, Object newValue) {
		if (Objects.equals(Optional.ofNullable(param.getResult()).orElse(-1), compareTo)) {
			param.setResult(newValue);
		}
	}

	private static void replaceResultIfEqualsIgnoreCase(XC_MethodHook.MethodHookParam param, String compareTo,
			String newValue) {
		if (Optional.ofNullable(param.getResult()).map(Object::toString).orElse("").equalsIgnoreCase(compareTo)) {
			param.setResult(newValue);
		}
	}

	private static void replaceResultIfStartsWith(XC_MethodHook.MethodHookParam param, String... prefixes) {
		String name = Optional.ofNullable(param.getResult()).map(Object::toString).orElse("");
		for (String prefix : prefixes) {
			if (name.startsWith(prefix)) {
				param.setResult(getRandomString(name.length()));
				break;
			}
		}
	}

	private static String getRandomString(int length) {
		String alphabet = "abcdefghijklmnopqrstuvwxyz";
		StringBuilder result = new StringBuilder(length);
		Random random = new Random();
		for (int i = 0; i < length; i++) {
			result.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return result.toString();
	}

	private static void bypassSystemProxyCheck() {
		HookUtil.hookAllMethods(System.class, "getProperty", param -> {
			String propertyName = (String) param.args[0];
			if (HTTP_PROXY_HOST.equals(propertyName)) {
				param.setResult(EMPTY_STRING);
			} else if (HTTP_PROXY_PORT.equals(propertyName)) {
				param.setResult(DEFAULT_PROXY_PORT);
			}
		});

		HookUtil.hookAllMethods(System.class, "setProperty", param -> {
			String propertyName = (String) param.args[0];
			if (HTTP_PROXY_HOST.equals(propertyName)) {
				param.args[1] = EMPTY_STRING;
			} else if (HTTP_PROXY_PORT.equals(propertyName)) {
				param.args[1] = DEFAULT_PROXY_PORT;
			}
		});
	}

	public static void proxy() {
		bypassSystemProxyCheck();
		modifyNetworkInfo();
		modifyNetworkCapabilities();
	}

	private static void modifyNetworkInfo() {
		HookUtil.hookAllMethods(NetworkInfo.class, "getType",
				param -> replaceResultIfEquals(param, ConnectivityManager.TYPE_VPN, ConnectivityManager.TYPE_WIFI));
		HookUtil.hookAllMethods(NetworkInfo.class, "getSubtype",
				param -> replaceResultIfEquals(param, ConnectivityManager.TYPE_VPN, ConnectivityManager.TYPE_WIFI));
		HookUtil.hookAllMethods(NetworkInfo.class, "getTypeName",
				param -> replaceResultIfEqualsIgnoreCase(param, VPN, WIFI));
		HookUtil.hookAllMethods(NetworkInfo.class, "getSubtypeName",
				param -> replaceResultIfEqualsIgnoreCase(param, VPN, WIFI));
		HookUtil.hookAllMethods(ConnectivityManager.class, "getNetworkInfo", param -> {
			if (Objects.equals(Optional.ofNullable(param.args[0]).orElse(-1), ConnectivityManager.TYPE_VPN)) {
				param.setResult(null);
			}
		});
	}

	private static void modifyNetworkCapabilities() {
		HookUtil.hookAllMethods(NetworkCapabilities.class, "hasTransport", param -> {
			if (Objects.equals(param.args[0], NetworkCapabilities.TRANSPORT_VPN)) {
				param.setResult(false);
			}
		});
		HookUtil.hookAllMethods(NetworkCapabilities.class, "hasCapability", param -> {
			if (Objects.equals(param.args[0], NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
				param.setResult(true);
			}
		});
		HookUtil.hookAllMethods(NetworkCapabilities.class, "getCapabilities", param -> {
			Optional.ofNullable(param.getResult()).ifPresent(resultObj -> {
				int[] result = (int[]) resultObj;
				if (result.length > 0
						&& Arrays.stream(result).noneMatch(c -> c == NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
					int[] newResult = Arrays.copyOf(result, result.length + 1);
					newResult[result.length] = NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
					param.setResult(newResult);
				}
			});
		});
		HookUtil.hookAllMethods(NetworkInterface.class, "isVirtual", param -> param.setResult(false));
		HookUtil.hookAllMethods(NetworkInterface.class, "getName",
				param -> replaceResultIfStartsWith(param, "tun", "ppp", "pptp"));
	}

}
