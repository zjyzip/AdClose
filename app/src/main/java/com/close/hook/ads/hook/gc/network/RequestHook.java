package com.close.hook.ads.hook.gc.network;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Base64;

import androidx.annotation.Nullable;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.StringFinderKit;
import com.close.hook.ads.provider.UrlContentProvider;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.luckypray.dexkit.result.MethodData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import kotlin.Triple;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

public class RequestHook {

    private static final String LOG_PREFIX = "[RequestHook] ";

    private static volatile Object EMPTY_WEB_RESPONSE = null;

    private static final ConcurrentHashMap<String, Boolean> dnsHostCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> urlStringCache = new ConcurrentHashMap<>();

    private static Context applicationContext;

    private static final Uri CONTENT_URI = new Uri.Builder()
        .scheme("content")
        .authority(UrlContentProvider.AUTHORITY)
        .appendPath(UrlContentProvider.URL_TABLE_NAME)
        .build();

    private static final Cache<String, Triple<Boolean, String, String>> queryCache = CacheBuilder.newBuilder()
        .maximumSize(8192)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build();

    private static final int COMPRESSION_THRESHOLD_BYTES = 100 * 1024;

    public static void init() {
        try {
            ContextUtil.INSTANCE.addOnApplicationContextInitializedCallback(() -> {
                applicationContext = ContextUtil.INSTANCE.applicationContext;
                setupDNSRequestHook();
                setupHttpRequestHook();
                setupOkHttpRequestHook();
                setupWebViewRequestHook();
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error during initialization: " + e.getMessage());
        }
    }

    private static String formatUrlWithoutQuery(Object urlObject) {
        try {
            if (urlObject instanceof URL) {
                URL url = (URL) urlObject;
                String decodedPath = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8.name());
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), decodedPath).toExternalForm();
            } else if (urlObject instanceof Uri) {
                Uri uri = (Uri) urlObject;
                String path = uri.getPath();
                String decodedPath = (path != null) ? URLDecoder.decode(path, StandardCharsets.UTF_8.name()) : "";
                
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
        return urlObject != null ? urlObject.toString() : "";
    }

    private static boolean isJson(Object mediaTypeObj) {
        if (mediaTypeObj == null) {
            return false;
        }

        try {
            String type = (String) XposedHelpers.callMethod(mediaTypeObj, "type");
            String subtype = (String) XposedHelpers.callMethod(mediaTypeObj, "subtype");

            if (type == null || subtype == null) {
                return false;
            }

            type = type.toLowerCase();
            subtype = subtype.toLowerCase();

            return "application".equals(type) && "json".equals(subtype) ||
                   "text".equals(type) && "json".equals(subtype) ||
                   subtype.contains("json") || subtype.contains("javascript") || subtype.contains("x-www-form-urlencoded");
        } catch (Throwable t) {
            XposedBridge.log(LOG_PREFIX + "Error calling MediaType methods via reflection: " + t.getMessage());
            return false;
        }
    }

    private static boolean checkShouldBlockRequest(final String requestValue, final RequestDetails details, final String requestType) {
        String[] queryTypes = {"URL", "Domain", "KeyWord"};
        for (String queryType : queryTypes) {
            String processedValue = queryType.equals("Domain") ? AppUtils.INSTANCE.extractHostOrSelf(requestValue) : requestValue;
            
            Triple<Boolean, String, String> matchResult = queryContentProvider(queryType, processedValue);
            if (matchResult.getFirst()) {
                sendBroadcast(requestType, true, matchResult.getSecond(), matchResult.getThird(), requestValue, details);
                return true;
            }
        }

        sendBroadcast(requestType, false, null, null, requestValue, details);
        return false;
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
        String selection = null; 
        String[] selectionArgs = {queryType, queryValue};

        try (Cursor cursor = contentResolver.query(CONTENT_URI, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int urlTypeIndex = cursor.getColumnIndexOrThrow(Url.URL_TYPE);
                int urlAddressIndex = cursor.getColumnIndexOrThrow(Url.URL_ADDRESS);

                String urlType = cursor.getString(urlTypeIndex);
                String urlValue = cursor.getString(urlAddressIndex);
                return new Triple<>(true, urlType, urlValue);
            }
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error querying content provider for " + queryType + ": " + e.getMessage());
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
                if (processDnsRequest(param.args[0], param.getResult(), "getByName")) {
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
                if (processDnsRequest(param.args[0], param.getResult(), "getAllByName")) {
                    param.setResult(new InetAddress[0]);
                }
            }
        );
    }

    private static boolean processDnsRequest(Object hostObject, Object result, String methodName) {
        String host = (String) hostObject;
        if (host == null) {
            return false;
        }

        String stackTrace = HookUtil.getFormattedStackTrace();
        String fullAddress = null;

        if ("getByName".equals(methodName) && result instanceof InetAddress) {
            InetAddress inetAddress = (InetAddress) result;
            fullAddress = inetAddress.getHostAddress();
        } else if ("getAllByName".equals(methodName) && result instanceof InetAddress[]) {
            InetAddress[] inetAddresses = (InetAddress[]) result;
            if (inetAddresses != null && inetAddresses.length > 0) {
                fullAddress = Arrays.stream(inetAddresses)
                    .filter(Objects::nonNull)
                    .map(InetAddress::getHostAddress)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            }
        }

        if (fullAddress == null || fullAddress.isEmpty()) {
            return false;
        }

        RequestDetails details = new RequestDetails(host, fullAddress, stackTrace);

        return checkShouldBlockRequest(host, details, " DNS");
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

                        Object userResponse = XposedHelpers.getObjectField(httpEngine, "userResponse");
                        
                        Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                        
                        Object httpUrl = XposedHelpers.callMethod(request, "urlString");
                        URL url = new URL(httpUrl.toString());

                        String responseBodyString = readOkioHttpResponseBody(userResponse);
                        String stackTrace = HookUtil.getFormattedStackTrace();

                        if (shouldBlockHttpRequest(url, " HTTP", request, userResponse, responseBodyString, stackTrace)) {
                            Object emptyResponse = createEmptyResponseForHttp(userResponse);
                            if (emptyResponse != null) {
                                XposedHelpers.setObjectField(httpEngine, "userResponse", emptyResponse);
                            } else {
                                XposedBridge.log(LOG_PREFIX + "Failed to create empty HTTP response for " + url);
                            }
                        }
                    } catch (Exception e) {
                        XposedBridge.log(LOG_PREFIX + "Exception in HTTP connection hook (getResponse): " + e.getMessage());
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

        builderClass.getMethod("code", int.class).invoke(builder, 204);
        builderClass.getMethod("message", String.class).invoke(builder, "No Content");

        Method createMethod = responseBodyClass.getMethod("create", Class.forName("com.android.okhttp.MediaType"), String.class);
        Object emptyResponseBody = createMethod.invoke(null, null, "");

        builderClass.getMethod("body", responseBodyClass).invoke(builder, emptyResponseBody);

        return builderClass.getMethod("build").invoke(builder);
    }

    private static String readOkioHttpResponseBody(Object response) {
        String responseBodyString = null;
        Object originalResponseBody = XposedHelpers.callMethod(response, "body");

        if (originalResponseBody != null) {
            Object mediaTypeObj = XposedHelpers.callMethod(originalResponseBody, "contentType");

            if (isJson(mediaTypeObj)) {
                try {
                    Class<?> okioBufferClass = XposedHelpers.findClass("com.android.okhttp.okio.Buffer", applicationContext.getClassLoader());
                    Class<?> okioSourceClass = XposedHelpers.findClass("com.android.okhttp.okio.Source", applicationContext.getClassLoader());
                    Class<?> bufferedSourceClass = XposedHelpers.findClass("com.android.okhttp.okio.BufferedSource", applicationContext.getClassLoader());
                    Class<?> okioSinkClass = XposedHelpers.findClass("com.android.okhttp.okio.Sink", applicationContext.getClassLoader());

                    Object originalBufferedSource = XposedHelpers.callMethod(originalResponseBody, "source");
                    
                    if (originalBufferedSource == null) {
                        XposedBridge.log(LOG_PREFIX + "Original BufferedSource is null.");
                        return null;
                    }

                    Object tempBuffer = okioBufferClass.getConstructor().newInstance();
                    Method readAllMethod = XposedHelpers.findMethodExact(bufferedSourceClass, "readAll", okioSinkClass);
                    readAllMethod.invoke(originalBufferedSource, tempBuffer);
                    
                    Method readUtf8Method = XposedHelpers.findMethodExact(okioBufferClass, "readUtf8");
                    responseBodyString = (String) readUtf8Method.invoke(tempBuffer);
                } catch (Throwable e) {
                    XposedBridge.log(LOG_PREFIX + "Error reading HTTP Response Body: " + e.getMessage());
                    responseBodyString = null; 
                }
            }
        }
        return responseBodyString;
    }

    public static void setupOkHttpRequestHook() {
        // okhttp3.Call.execute
        hookOkHttpMethod("setupOkHttpRequestHook_execute", "Already Executed", "execute"); 
        // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
        hookOkHttpMethod("setupOkHttp2RequestHook_intercept", "Canceled", "intercept"); 
    }

    private static void hookOkHttpMethod(String cacheKeySuffix, String methodDescription, String methodName) {
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

                            Triple<String, Object, Object> bodyData = getOkHttpResponseBodyAndMediaType(response, url);
                            String responseBodyString = bodyData.getFirst();
                            Object mediaTypeObj = bodyData.getSecond();
                            Object originalResponseBody = bodyData.getThird();

                            if (shouldBlockHttpRequest(url, " OKHTTP", request, response, responseBodyString, stackTrace)) {
                                param.setThrowable(new IOException("Request blocked by AdClose"));
                            } else {
                                recreateOkHttpResponse(param, response, responseBodyString, mediaTypeObj, url);
                            }
                        } catch (IOException e) {
                            param.setThrowable(e);
                        } catch (Exception e) {
                            XposedBridge.log(LOG_PREFIX + "Error processing OkHttp hook for method " + methodData.getName() + ": " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Error hooking OkHttp method: " + methodData + ", " + e.getMessage());
                }
            }
        }
    }

    private static Triple<String, Object, Object> getOkHttpResponseBodyAndMediaType(Object response, URL url) {
        String responseBodyString = null;
        Object originalResponseBody = XposedHelpers.callMethod(response, "body");
        Object mediaTypeObj = null;

        if (originalResponseBody != null) {
            mediaTypeObj = XposedHelpers.callMethod(originalResponseBody, "contentType");
            if (isJson(mediaTypeObj)) {
                try {
                    responseBodyString = (String) XposedHelpers.callMethod(originalResponseBody, "string");
                } catch (Throwable e) {
                    XposedBridge.log(LOG_PREFIX + "Error reading OkHttp Response Body for URL " + url + ": " + e.getMessage());
                    responseBodyString = null;
                }
            }
        }
        return new Triple<>(responseBodyString, mediaTypeObj, originalResponseBody);
    }

    private static void recreateOkHttpResponse(XC_MethodHook.MethodHookParam param, Object response,
                                               String responseBodyString, Object mediaTypeObj, URL url) {
        if (responseBodyString != null && mediaTypeObj != null) {
            try {
                Class<?> okhttp3ResponseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.getClassLoader());

                Method createMethod = XposedHelpers.findMethodExact(okhttp3ResponseBodyClass, "create", mediaTypeObj.getClass(), String.class);
                Object newResponseBody = createMethod.invoke(null, mediaTypeObj, responseBodyString);

                Object responseBuilder = XposedHelpers.callMethod(response, "newBuilder");
                XposedHelpers.callMethod(responseBuilder, "body", newResponseBody);
                param.setResult(XposedHelpers.callMethod(responseBuilder, "build"));
            } catch (Throwable e) {
                XposedBridge.log(LOG_PREFIX + "Failed to recreate OkHttp Response Body for unblocked request (URL: " + url + "): " + e.getMessage());
            }
        }
    }

    private static boolean shouldBlockHttpRequest(final URL url, final String requestFrameworkType,
                                                  Object request, Object response, String responseBody, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            RequestDetails details = new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, responseBody, stack);

            return checkShouldBlockRequest(formatUrlWithoutQuery(url), details, requestFrameworkType);

        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Exception in shouldBlockHttpRequest: " + e.getMessage());
            return false;
        }
    }

    public static void setupWebViewRequestHook() {
        if (EMPTY_WEB_RESPONSE == null) {
            EMPTY_WEB_RESPONSE = createEmptyWebResourceResponse();
        }

        setupWebViewRequestHookInternal(
            WebView.class.getName(),
            WebViewClient.class.getName(),
            WebResourceRequest.class.getName(),
            applicationContext.getClassLoader()
        );
    }

    private static void setupWebViewRequestHookInternal(
        String webViewClass,
        String webViewClientClass,
        String webResourceRequestClass,
        ClassLoader classLoader
    ) {
        HookUtil.findAndHookMethod(
            webViewClass,
            "setWebViewClient",
            new Object[]{webViewClientClass},
            "before",
            param -> {
                Object client = param.args[0];
                if (client != null) {
                    String clientClassName = client.getClass().getName();
                    hookClientMethods(clientClassName, classLoader, webViewClass, webResourceRequestClass);
                }
            },
            classLoader
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
            String responseBody = null;

            if (request != null) {
                method = (String) XposedHelpers.callMethod(request, "getMethod");
                Object webUrl = XposedHelpers.callMethod(request, "getUrl");
                requestHeaders = XposedHelpers.callMethod(request, "getRequestHeaders");
                urlString = webUrl.toString();
            }

            int responseCode = 0;
            String responseMessage = null;
            Object responseHeaders = null;

            if (response != null && response instanceof WebResourceResponse) {
                WebResourceResponse webResponse = (WebResourceResponse) response;
                responseHeaders = webResponse.getResponseHeaders();
                responseCode = webResponse.getStatusCode();
                responseMessage = webResponse.getReasonPhrase();
            }

            String stackTrace = HookUtil.getFormattedStackTrace();

            RequestDetails details = new RequestDetails(method, urlString, requestHeaders, responseCode, responseMessage, responseHeaders, responseBody, stackTrace);

            if (urlString != null) {
                return checkShouldBlockRequest(formatUrlWithoutQuery(Uri.parse(urlString)), details, " Web");
            }
            return false;
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error processing web request: " + e.getMessage());
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
                new ByteArrayInputStream(new byte[0])
            );
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error creating empty WebResourceResponse: " + e.getMessage());
            return null;
        }
    }

    private static String compressString(String data) throws IOException {
        if (data == null || data.isEmpty()) {
            return data;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data.getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressed = bos.toByteArray();
        return Base64.encodeToString(compressed, Base64.DEFAULT);
    }

    private static void sendBroadcast(
        String requestType, boolean shouldBlock, String blockRuleType, String ruleUrl,
        String requestValue, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, ruleUrl, blockRuleType, requestValue, details);
        sendBlockedRequestBroadcast(shouldBlock ? "block" : "pass", requestType, shouldBlock, ruleUrl, blockRuleType, requestValue, details);
    }

    private static void sendBlockedRequestBroadcast(
        String type, @Nullable String requestType, @Nullable Boolean isBlocked,
        @Nullable String ruleUrl, @Nullable String blockRuleType, String requestValue,
        RequestDetails details) {
        if (details == null) {
            Log.w(LOG_PREFIX, "sendBlockedRequestBroadcast: RequestDetails are null, cannot send broadcast.");
            return;
        }

        String dnsHost = details.getDnsHost();
        String urlString = details.getUrlString();

        if (dnsHost != null && !dnsHost.isEmpty()) {
            if (dnsHostCache.putIfAbsent(dnsHost, true) != null) {
                return;
            }
        } else if (urlString != null && !urlString.isEmpty()) {
            if (urlStringCache.putIfAbsent(urlString, true) != null) {
                return;
            }
        } else {
            Log.d(LOG_PREFIX, "sendBlockedRequestBroadcast: Neither DNS host nor URL string available for deduplication.");
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
            String responseBody = details.getResponseBody();
            String stackTrace = details.getStack();
            String fullAddress = details.getFullAddress();

            boolean isBodyCompressed = false;
            if (responseBody != null && responseBody.getBytes(StandardCharsets.UTF_8).length > COMPRESSION_THRESHOLD_BYTES) {
                try {
                    responseBody = compressString(responseBody);
                    isBodyCompressed = true;
                } catch (IOException e) {
                    Log.e(LOG_PREFIX, "Failed to compress response body", e);
                    isBodyCompressed = false; 
                }
            }

            BlockedRequest blockedRequest = new BlockedRequest(
                appName,
                packageName,
                requestValue,
                System.currentTimeMillis(),
                type,
                isBlocked,
                ruleUrl,
                blockRuleType,
                method,
                urlString,
                requestHeaders,
                responseCode,
                responseMessage,
                responseHeaders,
                responseBody,
                stackTrace,
                dnsHost,
                fullAddress,
                isBodyCompressed
            );

            intent.putExtra("request", blockedRequest);
            applicationContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(LOG_PREFIX, "sendBlockedRequestBroadcast: Error broadcasting request", e);
        }
    }
}
