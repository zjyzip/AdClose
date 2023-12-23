package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.core.BackpressureStrategy;

import java.net.*;

import android.util.Log;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.content.pm.PackageManager;

import com.close.hook.ads.data.model.BlockedRequest;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HostHook {
	private static final String LOG_PREFIX = "[HostHook] ";
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_HOSTS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_FullURL = new ConcurrentHashMap<>();
	private static final CountDownLatch loadDataLatch = new CountDownLatch(2);

	static {
		loadBlockedHostsAsync();
		loadBlockedFullURLAsync();
	}

	public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
		try {
			setupDNSRequestHook();
			setupHttpConnectionHook(lpparam);
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
		}
	}

	private static void setupDNSRequestHook() {
		XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, InetAddress2Hook);
		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddress1Hook);
	}

	private static void setupHttpConnectionHook(XC_LoadPackage.LoadPackageParam lpparam) {
		try {
			XposedHelpers.findAndHookMethod("com.android.okhttp.internal.huc.HttpURLConnectionImpl",
					lpparam.classLoader, "getInputStream", new XC_MethodHook() { // 待调整(getOutputStream)
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
							URL url = httpURLConnection.getURL();
							String host = url.getHost();

							if (url != null && shouldBlockHttpsRequest(url)) {
								param.setResult(new BlockedURLConnection(url));
							} else if (host != null && shouldBlockHttpsRequest(host)) {
								param.setResult(new BlockedURLConnection(url));
							}
						}
					});
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
		}
	}

	@SuppressLint("CheckResult")
	private static void loadBlockedHostsAsync() {
		Flowable.create(emitter -> {
			try (InputStream inputStream = HostHook.class.getResourceAsStream("/assets/blocked_hosts.txt");
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
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
		}, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io()).observeOn(Schedulers.computation())
				.doFinally(loadDataLatch::countDown).subscribe(host -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + error));
	}

	@SuppressLint("CheckResult")
	private static void loadBlockedFullURLAsync() {
		Flowable.create(emitter -> {
			try (InputStream inputStream = HostHook.class.getResourceAsStream("/assets/blocked_full_urls.txt");
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
					return;
				}
				String url;
				while ((url = reader.readLine()) != null) {
					BLOCKED_FullURL.put(url.trim(), Boolean.TRUE);
					emitter.onNext(url);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io()).observeOn(Schedulers.computation())
				.doFinally(loadDataLatch::countDown).subscribe(url -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked full URLs: " + error));
	}

	private static void waitForDataLoading() {
		if (BLOCKED_HOSTS.isEmpty() && BLOCKED_FullURL.isEmpty()) {
			try {
				loadDataLatch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				XposedBridge.log(LOG_PREFIX + "Interrupted while waiting for data loading");
			}
		}
	}

	private static boolean shouldBlockDnsRequest(String host) {
		return shouldBlockRequest(host, "DNS", BLOCKED_HOSTS);
	}

	private static boolean shouldBlockHttpsRequest(String host) {
		return shouldBlockRequest(host, " HTTPS-host", BLOCKED_HOSTS);
	}

	private static boolean shouldBlockRequest(String host, String requestType,
			ConcurrentHashMap<String, Boolean> blockedList) {
		sendBlockedRequestBroadcast("all", requestType, null, host);
		if (host == null) {
			return false;
		}
		waitForDataLoading();
		boolean isBlocked = blockedList.containsKey(host);
		if (isBlocked) {
			sendBlockedRequestBroadcast("block", requestType, true, host);
		} else {
			sendBlockedRequestBroadcast("pass", requestType, false, host);
		}
		return isBlocked;
	}

	private static boolean shouldBlockHttpsRequest(URL url) {
		if (url == null) {
			return false;
		}

		String host = url.getHost();
		boolean alreadyBlockedByHost = shouldBlockHttpsRequest(host);

		if (alreadyBlockedByHost) {
			return true;
		}

		String baseUrlString;
		try {
			baseUrlString = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath()).toExternalForm();
		} catch (MalformedURLException e) {
			XposedBridge.log(LOG_PREFIX + "Malformed URL: " + e.getMessage());
			return false;
		}

		if (BLOCKED_FullURL.isEmpty()) {
			waitForDataLoading();
		}

		boolean isBlocked = BLOCKED_FullURL.containsKey(baseUrlString);
		sendBlockedRequestBroadcast("all", " HTTPS-full", null, baseUrlString);
		if (isBlocked) {
			sendBlockedRequestBroadcast("block", " HTTPS-full", true, baseUrlString);
		} else {
			sendBlockedRequestBroadcast("pass", " HTTPS-full", false, baseUrlString);
		}

		return isBlocked;
	}

	private static final XC_MethodHook InetAddress1Hook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockDnsRequest(host)) {
				param.setResult(new InetAddress[0]);
				return;
			}
		}
	};

	private static final XC_MethodHook InetAddress2Hook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockDnsRequest(host)) {
				param.setResult(InetAddress.getByAddress(new byte[4]));
				return;
			}
		}
	};

	private static void sendBlockedRequestBroadcast(String type, @Nullable String requestType,
			@Nullable Boolean isBlocked, String request) {
		Intent intent;
		if (Objects.equals(type, "all")) {
			intent = new Intent("com.rikkati.ALL_REQUEST");
		} else if (Objects.equals(type, "block")) {
			intent = new Intent("com.rikkati.BLOCKED_REQUEST");
		} else {
			intent = new Intent("com.rikkati.PASS_REQUEST");
		}

		Context currentContext = AndroidAppHelper.currentApplication();
		if (currentContext != null) {
			PackageManager pm = currentContext.getPackageManager();
			String appName;
			try {
				appName = pm
						.getApplicationLabel(
								pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA))
						.toString();
			} catch (PackageManager.NameNotFoundException e) {
				appName = currentContext.getPackageName();

			}
			appName += requestType;
			String packageName = currentContext.getPackageName();
			BlockedRequest blockedRequest = new BlockedRequest(appName, packageName, request,
					System.currentTimeMillis(), type, isBlocked);

			intent.putExtra("request", blockedRequest);
			AndroidAppHelper.currentApplication().sendBroadcast(intent);
		} else {
			Log.w("HostHook", "sendBlockedRequestBroadcast: currentContext is null");
		}

	}

}
