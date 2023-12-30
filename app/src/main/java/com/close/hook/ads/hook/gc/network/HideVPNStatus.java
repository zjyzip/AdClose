package com.close.hook.ads.hook.gc.network;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class HideVPNStatus {

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

	private static void hookMethod(Class<?> clazz, String methodName, Consumer<XC_MethodHook.MethodHookParam> action) {
		try {
			XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					action.accept(param);
				}
			});
		} catch (Throwable e) {
			XposedBridge.log("HideVPNStatus - Error occurred while hooking " + clazz.getSimpleName() + "." + methodName
					+ ": " + e.getMessage());
		}
	}

	private static void bypassSystemProxyCheck() {
		hookMethod(System.class, "getProperty", param -> {
			if ("http.proxyHost".equals(param.args[0]) || "http.proxyPort".equals(param.args[0])) {
				param.setResult(null);
			}
		});
	}

	public static void proxy() {

		bypassSystemProxyCheck();

		hookMethod(NetworkInfo.class, "getType",
				param -> replaceResultIfEquals(param, ConnectivityManager.TYPE_VPN, ConnectivityManager.TYPE_WIFI));
		hookMethod(NetworkInfo.class, "getSubtype",
				param -> replaceResultIfEquals(param, ConnectivityManager.TYPE_VPN, ConnectivityManager.TYPE_WIFI));
		hookMethod(NetworkInfo.class, "getTypeName", param -> replaceResultIfEqualsIgnoreCase(param, "VPN", "WIFI"));
		hookMethod(NetworkInfo.class, "getSubtypeName", param -> replaceResultIfEqualsIgnoreCase(param, "VPN", "WIFI"));
		hookMethod(NetworkCapabilities.class, "hasTransport", param -> {
			if (Objects.equals(param.args[0], NetworkCapabilities.TRANSPORT_VPN)) {
				param.setResult(false);
			}
		});
		hookMethod(NetworkCapabilities.class, "hasCapability", param -> {
			if (Objects.equals(param.args[0], NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
				param.setResult(true);
			}
		});
		hookMethod(NetworkCapabilities.class, "getCapabilities", param -> {
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
		hookMethod(ConnectivityManager.class, "getNetworkInfo", param -> {
			if (Optional.ofNullable(param.args[0]).orElse(-1).equals(ConnectivityManager.TYPE_VPN)) {
				param.setResult(null);
			}
		});
		hookMethod(NetworkInterface.class, "isVirtual", param -> param.setResult(false));
		hookMethod(NetworkInterface.class, "getName", param -> replaceResultIfStartsWith(param, "tun", "ppp", "pptp"));
	}
}
