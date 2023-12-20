package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

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

public class HostHook {
	private static final String LOG_PREFIX = "[HostHook] ";
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_HOSTS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_FullURL = new ConcurrentHashMap<>();
	private static final CountDownLatch loadDataLatch = new CountDownLatch(2);

	static {
		setupURLHook();
		setupFullURLHook();
		loadBlockedHostsAsync();
		loadBlockedFullURLAsync();
	}

	public static void init() {
		try {
			hookAllRelevantMethods();
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
		}
	}

	private static void setupURLHook() {
		try {
			XposedHelpers.findAndHookMethod(URL.class, "openConnection", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					URL url = (URL) param.thisObject;
					String host = url.getHost();
					if (host != null && shouldBlockRequest(host)) {
						sendBlockedRequestBroadcast("block", " setupURLHook", null, url.toString());
						param.setResult(new BlockedURLConnection(url));
					} else if (host != null && !shouldBlockRequest(host)) {
						sendBlockedRequestBroadcast("pass", null, null, url.toString());
					}
				}
			});
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error setting up URL proxy: " + e.getMessage());
		}
	}

	private static void setupFullURLHook() {
		try {
			XposedHelpers.findAndHookMethod(URL.class, "openConnection", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					URL url = (URL) param.thisObject;
					if (shouldBlockRequest(url)) {
						XposedBridge.log("Has been blocked" + url);
						sendBlockedRequestBroadcast("block", "setupFullURLHook", null, url.toString());
						param.setResult(new BlockedURLConnection(url));
					} else if (url != null && !shouldBlockRequest(url)) {
						sendBlockedRequestBroadcast("pass", null, null, url.toString());
					}
				}
			});
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error setting up Full URL proxy: " + e.getMessage());
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
				.observeOn(Schedulers.computation()).doFinally(loadDataLatch::countDown).subscribe(host -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + error));
	}

	@SuppressLint("CheckResult")
	private static void loadBlockedFullURLAsync() {
		Flowable.<String>create(emitter -> {
			try (InputStream inputStream = HostHook.class.getResourceAsStream("/assets/blocked_full_urls.txt");
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
					emitter.onError(new FileNotFoundException("Blocked full URL list not found"));
					return;
				}

				String url;
				while ((url = reader.readLine()) != null) {
					BLOCKED_FullURL.put(url.trim(), Boolean.TRUE);
					emitter.onNext(url);
					XposedBridge.log(LOG_PREFIX + "Loaded blocked URL: " + url);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io())
				.observeOn(Schedulers.computation()).doFinally(loadDataLatch::countDown).subscribe(url -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked full URLs: " + error));
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
		sendBlockedRequestBroadcast("all", null, BLOCKED_HOSTS.containsKey(host), host);
		return BLOCKED_HOSTS.containsKey(host);
	}

	private static boolean shouldBlockRequest(URL url) {
		if (url == null) {
			return false;
		}
		String urlString = url.toExternalForm();
		String baseUrlString = urlString.split("\\?")[0];
		waitForDataLoading();
		sendBlockedRequestBroadcast("all", null, BLOCKED_FullURL.containsKey(baseUrlString), baseUrlString);
		return BLOCKED_FullURL.containsKey(baseUrlString);
	}

	private static final XC_MethodHook InetAddress1Hook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("block", " InetAddress1Hook", null, host);
				param.setResult(new InetAddress[0]);
				return;
			} else if (host != null && !shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("pass", null, null, host);
			}
		}
	};

	private static final XC_MethodHook InetAddress2Hook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("block", " InetAddress2Hook", null, host);
				param.setResult(InetAddress.getByAddress(new byte[4]));
				return;
			} else if (host != null && !shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("pass", null, null, host);
			}
		}
	};

	private static void hookAllRelevantMethods() {

		XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, InetAddress2Hook);
		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddress1Hook);

	}

	private static void sendBlockedRequestBroadcast(String type, @Nullable String blockType,
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
			if (Objects.equals(type, "block")) {
				appName += blockType;
			}
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