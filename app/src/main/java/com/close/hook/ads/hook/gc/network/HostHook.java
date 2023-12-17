package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.net.*;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.webkit.*;

import com.close.hook.ads.data.module.BlockedRequest;

import de.robv.android.xposed.*;

public class HostHook {
    private static final String LOG_PREFIX = "[HostHook] ";
    private static final ConcurrentHashMap<String, Boolean> BLOCKED_HOSTS = new ConcurrentHashMap<>();
	private static final CountDownLatch loadDataLatch = new CountDownLatch(1);

	static {
		loadBlockedHostsAsync();
	}

    public static void init() {
        try {
            hookAllRelevantMethods();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
        }
    }

	@SuppressLint("CheckResult")
	private static void loadBlockedHostsAsync() {
		Flowable.<String>create(emitter -> {
			try (InputStream inputStream = HostHook.class.getResourceAsStream("/assets/blocked_hosts.txt");
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
					emitter.onError(new FileNotFoundException("Blocked hosts list not found"));
					return;
				}

				String host;
				while ((host = reader.readLine()) != null) {
					BLOCKED_HOSTS.put(host, Boolean.TRUE);
					emitter.onNext(host);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io())
		.observeOn(Schedulers.computation())
		.doFinally(loadDataLatch::countDown)
	    .subscribe(
	        host -> {},
      		error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + error));
	}

	private static void waitForDataLoading() {
		try {
			loadDataLatch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			XposedBridge.log(LOG_PREFIX + "Interrupted while waiting for data loading");
		}
	}

    private static boolean shouldBlockRequest(String host) {
        if (host == null) {
            return false;
        }

        waitForDataLoading();
		sendBlockedRequestBroadcast("all", host);
        return BLOCKED_HOSTS.containsKey(host);
    }

    private static boolean isSocketConnection(XC_MethodHook.MethodHookParam param) {
        return param.thisObject instanceof Socket ||
            (param.args.length > 0 && param.args[0] instanceof SocketAddress);
    }

	private static final XC_MethodHook blockHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = HostExtractor.extractHostFromParam(param);
			if (host != null && shouldBlockRequest(host)) {
                if (isSocketConnection(param)) {
					sendBlockedRequestBroadcast("block", host);
                    param.setThrowable(new SocketException("Socket blocked by HostHook"));
                } else {
					sendBlockedRequestBroadcast("pass", host);
		     		param.setResult(null);
                }
			}
		}
	};

	private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("block", host);
				param.setResult(new InetAddress[0]);
				return;
			} else if (host != null && !shouldBlockRequest(host)){
				sendBlockedRequestBroadcast("pass", host);
			}
		}
	};

	private static void hookAllRelevantMethods() {

		XposedHelpers.findAndHookConstructor(URL.class, String.class, blockHook);

		XposedHelpers.findAndHookMethod(Socket.class, "connect", SocketAddress.class, int.class, blockHook);

		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);

		XposedBridge.hookAllMethods(WebView.class, "loadUrl", blockHook);
		XposedBridge.hookAllMethods(WebView.class, "postUrl", blockHook);

	}

	private static class HostExtractor {

		static String extractHostFromParam(XC_MethodHook.MethodHookParam param) {

			if (param.args == null || param.args.length == 0) {
				return null;

			}

			Object arg = param.args[0];

			if (arg instanceof String) {
				return extractHostFromString((String) arg);

			} else if (arg instanceof URL) {
				return ((URL) arg).getHost();

			} else if (arg instanceof InetSocketAddress) {
				return ((InetSocketAddress) arg).getHostName();

			} else if (arg instanceof WebResourceRequest) {
				return ((WebResourceRequest) arg).getUrl().getHost();

			} else {
				XposedBridge.log(LOG_PREFIX + "Unhandled argument type: " + arg.getClass().getName());
				return null;
			}
		}

		static String extractHostFromString(String urlString) {
			try {
				URI uri = new URI(urlString);
				return uri.getHost();
			} catch (URISyntaxException e) {
				XposedBridge.log(LOG_PREFIX + "Invalid URI: " + urlString);
				return null;
			}
		}

	}

	private static void sendBlockedRequestBroadcast(String type, String request) {
		Intent intent;
		if (Objects.equals(type, "all")) {
			intent = new Intent("com.rikkati.ALL_REQUEST");
		} else if (Objects.equals(type, "block")) {
			intent = new Intent("com.rikkati.BLOCKED_REQUEST");
		} else {
			intent = new Intent("com.rikkati.PASS_REQUEST");
		}

		Context currentContext = AndroidAppHelper.currentApplication();
		PackageManager pm = currentContext.getPackageManager();
		String appName;
		try {
			appName = pm
					.getApplicationLabel(
							pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA))
					.toString();
		} catch (PackageManager.NameNotFoundException e) {
			appName = currentContext.getPackageName(); // 使用包名作为备选名称

		}
		String packageName = currentContext.getPackageName();
		BlockedRequest blockedRequest = new BlockedRequest(appName, packageName, request,
				System.currentTimeMillis());

		intent.putExtra("request", blockedRequest);
		AndroidAppHelper.currentApplication().sendBroadcast(intent);
	}

}
