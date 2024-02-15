package com.close.hook.ads.hook.gc.network;

import android.app.AndroidAppHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

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
import java.util.List;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import org.luckypray.dexkit.result.MethodData;
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.StringFinderKit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";
    private static final Set<String> BLOCKED_LISTS = ConcurrentHashMap.newKeySet(); // 包含策略
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
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
        XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, InetAddressHook);
        XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);
    }

    private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String host = (String) param.args[0];
            if (host != null && shouldBlockDnsRequest(host)) {
                if ("getByName".equals(param.method.getName())) {
                    param.setResult(null);
                } else if ("getAllByName".equals(param.method.getName())) {
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

        Pair<Boolean, String> pair = queryContentProvider("host", host);
        boolean shouldBlock = pair.first;
        String blockType = pair.second;

        sendBroadcast(" DNS", shouldBlock, blockType, host);
        return shouldBlock;
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
        String formattedUrl = formatUrlWithoutQuery(url);

        Pair<Boolean, String> pair = queryContentProvider("url", formattedUrl);
        boolean shouldBlock = pair.first;
        String blockType = pair.second;

        sendBroadcast(" HTTP", shouldBlock, blockType, formattedUrl);
        return shouldBlock;
    }

    private static Object createEmptyResponseForOkHttp(Object chain) throws Exception {
        if (chain instanceof okhttp3.Interceptor.Chain) {
            okhttp3.Interceptor.Chain okhttpChain = (okhttp3.Interceptor.Chain) chain;
            Request request = okhttpChain.request();

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .build();
        }
        return null;
    }

    private static boolean shouldBlockOkHttpsRequest(Object chain) {
        try {
            if (chain == null) {
                return false;
            }
    
            Object request = XposedHelpers.callMethod(chain, "request");
            Object httpUrl = XposedHelpers.callMethod(request, "url");
            URL url = new URL(httpUrl.toString());
            String formattedUrl = formatUrlWithoutQuery(url);

            Pair<Boolean, String> pair = queryContentProvider("url", formattedUrl);
            boolean shouldBlock = pair.first;
            String blockType = pair.second;

            sendBroadcast(" OKHTTP", shouldBlock, blockType, formattedUrl);
            return shouldBlock;
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error processing OkHttp request: " + e.getMessage());
            return false;
        }
    }

    private static Pair<Boolean, String> queryContentProvider(String queryType, String queryValue) {
        Context context = AndroidAppHelper.currentApplication();
        if (context != null) {
            ContentResolver contentResolver = context.getContentResolver();
            Uri uri = Uri.parse("content://" + UrlContentProvider.AUTHORITY + "/" + UrlContentProvider.URL_TABLE_NAME);
            String[] projection = new String[]{Url.Companion.getURL_TYPE(), Url.Companion.getURL_ADDRESS()};
            String selection = null;
            String[] selectionArgs = null;
    
            if ("host".equals(queryType)) {
                selection = Url.Companion.getURL_TYPE() + " = ?";
                selectionArgs = new String[]{"host"};
            }

            try (Cursor cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)) {
                if (cursor != null) {
                    int urlTypeIndex = cursor.getColumnIndex(Url.Companion.getURL_TYPE());
                    int urlValueIndex = cursor.getColumnIndex(Url.Companion.getURL_ADDRESS());

                    while (cursor.moveToNext()) {
                        String urlType = cursor.getString(urlTypeIndex);
                        String urlValue = cursor.getString(urlValueIndex);

                        if ("host".equals(queryType) && Objects.equals(urlValue, queryValue)) {
                            return new Pair<>(true, "Host");
                        } else if (("url".equals(queryType) || "keyword".equals(queryType)) && queryValue.contains(urlValue)) {
                            return new Pair<>(true, formatUrlType(urlType));
                        }
                    }
                }
            }
        }
        return new Pair<>(false, null);
    }

    private static String formatUrlType(String urlType) {
        return urlType.replace("url", "URL").replace("keyword", "KeyWord");
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

    private static String extractHost(String url) {
        String host = url.replace("https://", "").replace("http://", "");
        int indexOfSlash = host.indexOf('/');
        if (indexOfSlash != -1) {
            host = host.substring(0, indexOfSlash);
        }
        return host;
    }

    private static void setupHttpRequestHook() {
        try {
            Class<?> httpURLConnectionImpl = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl");

            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "execute", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    processHttpRequestAsync(param);
                }
            });

            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "getInputStream", httpConnectionHook);
            XposedHelpers.findAndHookMethod(httpURLConnectionImpl, "getOutputStream", httpConnectionHook);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    public static void setupOkHttpRequestHook() {
        String cacheKey = DexKitUtil.INSTANCE.getContext().getPackageName() + ":setupOkHttpRequestHook";
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, " had non-zero Content-Length: ");

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(DexKitUtil.INSTANCE.getContext().getClassLoader());
    //              XposedBridge.log("hook " + methodData);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object chain = param.args[0];

                            if (shouldBlockOkHttpsRequest(chain)) {
                                Object response = createEmptyResponseForOkHttp(chain);
                                param.setResult(response);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            processOkHttpRequestAsync(param);
                        }
                    });
                } catch (Exception e) {
                }
            }
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

    private static void sendBroadcast(String requestType, boolean shouldBlock, String blockType, String url) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, blockType, url);
        if (shouldBlock) {
            sendBlockedRequestBroadcast("block", requestType, true, blockType, url);
        } else {
            sendBlockedRequestBroadcast("pass", requestType, false, blockType, url);
        }
    }

    private static void sendBlockedRequestBroadcast(String type, @Nullable String requestType,
                                                    @Nullable Boolean isBlocked, @Nullable String blockType, String request) {
        Intent intent;
        switch (type) {
            case "all":
                intent = new Intent("com.rikkati.ALL_REQUEST");
                break;
            case "block":
                intent = new Intent("com.rikkati.BLOCKED_REQUEST");
                break;
            default:
                intent = new Intent("com.rikkati.PASS_REQUEST");
                break;
        }

        Context currentContext = AndroidAppHelper.currentApplication();
        if (currentContext != null) {
            PackageManager pm = currentContext.getPackageManager();
            String appName;
            try {
                appName = pm.getApplicationLabel(
                                pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA))
                        .toString();
            } catch (PackageManager.NameNotFoundException e) {
                appName = currentContext.getPackageName();
            }
            appName += requestType;
            String packageName = currentContext.getPackageName();

            BlockedRequest blockedRequest = new BlockedRequest(appName, packageName, request,
                    System.currentTimeMillis(), type, isBlocked, blockType, method, urlString, requestHeaders,
                    responseCode, responseMessage, responseHeaders);
            urlString = null;

            intent.putExtra("request", blockedRequest);
            currentContext.sendBroadcast(intent);
        } else {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
        }
    }

}