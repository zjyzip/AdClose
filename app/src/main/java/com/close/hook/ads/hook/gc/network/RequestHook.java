package com.close.hook.ads.hook.gc.network;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.provider.UrlContentProvider;
import com.close.hook.ads.provider.ResponseBodyContentProvider;
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.util.EncryptionUtil;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import kotlin.Triple;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RequestHook {

    private static final String LOG_PREFIX = "[RequestHook] ";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private static volatile Object EMPTY_WEB_RESPONSE = null;

    private static final ConcurrentHashMap<String, Boolean> dnsHostCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> urlStringCache = new ConcurrentHashMap<>();

    private static Context applicationContext;

    private static Class<?> responseCls;
    private static Class<?> responseBodyCls;
    private static Class<?> mediaTypeCls;
    private static Class<?> headersCls;
    private static Class<?> okioBufferCls;

    private static Class<?> androidOkioBufferClass;
    private static Class<?> androidOkioSourceClass;
    private static Class<?> androidBufferedSourceClass;
    private static Class<?> androidOkioSinkClass;

    private static final Uri URL_CONTENT_URI = new Uri.Builder()
            .scheme("content")
            .authority(UrlContentProvider.AUTHORITY)
            .appendPath(UrlContentProvider.URL_TABLE_NAME)
            .build();

    private static final Uri RESPONSE_BODY_CONTENT_URI = ResponseBodyContentProvider.CONTENT_URI;

    private static final Cache<String, Triple<Boolean, String, String>> queryCache = CacheBuilder.newBuilder()
            .maximumSize(8192)
            .expireAfterAccess(4, TimeUnit.HOURS)
            .softValues()
            .build();

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
            XposedBridge.log(LOG_PREFIX + "Init error: " + e.getMessage());
        }
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
            XposedBridge.log(LOG_PREFIX + "URL format error: " + e.getMessage());
        }
        return urlObject != null ? urlObject.toString() : "";
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

        try (Cursor cursor = contentResolver.query(URL_CONTENT_URI, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int urlTypeIndex = cursor.getColumnIndexOrThrow(Url.URL_TYPE);
                int urlAddressIndex = cursor.getColumnIndexOrThrow(Url.URL_ADDRESS);
                String urlType = cursor.getString(urlTypeIndex);
                String urlValue = cursor.getString(urlAddressIndex);
                return new Triple<>(true, urlType, urlValue);
            }
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Query error: " + e.getMessage());
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
            fullAddress = ((InetAddress) result).getHostAddress();
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
            androidOkioBufferClass = XposedHelpers.findClass("com.android.okhttp.okio.Buffer", applicationContext.getClassLoader());
            androidOkioSourceClass = XposedHelpers.findClass("com.android.okhttp.okio.Source", applicationContext.getClassLoader());
            androidBufferedSourceClass = XposedHelpers.findClass("com.android.okhttp.okio.BufferedSource", applicationContext.getClassLoader());
            androidOkioSinkClass = XposedHelpers.findClass("com.android.okhttp.okio.Sink", applicationContext.getClassLoader());
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "Error caching com.android.okhttp.okio classes: " + e.getMessage());
        }

        HookUtil.hookAllMethods(
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
            "getResponse",
            "after",
            param -> {
                try {
                    Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");
                    if (httpEngine == null) {
                  //    XposedBridge.log(LOG_PREFIX + "HttpEngine is null.");
                        return;
                    }

                    if (!((boolean) XposedHelpers.callMethod(httpEngine, "hasResponse"))) {
                        return;
                    }

                    Object userResponse = XposedHelpers.getObjectField(httpEngine, "userResponse");
                    Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                    URL url = new URL(XposedHelpers.callMethod(request, "urlString").toString());

                    String responseBodyString = readOkioHttpResponseBody(userResponse);
                    String stackTrace = HookUtil.getFormattedStackTrace();

                    if (shouldBlockHttpRequest(url, " HTTP", request, userResponse, responseBodyString, stackTrace)) {
                        Object emptyResponse = createEmptyResponseForHttp(userResponse);
                        if (emptyResponse != null) {
                            XposedHelpers.setObjectField(httpEngine, "userResponse", emptyResponse);
                        }
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "HTTP hook error: " + e.getMessage());
                }
            },
            applicationContext.getClassLoader()
        );
    }

    private static Object createEmptyResponseForHttp(Object response) throws Exception {
        if (response == null || !response.getClass().getName().equals("com.android.okhttp.Response")) {
            return null;
        }

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
                if (androidOkioBufferClass == null || androidBufferedSourceClass == null || androidOkioSinkClass == null) {
                    return null;
                }

                try {
                    Object originalBufferedSource = XposedHelpers.callMethod(originalResponseBody, "source");
                    
                    if (originalBufferedSource == null) {
                        XposedBridge.log(LOG_PREFIX + "Original BufferedSource is null.");
                        return null;
                    }

                    Object tempBuffer = androidOkioBufferClass.getConstructor().newInstance();
                    
                    Method readAllMethod = XposedHelpers.findMethodExact(androidBufferedSourceClass, "readAll", androidOkioSinkClass);
                    readAllMethod.invoke(originalBufferedSource, tempBuffer);
                    
                    Method readUtf8Method = XposedHelpers.findMethodExact(androidOkioBufferClass, "readUtf8");
                    responseBodyString = (String) readUtf8Method.invoke(tempBuffer);

                } catch (Throwable e) {
                    XposedBridge.log(LOG_PREFIX + "Error reading HTTP Response Body (com.android.okhttp.okio): " + e.getMessage());
                    responseBodyString = null; 
                }
            }
        }
        return responseBodyString;
    }

    public static void setupOkHttpRequestHook() {
        try {
            responseCls = XposedHelpers.findClass("okhttp3.Response", applicationContext.getClassLoader());
            responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.getClassLoader());
            mediaTypeCls = XposedHelpers.findClass("okhttp3.MediaType", applicationContext.getClassLoader());
            headersCls = XposedHelpers.findClass("okhttp3.Headers", applicationContext.getClassLoader());
            okioBufferCls = XposedHelpers.findClassIfExists("okio.Buffer", applicationContext.getClassLoader());
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "OkHttp load error: " + e.getMessage());
        }

        if (responseCls == null || responseBodyCls == null || mediaTypeCls == null || headersCls == null) {
            XposedBridge.log(LOG_PREFIX + "OkHttp classes missing.");
            return;
        }

        HookUtil.hookAllMethods(responseCls, "body", "after", param -> {
            Object responseBody = param.getResult();
            if (responseBody == null) {
                return;
            }

            Object response = param.thisObject;

            try {
                URL url = new URL(XposedHelpers.callMethod(XposedHelpers.callMethod(response, "request"), "url").toString());
                Object request = XposedHelpers.callMethod(response, "request");

                String bodyString = getOkHttpResponseBodyString(responseBody, response);

                String method = (String) XposedHelpers.callMethod(request, "method");
                Object requestHeaders = XposedHelpers.callMethod(request, "headers");
                int code = (int) XposedHelpers.callMethod(response, "code");
                String message = (String) XposedHelpers.callMethod(response, "message");
                Object responseHeaders = XposedHelpers.callMethod(response, "headers");

                String stackTrace = HookUtil.getFormattedStackTrace();

                if (shouldBlockHttpRequest(url, " OKHTTP", request, response, bodyString, stackTrace)) {
                    param.setThrowable(new IOException("Request blocked by AdClose"));
                }
            } catch (IOException e) {
                param.setThrowable(e);
            } catch (Throwable e) {
                XposedBridge.log(LOG_PREFIX + "OkHttp hook error: " + e.getMessage());
            }
        }, applicationContext.getClassLoader());
    }

    private static String getOkHttpResponseBodyString(Object responseBody, Object response) {
        if (okioBufferCls == null) {
      //    XposedBridge.log(LOG_PREFIX + "Okio buffer missing.");
            return null;
        }

        Object mediaType = XposedHelpers.callMethod(responseBody, "contentType");

        if (isJson(mediaType)) {
            try {
                Object originalSource = XposedHelpers.callMethod(responseBody, "source");
                Object buffer = XposedHelpers.callMethod(originalSource, "buffer");
                XposedHelpers.callMethod(originalSource, "request", Long.MAX_VALUE);
                Object clonedBuffer = XposedHelpers.callMethod(buffer, "clone");

                String contentEncoding = (String) XposedHelpers.callMethod(XposedHelpers.callMethod(response, "headers"), "get", "Content-Encoding");
                boolean isGzipped = "gzip".equalsIgnoreCase(contentEncoding);

                Method inputStreamMethod = XposedHelpers.findMethodExact(okioBufferCls, "inputStream");
                Method readStringMethod = XposedHelpers.findMethodExact(okioBufferCls, "readString", Charset.class);
                
                if (isGzipped) {
                    try (GZIPInputStream gzipIs = new GZIPInputStream((java.io.InputStream) inputStreamMethod.invoke(clonedBuffer));
                         BufferedReader reader = new BufferedReader(new InputStreamReader(gzipIs, UTF8))) {
                        StringBuilder out = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            out.append(line);
                        }
                        return out.toString();
                    } catch (IOException e) {
                        XposedBridge.log(LOG_PREFIX + "GZIP decomp error: " + e.getMessage());
                        return (String) readStringMethod.invoke(clonedBuffer, UTF8);
                    }
                } else {
                    Charset charset = UTF8;
                    if (mediaType != null) {
                        try {
                            Method charsetMethod = XposedHelpers.findMethodExact(mediaTypeCls, "charset", Charset.class);
                            charset = (Charset) charsetMethod.invoke(mediaType, UTF8);
                        } catch (Throwable e) {
                            XposedBridge.log(LOG_PREFIX + "Charset get error: " + e.getMessage());
                        }
                    }
                    return (String) readStringMethod.invoke(clonedBuffer, charset);
                }
            } catch (Throwable e) {
                XposedBridge.log(LOG_PREFIX + "Okio reflection error: " + e.getMessage());
                return null;
            }
        }
        return null;
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
            XposedBridge.log(LOG_PREFIX + "Block check error: " + e.getMessage());
            return false;
        }
    }

    public static void setupWebViewRequestHook() {
        if (EMPTY_WEB_RESPONSE == null) {
            EMPTY_WEB_RESPONSE = createEmptyWebResourceResponse();
        }

        HookUtil.findAndHookMethod(
            WebView.class,
            "setWebViewClient",
            new Object[]{WebViewClient.class},
            "before",
            param -> {
                Object client = param.args[0];
                if (client != null) {
                    hookClientMethods(client.getClass().getName(), applicationContext.getClassLoader());
                }
            },
            applicationContext.getClassLoader()
        );
    }

    private static void hookClientMethods(
        String clientClassName,
        ClassLoader classLoader
    ) {
        XposedBridge.log(LOG_PREFIX + "WebViewClient set: " + clientClassName);

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldInterceptRequest",
            new Object[]{WebView.class, WebResourceRequest.class},
            "after",
            param -> {
                if (processWebRequest(param.args[1], param.getResult())) {
                    param.setResult(EMPTY_WEB_RESPONSE);
                }
            },
            classLoader
        );

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldOverrideUrlLoading",
            new Object[]{WebView.class, WebResourceRequest.class},
            "after",
            param -> {
                if (processWebRequest(param.args[1], null)) {
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
                urlString = XposedHelpers.callMethod(request, "getUrl").toString();
                requestHeaders = XposedHelpers.callMethod(request, "getRequestHeaders");
            }

            int responseCode = 0;
            String responseMessage = null;
            Object responseHeaders = null;

            if (response instanceof WebResourceResponse) {
                WebResourceResponse webResponse = (WebResourceResponse) response;
                responseHeaders = webResponse.getResponseHeaders();
                responseCode = webResponse.getStatusCode();
                responseMessage = webResponse.getReasonPhrase();
            }

            RequestDetails details = new RequestDetails(method, urlString, requestHeaders, responseCode, responseMessage, responseHeaders, responseBody, HookUtil.getFormattedStackTrace());

            return urlString != null && checkShouldBlockRequest(formatUrlWithoutQuery(Uri.parse(urlString)), details, " Web");
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Web request error: " + e.getMessage());
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
            XposedBridge.log(LOG_PREFIX + "Empty response error: " + e.getMessage());
            return null;
        }
    }

    private static void sendBroadcast(
        String requestType, boolean shouldBlock, String blockRuleType, String ruleUrl,
        String requestValue, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, ruleUrl, blockRuleType, requestValue, details);
        sendBlockedRequestBroadcast(shouldBlock ? "block" : "pass", requestType, shouldBlock, ruleUrl, blockRuleType, requestValue, details);
    }

    private static void sendBlockedRequestBroadcast(
            String type, String requestType, Boolean isBlocked,
            String ruleUrl, String blockRuleType, String requestValue,
            RequestDetails details) {
        if (details == null) {
            Log.w(LOG_PREFIX, "Broadcast details null.");
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
            Log.d(LOG_PREFIX, "No DNS or URL.");
        }

        Intent intent = new Intent("com.rikkati.REQUEST");

        try {
            String appName = applicationContext.getApplicationInfo().loadLabel(applicationContext.getPackageManager()).toString() + requestType;
            String packageName = applicationContext.getPackageName();
            String responseBodyUriString = null;

            try {
                String encryptedResponseBody = EncryptionUtil.encrypt(details.getResponseBody());
                ContentValues values = new ContentValues();
                values.put("body_content", encryptedResponseBody);
                Uri uri = applicationContext.getContentResolver().insert(RESPONSE_BODY_CONTENT_URI, values);
                if (uri != null) {
                    responseBodyUriString = uri.toString();
                } else {
                    Log.e(LOG_PREFIX, "ContentProvider insert fail.");
                }
            } catch (Exception e) {
                Log.e(LOG_PREFIX, "Encryption error: " + e.getMessage(), e);
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
                details.getMethod(),
                details.getUrlString(),
                details.getRequestHeaders() != null ? details.getRequestHeaders().toString() : null,
                details.getResponseCode(),
                details.getResponseMessage(),
                details.getResponseHeaders() != null ? details.getResponseHeaders().toString() : null,
                responseBodyUriString,
                details.getStack(),
                details.getDnsHost(),
                details.getFullAddress()
            );

            intent.putExtra("request", blockedRequest);
            applicationContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(LOG_PREFIX, "Broadcast send error.", e);
        }
    }
}
