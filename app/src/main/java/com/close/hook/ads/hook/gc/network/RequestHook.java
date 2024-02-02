package com.close.hook.ads.hook.gc.network;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.provider.UrlContentProvider;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

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

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            setupDNSRequestHook();
            setupHttpRequestHook();
            setupOkHttpRequestHook(lpparam);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
        }
    }

    private static void loadBlockedListsAsync() {
        loadListAsync("/assets/blocked_lists.txt", "Error loading blocked lists");
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

    private static boolean shouldBlockDnsRequest(String host) {
        if (host == null) {
            return false;
        }
        waitForDataLoading();
        boolean shouldBlock = shouldBlockHost(host);

        shouldBlock = shouldBlock || queryHostContentProvider(host);

        sendBroadcast(" DNS", shouldBlock, host);
        return shouldBlock;
    }

    private static void setupHttpConnectionHook() {
        try {
            Class<?> httpURLConnectionImpl = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl");

            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "getInputStream", httpConnectionHook);
            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "getOutputStream", httpConnectionHook);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static final XC_MethodHook httpConnectionHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
            URL url = httpURLConnection.getURL();
            if (shouldBlockHttpsRequest(url)) {
                BlockedURLConnection blockedConnection = new BlockedURLConnection(url);
                String methodName = param.method.getName();
                if ("getInputStream".equals(methodName)) {
                    param.setResult(blockedConnection.getInputStream());
                } else if ("getOutputStream".equals(methodName)) {
                    param.setResult(blockedConnection.getOutputStream());
                }
            }
        }
    };

    private static boolean shouldBlockHttpsRequest(URL url) {
        if (url == null) {
            return false;
        }
        waitForDataLoading();
        String formattedUrl = formatUrlWithoutQuery(url);
        boolean shouldBlock = shouldBlockURL(formattedUrl);

        shouldBlock = shouldBlock || queryURLContentProvider(formattedUrl);

        sendBroadcast(" HTTP", shouldBlock, formattedUrl);
        return shouldBlock;
    }

    private static void setupOkHttpConnectionHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("okhttp3.internal.http.CallServerInterceptor", lpparam.classLoader, "intercept", "okhttp3.Interceptor.Chain", okhttpConnectionHook);
    }

    private static final XC_MethodHook okhttpConnectionHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Object chain = param.args[0];

            if (shouldBlockOkHttpsRequest(chain)) {
                Object response = createEmptyResponseForOkHttp(chain);
                param.setResult(response);
            }
        }
    };

    private static Object createEmptyResponseForOkHttp(Object chain) throws Exception {
        Object request = XposedHelpers.callMethod(chain, "request");
        Class<?> responseClass = Class.forName("okhttp3.Response");
        Class<?> protocolClass = Class.forName("okhttp3.Protocol");
        Object builder = responseClass.getDeclaredConstructor().newInstance();

        XposedHelpers.callMethod(builder, "request", request);
        XposedHelpers.callMethod(builder, "protocol", Enum.valueOf((Class<Enum>) protocolClass, "HTTP_1_1"));
        XposedHelpers.callMethod(builder, "code", 204);
        XposedHelpers.callMethod(builder, "message", "No Content");

        return XposedHelpers.callMethod(builder, "build");
    }

    private static boolean shouldBlockOkHttpsRequest(Object chain) {
        try {
            if (chain == null) {
                return false;
            }
            waitForDataLoading();

            Object request = XposedHelpers.callMethod(chain, "request");
            Object httpUrl = XposedHelpers.callMethod(request, "url");
            URL url = new URL(httpUrl.toString());
            String formattedUrl = formatUrlWithoutQuery(url);
            boolean shouldBlock = shouldBlockURL(formattedUrl);

            shouldBlock = shouldBlock || queryURLContentProvider(formattedUrl);

            sendBroadcast(" OKHTTP", shouldBlock, formattedUrl);
            return shouldBlock;
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error processing OkHttp request: " + e.getMessage());
            return false;
        }
    }

    private static boolean shouldBlockHost(String host) {
        try {
            return urlBlockCache.get(host, () -> BLOCKED_LISTS.stream().anyMatch(host::contains));
        } catch (ExecutionException e) {
            XposedBridge.log(LOG_PREFIX + "Error accessing the cache: " + e.getMessage());
            throw new RuntimeException("Error accessing the cache", e);
        }
    }

    private static boolean shouldBlockURL(String urlPart) {
        try {
            return urlBlockCache.get(urlPart, () -> BLOCKED_LISTS.stream().anyMatch(urlPart::contains));
        } catch (ExecutionException e) {
            XposedBridge.log(LOG_PREFIX + "Error accessing the cache: " + e.getMessage());
            throw new RuntimeException("Error accessing the cache", e);
        }
    }

    private static boolean queryHostContentProvider(String host) {
        Context context = AndroidAppHelper.currentApplication();
        if (context != null) {
            ContentResolver contentResolver = context.getContentResolver();
            Cursor cursor = null;
            try {
                cursor = contentResolver.query(Uri.parse("content://" + UrlContentProvider.AUTHORITY + "/" + UrlContentProvider.URL_TABLE_NAME), null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        @SuppressLint("Range") String urlAddress = cursor.getString(cursor.getColumnIndex(Url.Companion.getURL_ADDRESS()));
                        if (urlAddress.contains(host)) {
                            return true;
                        }
                    } while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return false;
    }

    private static boolean queryURLContentProvider(String formattedUrl) {
        Context context = AndroidAppHelper.currentApplication();
        if (context != null) {
            ContentResolver contentResolver = context.getContentResolver();
            Cursor cursor = null;
            try {
                cursor = contentResolver.query(Uri.parse("content://" + UrlContentProvider.AUTHORITY + "/" + UrlContentProvider.URL_TABLE_NAME), null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        @SuppressLint("Range") String urlAddress = cursor.getString(cursor.getColumnIndex(Url.Companion.getURL_ADDRESS()));
                        if (formattedUrl.contains(urlAddress)) {
                            return true;
                        }
                    } while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return false;
    }

    private static String formatUrlWithoutQuery(URL url) {
        try {
            URL formattedUrl = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath());
            return formattedUrl.toExternalForm();
        } catch (MalformedURLException e) {
            XposedBridge.log(LOG_PREFIX + "Malformed URL: " + e.getMessage());
            return null;
        }
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
                    processHttpRequestAsync(param);
                }
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static void setupOkHttpRequestHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("okhttp3.internal.http.CallServerInterceptor", lpparam.classLoader, "intercept", "okhttp3.Interceptor.Chain", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    setupOkHttpConnectionHook(lpparam);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    processOkHttpRequestAsync(param);
                }
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "OkHttp interceptor not found: " + e.getMessage());
        }
    }

    private static void processHttpRequestAsync(final XC_MethodHook.MethodHookParam param) {
        executorService.submit(() -> {
            try {
                RequestDetails details = processHttpRequest(param);
                if (details != null) {
                    logRequestDetails(details);
                }
            } catch (Exception e) {
                XposedBridge.log(LOG_PREFIX + "Error processing request: " + e.getMessage());
            }
        });
    }

    private static void processOkHttpRequestAsync(final XC_MethodHook.MethodHookParam param) {
        executorService.submit(() -> {
            try {
                RequestDetails details = processOkHttpRequest(param);
                if (details != null) {
                    logRequestDetails(details);
                }
            } catch (Exception e) {
                XposedBridge.log(LOG_PREFIX + "Error processing request: " + e.getMessage());
            }
        });
    }

    private static RequestDetails processHttpRequest(XC_MethodHook.MethodHookParam param) {
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

    private static RequestDetails processOkHttpRequest(XC_MethodHook.MethodHookParam param) {
        try {
            // 获取请求对象
            Object chain = param.args[0];
            Object request = XposedHelpers.callMethod(chain, "request");

            // 获取请求信息
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = XposedHelpers.callMethod(request, "url").toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            // 获取响应对象
            Object response = param.getResult();
            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

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

			XposedBridge.log(logBuilder.toString());
*/
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
                .subscribe(item -> {
                        },
                        error -> XposedBridge.log(LOG_PREFIX + errorMessage + ": " + error));
    }

    private static void sendBroadcast(String requestType, boolean shouldBlock, String url) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, url);
        if (shouldBlock) {
            sendBlockedRequestBroadcast("block", requestType, true, url);
        } else {
            sendBlockedRequestBroadcast("pass", requestType, false, url);
        }
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