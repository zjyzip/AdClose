package com.close.hook.ads.hook.gc.network;

import android.annotation.SuppressLint;
import androidx.annotation.Nullable;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

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

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;

import java.util.concurrent.TimeUnit;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.schedulers.Schedulers;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;


public class RequestHook {
	private static final String LOG_PREFIX = "[RequestHook] ";
	private static final Set<String> BLOCKED_LISTS = ConcurrentHashMap.newKeySet(); // 包含策略
    private static final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	private static final CountDownLatch loadDataLatch = new CountDownLatch(1);
	private static final Cache<String, Boolean> urlBlockCache = CacheBuilder.newBuilder()
	        .maximumSize(15000)
			.expireAfterAccess(1, TimeUnit.HOURS) // 更改为最后一次访问后1小时过期
			.build();
	@Nullable
	private static String method;
	@Nullable
	private static String urlString;
	@Nullable
	private static String requestHeaders;
	private static int responseCode = -1;
	@Nullable
	private static String responseMessage;
	@Nullable
	private static String responseHeaders;

	static {
		loadBlockedListsAsync();
	}

	public static void init() {
		try {
			setupDNSRequestHook();
			setupHttpRequestHook();
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

	private static boolean shouldBlockRequest(String key) {
		try {
			return urlBlockCache.get(key, () -> {
				boolean isBlocked = BLOCKED_LISTS.stream().anyMatch(key::contains);
				sendBlockedRequestBroadcast(isBlocked ? "block" : "pass", key.contains(":") ? " HTTP(S)" : " DNS",
						isBlocked, key);
				return isBlocked;
			});
		} catch (ExecutionException e) {
			XposedBridge.log(LOG_PREFIX + "Error accessing the cache: " + e.getMessage());
			throw new RuntimeException("Error accessing the cache", e);
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

	private static boolean shouldBlockDnsRequest(String host) {
		waitForDataLoading();
		Boolean shouldBlock = shouldBlockRequest(host);
		sendBlockedRequestBroadcast("all", " DNS", shouldBlock, host);
		return shouldBlock;
	}

	private static void setupHttpConnectionHook() {
		try {
			Class<?> httpURLConnectionImpl = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl");

			XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "getInputStream", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
					URL url = httpURLConnection.getURL();
					if (shouldBlockHttpsRequest(url)) {
						BlockedURLConnection blockedConnection = new BlockedURLConnection(url);
						param.setResult(blockedConnection.getInputStream());
					}
				}
			});

			XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "getOutputStream", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
					URL url = httpURLConnection.getURL();
					if (shouldBlockHttpsRequest(url)) {
						BlockedURLConnection blockedConnection = new BlockedURLConnection(url);
						param.setResult(blockedConnection.getOutputStream());
					}
				}
			});

		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
		}
	}

	private static boolean shouldBlockHttpsRequest(URL url) {
		if (url == null) {
			return false;
		}
		waitForDataLoading();
		String fullUrlString = url.toString();
		Boolean shouldBlock = shouldBlockRequest(fullUrlString);
		sendBlockedRequestBroadcast("all", " HTTP(S)", shouldBlock, fullUrlString);
		return shouldBlock;
	}

    private static void setupHttpRequestHook() {
        try {
            Class<?> httpURLConnectionImpl = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl");
            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "execute", boolean.class, new XC_MethodHook() {

                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    setupHttpConnectionHook();
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    processRequestAsync(param);
                }
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static void processRequestAsync(final XC_MethodHook.MethodHookParam param) {
        executorService.submit(() -> {
            try {
                RequestDetails details = processRequest(param);
                if (details != null) {
                    logRequestDetails(details);
                }
            } catch (Exception e) {
                XposedBridge.log(LOG_PREFIX + "Error processing request: " + e.getMessage());
            }
        });
    }

	private static RequestDetails processRequest(XC_MethodHook.MethodHookParam param) {
		try {
			Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");
			Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
			Object response = XposedHelpers.callMethod(httpEngine, "getResponse");

			String method = (String) XposedHelpers.callMethod(request, "method");
			String urlString = (String) XposedHelpers.callMethod(request, "urlString");
			Object requestHeaders = XposedHelpers.callMethod(request, "headers");

			int code = response != null ? (int) XposedHelpers.callMethod(response, "code") : -1;
			String message = response != null ? (String) XposedHelpers.callMethod(response, "message") : "No response";
			Object responseHeaders = response != null ? XposedHelpers.callMethod(response, "headers")
					: "No response headers";

			return new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders);
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Exception in processing request: " + e.getMessage());
			return null;
		}
	}

	private static void logRequestDetails(RequestDetails details) {
		if (details != null) {
/*			StringBuilder logBuilder = new StringBuilder();
			logBuilder.append(LOG_PREFIX).append("Request Details:\n");
			logBuilder.append("Method: ").append(details.getMethod()).append("\n");
			logBuilder.append("URL: ").append(details.getUrlString()).append("\n");
			logBuilder.append("Request Headers: ").append(details.getRequestHeaders().toString()).append("\n");
			logBuilder.append("Response Code: ").append(details.getResponseCode()).append("\n");
			logBuilder.append("Response Message: ").append(details.getResponseMessage()).append("\n");
			logBuilder.append("Response Headers: ").append(details.getResponseHeaders().toString());
*/
			XposedBridge.log(logBuilder.toString());

			method = details.getMethod();
			urlString = details.getUrlString();
			if (details.getRequestHeaders() != null) {
				requestHeaders = details.getRequestHeaders().toString();
			} else {
				requestHeaders = "";
			}
			responseCode = details.getResponseCode();
			responseMessage = details.getResponseMessage();
			if (details.getResponseHeaders() != null) {
				responseHeaders = details.getResponseHeaders().toString();
			} else {
				responseHeaders = "";
			}

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
					String trimmedLine = line.trim();
					BLOCKED_LISTS.add(trimmedLine);
					emitter.onNext(trimmedLine);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, BackpressureStrategy.BUFFER)
		.subscribeOn(Schedulers.io())
		.observeOn(Schedulers.computation())
    	.doFinally(loadDataLatch::countDown)
    	.subscribe(item -> {},
				   error -> XposedBridge.log(LOG_PREFIX + errorMessage + ": " + error));
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
					System.currentTimeMillis(), type, isBlocked, method, urlString, requestHeaders,
					responseCode, responseMessage, responseHeaders);
			urlString = null;

			intent.putExtra("request", blockedRequest);
			AndroidAppHelper.currentApplication().sendBroadcast(intent);
		} else {
			Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
		}

	}

}
