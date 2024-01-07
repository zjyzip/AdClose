package com.close.hook.ads.hook.gc.network;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.close.hook.ads.data.model.BlockedRequest;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class RequestHook {
	private static final String LOG_PREFIX = "[RequestHook] ";
	private static final Set<String> BLOCKED_LISTS = ConcurrentHashMap.newKeySet(); // 包含策略
	private static final CountDownLatch loadDataLatch = new CountDownLatch(1);
	private static final Cache<String, Boolean> urlBlockCache = CacheBuilder.newBuilder()
	        .maximumSize(10000) // 设置缓存的最大容量
			.expireAfterWrite(10, TimeUnit.MINUTES) // 设置缓存在写入10分钟后失效
			.build();

	static {
		loadBlockedListsAsync();
	}

	public static void init() {
		try {
			setupDNSRequestHook();
			setupHttpConnectionHook();
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
		}
	}

	private static void waitForDataLoading() {
		try {
			loadDataLatch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			XposedBridge.log(LOG_PREFIX + "Interrupted while waiting for data loading");
		}
	}

	private static void setupDNSRequestHook() {
		XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, InetAddressHook);
		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);
	}

	private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockDnsRequest(host)) {
				String methodName = param.method.getName();
				if ("getByName".equals(methodName)) {
					param.setResult(null);
				} else if ("getAllByName".equals(methodName)) {
					param.setResult(new InetAddress[0]);
				}
				return;
			}
		}
	};

	private static void setupHttpConnectionHook() {
		try {
			ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
			Class<?> httpURLConnectionImplClass = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl",
					true, systemClassLoader);

			XC_MethodHook httpConnectionHook = new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
					URL url = httpURLConnection.getURL();
					if (shouldBlockHttpsRequest(url)) {
						BlockedURLConnection blockedConnection = new BlockedURLConnection(url);
						if ("getInputStream".equals(param.method.getName())) {
							param.setResult(blockedConnection.getInputStream());
						} else if ("getOutputStream".equals(param.method.getName())) {
							param.setResult(blockedConnection.getOutputStream());
						}
					}
				}
			};

			XposedHelpers.findAndHookMethod(httpURLConnectionImplClass, "getInputStream", httpConnectionHook);
			XposedHelpers.findAndHookMethod(httpURLConnectionImplClass, "getOutputStream", httpConnectionHook);

		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
		}
	}

	private static void loadBlockedListsAsync() {
		loadListAsync("/assets/blocked_lists.txt", "Error loading blocked lists");
	}

	@SuppressLint("CheckResult")
	private static void loadListAsync(String resourcePath, String errorMessage) {
		Flowable.create(emitter -> {
			try (InputStream inputStream = RequestHook.class.getResourceAsStream(resourcePath);
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
					emitter.onError(new FileNotFoundException("Resource not found: " + resourcePath));
					return;
				}
				String line;
				while ((line = reader.readLine()) != null) {
					BLOCKED_LISTS.add(line.trim());
					emitter.onNext(line);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io()).observeOn(Schedulers.computation())
				.doFinally(loadDataLatch::countDown).subscribe(item -> {
				}, error -> XposedBridge.log(LOG_PREFIX + errorMessage + ": " + error));
	}

	private static boolean shouldBlockDnsRequest(String host) {
		waitForDataLoading();
		sendBlockedRequestBroadcast("all", " DNS", null, host);

		Boolean isBlocked = urlBlockCache.getIfPresent(host);
		if (isBlocked == null) {
			isBlocked = BLOCKED_LISTS.stream().anyMatch(host::contains);
			urlBlockCache.put(host, isBlocked);
		}

		sendBlockedRequestBroadcast(isBlocked ? "block" : "pass", " DNS", isBlocked, host);
		return isBlocked;
	}

	private static boolean shouldBlockHttpsRequest(URL url) {
		if (url == null) {
			return false;
		}

		waitForDataLoading();
		String fullUrlString = url.toString();

		Boolean isBlocked = urlBlockCache.getIfPresent(fullUrlString);
		if (isBlocked == null) {
			isBlocked = BLOCKED_LISTS.stream().anyMatch(fullUrlString::contains);
			urlBlockCache.put(fullUrlString, isBlocked);
		}

		sendBlockedRequestBroadcast(isBlocked ? "block" : "pass", " HTTPS", isBlocked, fullUrlString);
		return isBlocked;
	}

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
			Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
		}

	}

}
