package com.close.hook.ads.hook.gc.network;

import android.app.AndroidAppHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.close.hook.ads.BlockedBean;
import com.close.hook.ads.IBlockedStatusProvider;
import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.StringFinderKit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.luckypray.dexkit.result.MethodData;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import kotlin.Triple;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";

    private static IBlockedStatusProvider mStub;
    private static boolean isBound = false;

    private static final Cache<String, Triple<Boolean, String, String>> cache = CacheBuilder.newBuilder()
            .maximumSize(5000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    private static final ConcurrentHashMap<String, CompletableFuture<Triple<Boolean, String, String>>> queryInProgress = new ConcurrentHashMap<>();

    public static void init() {
        try {
            setupDNSRequestHook();
            setupHttpRequestHook();
            setupOkHttpRequestHook();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
        }
    }

    private static void setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
                InetAddress.class,
                "getByName",
                "before",
                param -> {
                    String host = (String) param.args[0];
                    if (host != null && shouldBlockDnsRequest(host)) {
                        param.setResult(null);
                    }
                },
                String.class
        );

        HookUtil.findAndHookMethod(
                InetAddress.class,
                "getAllByName",
                "before",
                param -> {
                    String host = (String) param.args[0];
                    if (host != null && shouldBlockDnsRequest(host)) {
                        param.setResult(new InetAddress[0]);
                    }
                },
                String.class
        );
    }

    private static boolean shouldBlockDnsRequest(String host) {
        return checkShouldBlockRequest(host, null, "DNS", "host");
    }

    private static boolean shouldBlockHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, "HTTP", "url");
    }

    private static boolean shouldBlockOkHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, "OKHTTP", "url");
    }

    private static boolean checkShouldBlockRequest(final String queryValue, final RequestDetails details, final String requestType, final String queryType) {
        String cacheKey = queryType + ":" + queryValue;

        Triple<Boolean, String, String> cachedResult = cache.getIfPresent(cacheKey);
        if (cachedResult != null) {
            return processCachedResult(cachedResult, cacheKey, requestType, queryValue, details);
        }

        CompletableFuture<Triple<Boolean, String, String>> futureResult = queryInProgress.get(cacheKey);
        if (futureResult != null) {
            return getResultFromFuture(futureResult, cacheKey, requestType, queryValue, details);
        }

        CompletableFuture<Triple<Boolean, String, String>> newFuture = CompletableFuture.supplyAsync(() -> {
            ensureServiceBound();
            return queryContentProvider(queryType, queryValue);
        });
        queryInProgress.put(cacheKey, newFuture);

        boolean shouldBlock = getResultFromFuture(newFuture, cacheKey, requestType, queryValue, details);
        queryInProgress.remove(cacheKey);
        return shouldBlock;
    }

    private static boolean getResultFromFuture(CompletableFuture<Triple<Boolean, String, String>> future, String cacheKey, String requestType, String queryValue, RequestDetails details) {
        try {
            Triple<Boolean, String, String> result = future.get(5, TimeUnit.SECONDS);
            if (result == null) {
                XposedBridge.log(LOG_PREFIX + "Null result from future for key: " + cacheKey);
                return false;
            }

            cache.put(cacheKey, result);

            return processCachedResult(result, cacheKey, requestType, queryValue, details);
        } catch (TimeoutException e) {
            XposedBridge.log(LOG_PREFIX + "Timeout while waiting for query result for key: " + cacheKey);
            future.cancel(true);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            XposedBridge.log(LOG_PREFIX + "Query interrupted for key: " + cacheKey);
            return false;
        } catch (ExecutionException e) {
            XposedBridge.log(LOG_PREFIX + "Exception during query execution for key: " + cacheKey + ", cause: " + e.getCause());
            return false;
        }
    }

    private static boolean processCachedResult(Triple<Boolean, String, String> result, String cacheKey, String requestType, String queryValue, RequestDetails details) {
        boolean shouldBlock = result.getFirst();
        String blockType = result.getSecond();
        String ruleUrl = result.getThird();

        sendBroadcast(requestType, shouldBlock, blockType, ruleUrl, queryValue, details);
        return shouldBlock;
    }

    public static Triple<Boolean, String, String> queryContentProvider(String queryType, String queryValue) {
        ensureServiceBound();
        try {
            if (mStub != null) {
                BlockedBean blockedBean = mStub.getData(queryType.replace("host", "Domain").replace("url", "URL"), queryValue);
                if (blockedBean != null) {
                    return new Triple<>(blockedBean.isBlocked(), blockedBean.getType(), blockedBean.getValue());
                } else {
                    XposedBridge.log(LOG_PREFIX + "BlockedBean is null for key: " + queryValue);
                }
            }
        } catch (RemoteException e) {
            XposedBridge.log(LOG_PREFIX + "RemoteException during service communication for key: " + queryValue + ", cause: " + e.getMessage());
        }

        return new Triple<>(false, null, null);
    }

    private static void ensureServiceBound() {
        if (mStub == null) {
            Context context = AndroidAppHelper.currentApplication();
            if (context != null) {
                bindService(context);
            }

            int retryCount = 0;
            while (mStub == null && retryCount < 5) {
                try {
                    Thread.sleep(500);
                    retryCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    XposedBridge.log(LOG_PREFIX + "Service binding interrupted");
                    return;
                }
            }

            if (mStub == null) {
                XposedBridge.log(LOG_PREFIX + "Failed to bind service after retries");
            }
        }
    }

    private static void bindService(Context context) {
        if (!isBound) {
            Intent intent = new Intent();
            intent.setClassName("com.close.hook.ads", "com.close.hook.ads.service.AidlService");
            isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private static final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mStub = IBlockedStatusProvider.Stub.asInterface(service);
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mStub = null;
            isBound = false;
        }
    };

    private static void setupHttpRequestHook() {
        try {
            HookUtil.hookAllMethods("com.android.okhttp.internal.huc.HttpURLConnectionImpl", "getResponse", "after", param -> {
                try {
                    Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");

                    boolean isResponseAvailable = (boolean) XposedHelpers.callMethod(httpEngine, "hasResponse");
                    if (!isResponseAvailable) {
                        return;
                    }

                    Object response = XposedHelpers.callMethod(httpEngine, "getResponse");
                    Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                    Object httpUrl = XposedHelpers.callMethod(request, "urlString");
                    URL url = new URL(httpUrl.toString());

                    String stackTrace = HookUtil.getFormattedStackTrace();
                    RequestDetails details = processRequest(request, response, url, stackTrace);

                    if (details != null && shouldBlockHttpsRequest(url, details)) {
                        Object emptyResponse = createEmptyResponseForHttp(response);

                        Field userResponseField = httpEngine.getClass().getDeclaredField("userResponse");
                        userResponseField.setAccessible(true);
                        userResponseField.set(httpEngine, emptyResponse);
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Exception in HTTP connection hook: " + e.getMessage());
                }
            }, ContextUtil.appContext.getClassLoader());
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static void setupOkHttpRequestHook() {
        hookMethod("setupOkHttpRequestHook", "Already Executed", "execute");
        hookMethod("setupOkHttp2RequestHook", "Canceled", "intercept");
    }

    private static void hookMethod(String cacheKeySuffix, String methodDescription, String methodName) {
        String cacheKey = DexKitUtil.INSTANCE.getContext().getPackageName() + ":" + cacheKeySuffix;
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, methodDescription, methodName);

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(DexKitUtil.INSTANCE.getContext().getClassLoader());
                    HookUtil.hookMethod(method, "after", param -> {
                        try {
                            Object response = param.getResult();
                            if (response == null) {
                                return;
                            }

                            Object request = XposedHelpers.callMethod(response, "request");
                            Object okhttpUrl = XposedHelpers.callMethod(request, "url");
                            URL url = new URL(okhttpUrl.toString());

                            String stackTrace = HookUtil.getFormattedStackTrace();
                            RequestDetails details = processRequest(request, response, url, stackTrace);

                            if (details != null && shouldBlockOkHttpsRequest(url, details)) {
                                throw new IOException("Request blocked");
                            }
                        } catch (IOException e) {
                            param.setThrowable(e);
                        } catch (Exception e) {
                            XposedBridge.log("Error processing method hook: " + methodData + ", " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    XposedBridge.log("Error hooking method: " + methodData + ", " + e.getMessage());
                }
            }
        }
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

    private static Object createEmptyResponseForHttp(Object response) throws Exception {
        if (response.getClass().getName().equals("com.android.okhttp.Response")) {
            Class<?> responseClass = response.getClass();

            Class<?> builderClass = Class.forName("com.android.okhttp.Response$Builder");
            Class<?> requestClass = Class.forName("com.android.okhttp.Request");
            Class<?> protocolClass = Class.forName("com.android.okhttp.Protocol");
            Class<?> responseBodyClass = Class.forName("com.android.okhttp.ResponseBody");

            Object request = XposedHelpers.callMethod(response, "request");
            Object builder = builderClass.newInstance();

            builderClass.getMethod("request", requestClass).invoke(builder, request);
            Object protocolHTTP11 = protocolClass.getField("HTTP_1_1").get(null);
            builderClass.getMethod("protocol", protocolClass).invoke(builder, protocolHTTP11);
            builderClass.getMethod("code", int.class).invoke(builder, 204); // 204 No Content
            builderClass.getMethod("message", String.class).invoke(builder, "No Content");

            Method createMethod = responseBodyClass.getMethod("create", Class.forName("com.android.okhttp.MediaType"), String.class);
            Object emptyResponseBody = createMethod.invoke(null, null, "");
            builderClass.getMethod("body", responseBodyClass).invoke(builder, emptyResponseBody);

            return builderClass.getMethod("build").invoke(builder);
        }
        return null;
    }

    private static RequestDetails processRequest(Object request, Object response, URL url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            return new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, stack);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Exception in processing request: " + e.getMessage());
            return null;
        }
    }

    private static void sendBroadcast(String requestType, boolean shouldBlock, String blockType, String ruleUrl, String url, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, ruleUrl, blockType, url, details);
        if (shouldBlock) {
            sendBlockedRequestBroadcast("block", requestType, true, ruleUrl, blockType, url, details);
        } else {
            sendBlockedRequestBroadcast("pass", requestType, false, ruleUrl, blockType, url, details);
        }
    }

    private static void sendBlockedRequestBroadcast(String type, @Nullable String requestType, @Nullable Boolean isBlocked, @Nullable String url, @Nullable String blockType, String request, RequestDetails details) {
        Intent intent = new Intent("com.rikkati.REQUEST");

        Context currentContext = AndroidAppHelper.currentApplication();
        if (currentContext != null) {
            PackageManager pm = currentContext.getPackageManager();
            String appName;
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                appName = currentContext.getPackageName();
            }
            appName += requestType;
            String packageName = currentContext.getPackageName();
            BlockedRequest blockedRequest;

            String method = details != null ? details.getMethod() : null;
            String urlString = details != null ? details.getUrlString() : null;
            String requestHeaders = details != null && details.getRequestHeaders() != null ? details.getRequestHeaders().toString() : null;
            int responseCode = details != null ? details.getResponseCode() : -1;
            String responseMessage = details != null ? details.getResponseMessage() : null;
            String responseHeaders = details != null && details.getResponseHeaders() != null ? details.getResponseHeaders().toString() : null;
            String stackTrace = details != null ? details.getStack() : null;

            blockedRequest = new BlockedRequest(
                    appName,
                    packageName,
                    request,
                    System.currentTimeMillis(),
                    type,
                    isBlocked,
                    url,
                    blockType,
                    method,
                    urlString,
                    requestHeaders,
                    responseCode,
                    responseMessage,
                    responseHeaders,
                    stackTrace
            );

            intent.putExtra("request", blockedRequest);
            currentContext.sendBroadcast(intent);
        } else {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
        }
    }
}
