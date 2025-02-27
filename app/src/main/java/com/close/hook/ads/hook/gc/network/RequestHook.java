package com.close.hook.ads.hook.gc.network;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.StringFinderKit;
import com.close.hook.ads.provider.UrlContentProvider;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.luckypray.dexkit.result.MethodData;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Triple;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";

    private static final Object EMPTY_WEB_RESPONSE = createEmptyWebResourceResponse();

    private static final ConcurrentHashMap<String, Boolean> dnsHostCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> urlStringCache = new ConcurrentHashMap<>();

    private static Context applicationContext;

    private static final Uri CONTENT_URI = new Uri.Builder()
        .scheme("content")
        .authority(UrlContentProvider.AUTHORITY)
        .appendPath(UrlContentProvider.URL_TABLE_NAME)
        .build();

    private static final Cache<String, Triple<Boolean, String, String>> queryCache = CacheBuilder.newBuilder()
        .maximumSize(4857)
        .expireAfterWrite(6, TimeUnit.HOURS)
        .concurrencyLevel(Runtime.getRuntime().availableProcessors() * 2)
        .build();

    public static void init() {
        try {
            ContextUtil.addOnApplicationContextInitializedCallback(() -> {
                applicationContext = ContextUtil.applicationContext;
                setupDNSRequestHook();
                setupHttpRequestHook();
                setupOkHttpRequestHook();
                setupWebViewRequestHook();
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error during initialization: " + e.getMessage());
        }
    }

    private static String calculateCidrNotation(InetAddress inetAddress) {
        byte[] addressBytes = inetAddress.getAddress();
        int prefixLength = 0;

        for (byte b : addressBytes) {
            prefixLength += Integer.bitCount(b & 0xFF);
        }

        return inetAddress.getHostAddress() + "/" + prefixLength;
    }

    private static String formatUrlWithoutQuery(Object urlObject) {
        try {
            if (urlObject instanceof URL) {
                URL url = (URL) urlObject;
                String decodedPath = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8.name());
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), decodedPath).toExternalForm();
            } else if (urlObject instanceof Uri) {
                Uri uri = (Uri) urlObject;
                String decodedPath = URLDecoder.decode(uri.getPath(), StandardCharsets.UTF_8.name());
                return new Uri.Builder()
                        .scheme(uri.getScheme())
                        .authority(uri.getAuthority())
                        .path(decodedPath)
                        .build()
                        .toString();
            }
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error formatting URL: " + e.getMessage());
        }
        return "";
    }

    private static boolean shouldBlockDnsRequest(final String host, final RequestDetails details) {
        return checkShouldBlockRequest(host, details, " DNS", "host");
    }

    private static boolean shouldBlockHttpsRequest(final URL url, final RequestDetails details) {
        return checkShouldBlockRequest(formatUrlWithoutQuery(url), details, " HTTP", "url");
    }

    private static boolean shouldBlockOkHttpsRequest(final URL url, final RequestDetails details) {
        return checkShouldBlockRequest(formatUrlWithoutQuery(url), details, " OKHTTP", "url");
    }

    private static boolean shouldBlockWebRequest(final String url, final RequestDetails details) {
        return checkShouldBlockRequest(formatUrlWithoutQuery(Uri.parse(url)), details, " Web", "url");
    }

    private static boolean checkShouldBlockRequest(final String queryValue, final RequestDetails details, final String requestType, final String queryType) {
        Triple<Boolean, String, String> triple = queryContentProvider(queryType, queryValue);
        boolean shouldBlock = triple.getFirst();
        String blockType = triple.getSecond();
        String url = triple.getThird();

        sendBroadcast(requestType, shouldBlock, blockType, url, queryValue, details);
        return shouldBlock;
    }

    private static Triple<Boolean, String, String> queryContentProvider(String queryType, String queryValue) {
        String cacheKey = queryType + ":" + queryValue;
        Triple<Boolean, String, String> cachedResult = queryCache.getIfPresent(cacheKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        Triple<Boolean, String, String> result = performContentQuery(queryType, queryValue);
        queryCache.put(cacheKey, result);
        return result;
    }

    private static Triple<Boolean, String, String> performContentQuery(String queryType, String queryValue) {
        ContentResolver contentResolver = applicationContext.getContentResolver();
        String[] projection = {Url.URL_TYPE, Url.URL_ADDRESS};
        String[] selectionArgs = {queryType, queryValue};

        try (Cursor cursor = contentResolver.query(CONTENT_URI, projection, null, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String urlType = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_TYPE));
                String urlValue = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_ADDRESS));
                return new Triple<>(true, urlType, urlValue);
            }
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error querying content provider: " + e.getMessage());
        }

        return new Triple<>(false, null, null);
    }

    private static void setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
            InetAddress.class,
            "getByName",
            new Object[]{String.class},
            "after",
            param -> {
                if (processDnsRequest(param.args, param.getResult(), "getByName")) {
                    param.setResult(null);
                }
            }
        );

        HookUtil.findAndHookMethod(
            InetAddress.class,
            "getAllByName",
            new Object[]{String.class},
            "after",
            param -> {
                if (processDnsRequest(param.args, param.getResult(), "getAllByName")) {
                    param.setResult(new InetAddress[0]);
                }
            }
        );
    }

    private static boolean processDnsRequest(Object[] args, Object result, String methodName) {
        String host = (String) args[0];
        if (host == null) return false;

        String stackTrace = HookUtil.getFormattedStackTrace();
        String cidr = null;
        String fullAddress = null;

        if ("getByName".equals(methodName)) {
            InetAddress inetAddress = (InetAddress) result;
            if (inetAddress != null) {
                cidr = calculateCidrNotation(inetAddress);
                fullAddress = inetAddress.getHostAddress();
            }
        } else if ("getAllByName".equals(methodName)) {
            InetAddress[] inetAddresses = (InetAddress[]) result;
            if (inetAddresses != null && inetAddresses.length > 0) {
                StringBuilder cidrBuilder = new StringBuilder();
                StringBuilder fullAddressBuilder = new StringBuilder();

                for (InetAddress inetAddress : inetAddresses) {
                    if (cidrBuilder.length() > 0) {
                        cidrBuilder.append(", ");
                        fullAddressBuilder.append(", ");
                    }
                    cidrBuilder.append(calculateCidrNotation(inetAddress));
                    fullAddressBuilder.append(inetAddress.getHostAddress());
                }

                cidr = cidrBuilder.toString();
                fullAddress = fullAddressBuilder.toString();
            }
        }

        if (cidr != null && fullAddress != null) {
            RequestDetails details = new RequestDetails(host, cidr, fullAddress, stackTrace);
            return shouldBlockDnsRequest(host, details);
        }
        return false;
    }

    private static void setupHttpRequestHook() {
        try {
            HookUtil.hookAllMethods(
                "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
                "getResponse",
                "after",
                param -> {
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

                        if (processHttpRequest(request, response, url, stackTrace)) {
                            Object emptyResponse = createEmptyResponseForHttp(response);

                            Field userResponseField = httpEngine.getClass().getDeclaredField("userResponse");
                            userResponseField.setAccessible(true);
                            userResponseField.set(httpEngine, emptyResponse);
                        }
                    } catch (Exception e) {
                        XposedBridge.log(LOG_PREFIX + "Exception in HTTP connection hook: " + e.getMessage());
                    }
                },
                applicationContext.getClassLoader()
            );
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static Object createEmptyResponseForHttp(Object response) throws Exception {
        if (response == null || !response.getClass().getName().equals("com.android.okhttp.Response")) {
            return null;
        }

        Class<?> responseClass = response.getClass();
        Class<?> builderClass = Class.forName("com.android.okhttp.Response$Builder");
        Class<?> requestClass = Class.forName("com.android.okhttp.Request");
        Class<?> protocolClass = Class.forName("com.android.okhttp.Protocol");
        Class<?> responseBodyClass = Class.forName("com.android.okhttp.ResponseBody");

        Object request = XposedHelpers.callMethod(response, "request");

        Object builder = builderClass.getConstructor().newInstance();

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

    public static void setupOkHttpRequestHook() {
        hookMethod("setupOkHttpRequestHook", "Already Executed", "execute");  // okhttp3.Call.execute -overload method
        hookMethod("setupOkHttp2RequestHook", "Canceled", "intercept");  // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
    }

    private static void hookMethod(String cacheKeySuffix, String methodDescription, String methodName) {
        String cacheKey = applicationContext.getPackageName() + ":" + cacheKeySuffix;
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, methodDescription, methodName);

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(DexKitUtil.INSTANCE.getContext().getClassLoader());
                    XposedBridge.log(LOG_PREFIX + "setupOkHttpRequestHook" + methodData);
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

                            if (processHttpRequest(request, response, url, stackTrace)) {
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

    private static boolean processHttpRequest(Object request, Object response, URL url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            RequestDetails details = new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, stack);

            return shouldBlockHttpsRequest(url, details);

        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Exception in processing request: " + e.getMessage());
            return false;
        }
    }

    public static void setupWebViewRequestHook() {
        String packageName = applicationContext.getPackageName();

        if ("com.UCMobile".equals(packageName) || "com.UCMobile.intl".equals(packageName)) {
            setupWebViewRequestHookForUCMobile();
        } else {
            setupWebViewRequestHookForDefault();
        }
    }

    private static void setupWebViewRequestHookForUCMobile() {
        HookUtil.findAndHookMethod(
            "com.uc.webview.export.WebView",
            "setWebViewClient",
            new Object[]{"com.uc.webview.export.WebViewClient"},
            "before",
            param -> {
                Object client = param.args[0];
                if (client != null) {
                    String clientClassName = client.getClass().getName();
                    hookClientMethods(clientClassName, applicationContext.getClassLoader(),
                        "com.uc.webview.export.WebView", "com.uc.webview.export.WebResourceRequest");
                }
            },
            applicationContext.getClassLoader()
        );
    }

    private static void setupWebViewRequestHookForDefault() {
        HookUtil.findAndHookMethod(
            WebView.class,
            "setWebViewClient",
            new Object[]{WebViewClient.class},
            "before",
            param -> {
                Object client = param.args[0];
                if (client != null) {
                    String clientClassName = client.getClass().getName();
                    hookClientMethods(clientClassName, applicationContext.getClassLoader(),
                        WebView.class.getName(), WebResourceRequest.class.getName());
                }
            }
        );
    }

    private static void hookClientMethods(
        String clientClassName,
        ClassLoader classLoader,
        String webViewClassName,
        String webResourceRequestClassName
    ) {

        XposedBridge.log(LOG_PREFIX + " - WebViewClient set: " + clientClassName);

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldInterceptRequest",
            new Object[]{webViewClassName, webResourceRequestClassName},
            "after",
            param -> {
                Object request = param.args[1];
                Object response = param.getResult();
                if (request != null && processWebRequest(request, response)) {
                    param.setResult(EMPTY_WEB_RESPONSE);
                }
            },
            classLoader
        );

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldOverrideUrlLoading",
            new Object[]{webViewClassName, webResourceRequestClassName},
            "after",
            param -> {
                Object request = param.args[1];
                if (request != null && processWebRequest(request, null)) {
                    param.setResult(true);
                }
            },
            classLoader
        );
    }

    private static boolean processWebRequest(Object request, Object response) {
        try {
            String method = null;
            String urlString = null;
            Object requestHeaders = null;

            if (request != null) {
                method = (String) XposedHelpers.callMethod(request, "getMethod");
                Object webUrl = XposedHelpers.callMethod(request, "getUrl");
                requestHeaders = XposedHelpers.callMethod(request, "getRequestHeaders");
                urlString = webUrl.toString();
            }

            int responseCode = 0;
            String responseMessage = null;
            Object responseHeaders = null;

            if (response != null) {
                responseHeaders = XposedHelpers.callMethod(response, "getResponseHeaders");
                responseCode = (int) XposedHelpers.callMethod(response, "getStatusCode");
                responseMessage = (String) XposedHelpers.callMethod(response, "getReasonPhrase");
            }

            String stackTrace = HookUtil.getFormattedStackTrace();

            RequestDetails details = new RequestDetails(
                method, urlString, requestHeaders, responseCode, responseMessage, responseHeaders, stackTrace
            );

            return shouldBlockWebRequest(urlString, details);

        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error processing web request: " + e.getMessage());
            return false;
        }
    }

    private static Object createEmptyWebResourceResponse() {
        try {
            return new WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    204,
                    "No Content",
                    Collections.emptyMap(),
                    InputStream.nullInputStream()
            );
        } catch (Exception e) {
            XposedBridge.log("Error creating empty WebResourceResponse: " + e.getMessage());
            return null;
        }
    }

    private static void sendBroadcast(
        String requestType, boolean shouldBlock, String blockType, String ruleUrl,
        String url, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, ruleUrl, blockType, url, details);
        sendBlockedRequestBroadcast(shouldBlock ? "block" : "pass", requestType, shouldBlock, ruleUrl, blockType, url, details);
    }

    private static void sendBlockedRequestBroadcast(
        String type, @Nullable String requestType, @Nullable Boolean isBlocked,
        @Nullable String url, @Nullable String blockType, String request,
        RequestDetails details) {
        if (details == null) return;

        String dnsHost = details.getDnsHost();
        String urlString = details.getUrlString();

        if (dnsHost != null && dnsHostCache.putIfAbsent(dnsHost, true) != null) {
            return;
        }

        if (urlString != null && urlStringCache.putIfAbsent(urlString, true) != null) {
            return;
        }

        Intent intent = new Intent("com.rikkati.REQUEST");

        try {
            String appName = applicationContext.getApplicationInfo().loadLabel(applicationContext.getPackageManager()).toString() + requestType;
            String packageName = applicationContext.getPackageName();

            String method = details.getMethod();
            String requestHeaders = details.getRequestHeaders() != null ? details.getRequestHeaders().toString() : null;
            int responseCode = details.getResponseCode();
            String responseMessage = details.getResponseMessage();
            String responseHeaders = details.getResponseHeaders() != null ? details.getResponseHeaders().toString() : null;
            String stackTrace = details.getStack();
            String dnsCidr = details.getDnsCidr();
            String fullAddress = details.getFullAddress();

            BlockedRequest blockedRequest = new BlockedRequest(
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
                stackTrace,
                dnsHost,
                dnsCidr,
                fullAddress
            );

            intent.putExtra("request", blockedRequest);
            applicationContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: Error broadcasting request", e);
        }
    }
}
