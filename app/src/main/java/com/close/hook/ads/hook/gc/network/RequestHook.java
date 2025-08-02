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
import com.close.hook.ads.data.model.RequestInfo;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.provider.UrlContentProvider;
import com.close.hook.ads.provider.ResponseBodyContentProvider;
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.util.EncryptionUtil;
import com.close.hook.ads.preference.HookPrefs;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
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
    private static HookPrefs hookPrefs;

    private static Class<?> responseCls;
    private static Class<?> responseBodyCls;
    private static Class<?> okioBufferCls;
    private static Class<?> androidOkioBufferClass;

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
                hookPrefs = HookPrefs.Companion.getXpInstance();
                setupDNSRequestHook();
                setupHttpRequestHook();
                setupOkHttpRequestHook();
                setupWebViewRequestHook();
            });
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Init error: " + e.getMessage());
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

    private static boolean checkShouldBlockRequest(RequestInfo info) {
        String[] queryTypes = {"URL", "Domain", "KeyWord"};
        for (String queryType : queryTypes) {
            String processedValue = queryType.equals("Domain") ? AppUtils.INSTANCE.extractHostOrSelf(info.getRequestValue()) : info.getRequestValue();
            Triple<Boolean, String, String> matchResult = queryContentProvider(queryType, processedValue);
            if (matchResult.getFirst()) {
                sendBroadcast(info, true, matchResult.getSecond(), matchResult.getThird());
                return true;
            }
        }
        sendBroadcast(info, false, null, null);
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
        RequestInfo info = new RequestInfo(" DNS", host, null, null, null, -1, null, null, null, null, stackTrace, host, fullAddress);
        return checkShouldBlockRequest(info);
    }

    private static void setupHttpRequestHook() {
        HookUtil.hookAllMethods(
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
            "getResponse",
            "after",
            param -> {
                try {
                    Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");
                    if (httpEngine == null || !((boolean) XposedHelpers.callMethod(httpEngine, "hasResponse"))) {
                        return;
                    }

                    Object userResponse = XposedHelpers.getObjectField(httpEngine, "userResponse");
                    Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                    URL url = new URL(XposedHelpers.callMethod(request, "urlString").toString());
                    String stackTrace = HookUtil.getFormattedStackTrace();

                    byte[] responseBodyBytes = null;
                    String contentType = null;

                    if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && userResponse != null) {
                        try {
                            Object originalResponseBody = XposedHelpers.callMethod(userResponse, "body");
                            if (originalResponseBody != null) {
                                Object originalSource = XposedHelpers.callMethod(originalResponseBody, "source");
                                if (originalSource != null) {
                                    responseBodyBytes = readAndroidOkHttpBodyBytes(originalSource, userResponse);

                                    Object mediaTypeObj = XposedHelpers.callMethod(originalResponseBody, "contentType");
                                    if (mediaTypeObj != null) {
                                        contentType = mediaTypeObj.toString();
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            XposedBridge.log(LOG_PREFIX + "Android OkHttp body reading error: " + e.getMessage());
                        }
                    }

                    RequestInfo info = buildRequestInfo(url, " HTTP", request, userResponse, responseBodyBytes, contentType, stackTrace);

                    if (info != null && checkShouldBlockRequest(info)) {
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

    private static byte[] readAndroidOkHttpBodyBytes(Object originalSource, Object userResponse) {
        try {
            XposedHelpers.callMethod(originalSource, "request", Long.MAX_VALUE);
            Object buffer = XposedHelpers.callMethod(originalSource, "buffer");
            Object clonedBuffer = XposedHelpers.callMethod(buffer, "clone");

            String contentEncoding = (String) XposedHelpers.callMethod(XposedHelpers.callMethod(userResponse, "headers"), "get", "Content-Encoding");
            boolean isGzipped = "gzip".equalsIgnoreCase(contentEncoding);

            Class<?> androidOkioBufferClass = XposedHelpers.findClass("com.android.okhttp.okio.Buffer", applicationContext.getClassLoader());
            Method inputStreamMethod = XposedHelpers.findMethodExact(androidOkioBufferClass, "inputStream");

            if (isGzipped) {
                try (GZIPInputStream gzipIs = new GZIPInputStream((InputStream) inputStreamMethod.invoke(clonedBuffer))) {
                    return readStream(gzipIs);
                } catch (IOException e) {
                    XposedBridge.log(LOG_PREFIX + "GZIP decomp error: " + e.getMessage());
                    return readStream((InputStream) inputStreamMethod.invoke(clonedBuffer));
                }
            } else {
                return readStream((InputStream) inputStreamMethod.invoke(clonedBuffer));
            }
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "Android Okio body reading error: " + e.getMessage());
            return null;
        }
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

    public static void setupOkHttpRequestHook() {
        try {
            responseCls = XposedHelpers.findClass("okhttp3.Response", applicationContext.getClassLoader());
            responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.getClassLoader());
            okioBufferCls = XposedHelpers.findClassIfExists("okio.Buffer", applicationContext.getClassLoader());
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "OkHttp load error: " + e.getMessage());
        }

        if (responseCls == null || responseBodyCls == null) {
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
                String stackTrace = HookUtil.getFormattedStackTrace();

                byte[] bodyBytes = null;
                String contentType = null;

                if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                    Object mediaType = XposedHelpers.callMethod(responseBody, "contentType");
                    if (mediaType != null) {
                        contentType = mediaType.toString();
                    }
                    bodyBytes = getOkHttpResponseBodyBytes(responseBody, response);
                }

                RequestInfo info = buildRequestInfo(url, " OKHTTP", request, response, bodyBytes, contentType, stackTrace);

                if (info != null && checkShouldBlockRequest(info)) {
                    param.setThrowable(new IOException("Request blocked by AdClose"));
                }
            } catch (IOException e) {
                param.setThrowable(e);
            } catch (Throwable e) {
                XposedBridge.log(LOG_PREFIX + "OkHttp hook error: " + e.getMessage());
            }
        }, applicationContext.getClassLoader());
    }

    private static byte[] getOkHttpResponseBodyBytes(Object responseBody, Object response) {
        if (okioBufferCls == null) {
            return null;
        }
        try {
            Object originalSource = XposedHelpers.callMethod(responseBody, "source");
            Object buffer = XposedHelpers.callMethod(originalSource, "buffer");
            XposedHelpers.callMethod(originalSource, "request", Long.MAX_VALUE);
            Object clonedBuffer = XposedHelpers.callMethod(buffer, "clone");

            String contentEncoding = (String) XposedHelpers.callMethod(XposedHelpers.callMethod(response, "headers"), "get", "Content-Encoding");
            boolean isGzipped = "gzip".equalsIgnoreCase(contentEncoding);
            Method inputStreamMethod = XposedHelpers.findMethodExact(okioBufferCls, "inputStream");

            if (isGzipped) {
                try (GZIPInputStream gzipIs = new GZIPInputStream((InputStream) inputStreamMethod.invoke(clonedBuffer))) {
                    return readStream(gzipIs);
                } catch (IOException e) {
                    XposedBridge.log(LOG_PREFIX + "GZIP decomp error: " + e.getMessage());
                    return readStream((InputStream) inputStreamMethod.invoke(clonedBuffer));
                }
            } else {
                return readStream((InputStream) inputStreamMethod.invoke(clonedBuffer));
            }
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "Okio reflection error: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[4096];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    private static RequestInfo buildRequestInfo(final URL url, final String requestFrameworkType,
                                                  Object request, Object response, byte[] responseBody, String contentType, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            String requestHeaders = XposedHelpers.callMethod(request, "headers").toString();
            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            String responseHeaders = XposedHelpers.callMethod(response, "headers").toString();
            String formattedUrl = formatUrlWithoutQuery(url);
            return new RequestInfo(requestFrameworkType, formattedUrl, method, urlString,
                                   requestHeaders, code, message, responseHeaders, responseBody, contentType, stack, null, null);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Block check error: " + e.getMessage());
            return null;
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
                hookClientMethods(client.getClass().getName(), applicationContext.getClassLoader());
            },
            applicationContext.getClassLoader()
        );
    }

    private static void hookClientMethods(String clientClassName, ClassLoader classLoader) {
        XposedBridge.log(LOG_PREFIX + "WebViewClient set: " + clientClassName);

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldInterceptRequest",
            new Object[]{WebView.class, WebResourceRequest.class},
            "before",
            param -> {
                if (processWebRequest(param.args[1], null)) {
                    param.setResult(EMPTY_WEB_RESPONSE);
                }
            },
            classLoader
        );
        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldOverrideUrlLoading",
            new Object[]{WebView.class, WebResourceRequest.class},
            "before",
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
            String requestHeaders = null;
            if (request instanceof WebResourceRequest) {
                WebResourceRequest webRequest = (WebResourceRequest) request;
                method = webRequest.getMethod();
                urlString = webRequest.getUrl().toString();
                requestHeaders = (webRequest.getRequestHeaders() != null) ? webRequest.getRequestHeaders().toString() : null;
            }

            int responseCode = -1;
            String responseMessage = null;
            String responseHeaders = null;
            byte[] responseBody = null;
            String contentType = null;

            if (response instanceof WebResourceResponse) {
                WebResourceResponse webResponse = (WebResourceResponse) response;
                responseHeaders = (webResponse.getResponseHeaders() != null) ? webResponse.getResponseHeaders().toString() : null;
                responseCode = webResponse.getStatusCode();
                responseMessage = webResponse.getReasonPhrase();
                contentType = webResponse.getMimeType();

                if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                     try(InputStream is = webResponse.getData()) {
                         if (is != null) {
                             responseBody = readStream(is);
                         }
                     }
                }
            }

            String stack = HookUtil.getFormattedStackTrace();
            String formattedUrl = urlString != null ? formatUrlWithoutQuery(Uri.parse(urlString)) : null;

            if (formattedUrl != null) {
                RequestInfo info = new RequestInfo(
                    " Web", formattedUrl, method, urlString, requestHeaders,
                    responseCode, responseMessage, responseHeaders, responseBody, contentType,
                    stack, null, null
                );
                return checkShouldBlockRequest(info);
            }
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Web request error: " + e.getMessage());
        }
        return false;
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

    private static void sendBroadcast(RequestInfo info, boolean shouldBlock, String blockRuleType, String ruleUrl) {
        sendBlockedRequestBroadcast("all", info, shouldBlock, ruleUrl, blockRuleType);
        sendBlockedRequestBroadcast(shouldBlock ? "block" : "pass", info, shouldBlock, ruleUrl, blockRuleType);
    }

    private static void sendBlockedRequestBroadcast(
            String type, RequestInfo info, Boolean isBlocked,
            String ruleUrl, String blockRuleType) {
        if (info.getDnsHost() != null && !info.getDnsHost().isEmpty()) {
            if (dnsHostCache.putIfAbsent(info.getDnsHost(), true) != null) {
                return;
            }
        } else if (info.getUrlString() != null && !info.getUrlString().isEmpty()) {
            if (urlStringCache.putIfAbsent(info.getUrlString(), true) != null) {
                return;
            }
        } else {
            Log.d(LOG_PREFIX, "No DNS or URL.");
        }
        Intent intent = new Intent("com.rikkati.REQUEST");
        try {
            String appName = applicationContext.getApplicationInfo().loadLabel(applicationContext.getPackageManager()).toString() + info.getRequestType();
            String packageName = applicationContext.getPackageName();
            String responseBodyUriString = null;
            String responseBodyContentType = null;
            try {
                if (info.getResponseBody() != null) {
                    String encryptedResponseBody = EncryptionUtil.encrypt(info.getResponseBody());
                    ContentValues values = new ContentValues();
                    values.put("body_content", encryptedResponseBody);
                    values.put("mime_type", info.getResponseBodyContentType());
                    Uri uri = applicationContext.getContentResolver().insert(RESPONSE_BODY_CONTENT_URI, values);
                    if (uri != null) {
                        responseBodyUriString = uri.toString();
                        responseBodyContentType = info.getResponseBodyContentType();
                    } else {
                        Log.e(LOG_PREFIX, "ContentProvider insert fail.");
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_PREFIX, "Encryption error: " + e.getMessage(), e);
            }
            BlockedRequest blockedRequest = new BlockedRequest(
                appName,
                packageName,
                info.getRequestValue(),
                System.currentTimeMillis(),
                type,
                isBlocked,
                ruleUrl,
                blockRuleType,
                info.getMethod(),
                info.getUrlString(),
                info.getRequestHeaders(),
                info.getResponseCode(),
                info.getResponseMessage(),
                info.getResponseHeaders(),
                responseBodyUriString,
                responseBodyContentType,
                info.getStack(),
                info.getDnsHost(),
                info.getFullAddress()
            );
            intent.putExtra("request", blockedRequest);
            applicationContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(LOG_PREFIX, "Broadcast send error.", e);
        }
    }
}
