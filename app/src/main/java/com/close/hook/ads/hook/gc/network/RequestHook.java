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
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.StringFinderKit;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Map;
import kotlin.Triple;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.result.MethodData;

public class RequestHook {

    private static final String LOG_PREFIX = "[RequestHook] ";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private static volatile Object EMPTY_WEB_RESPONSE = null;

    private static final ConcurrentHashMap<String, Boolean> dnsHostCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> urlStringCache = new ConcurrentHashMap<>();

    private static Context applicationContext;
    private static HookPrefs hookPrefs;

    private static Class<?> responseBodyCls;

    private static Class<?> cronetHttpURLConnectionCls; // org.chromium.net.urlconnection.CronetHttpURLConnection
    private static Class<?> urlResponseInfoCls; // org.chromium.net.ResponseInfo
    private static Class<?> urlRequestCls; // org.chromium.net.UrlRequest
    private static Class<?> cronetInputStreamCls; // org.chromium.net.urlconnection.CronetInputStream

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

                // 受严重混淆问题，只做了某音的抓取。对于org.chromium.net类的Hook点需调整至onSucceeded/onResponseStarted，getResponse貌似不行(YouTube/PlayStote)，未进行更多的测试，弃坑。
                if (applicationContext.getPackageName().equals("com.ss.android.ugc.aweme")) {
                    setupCronetRequestHook();
                }
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
                                Object mediaTypeObj = XposedHelpers.callMethod(originalResponseBody, "contentType");
                                if (mediaTypeObj != null) {
                                    contentType = mediaTypeObj.toString();
                                }

                                InputStream is = (InputStream) XposedHelpers.callMethod(originalResponseBody, "byteStream");
                                responseBodyBytes = readStream(is);
                                is.close();

                                if (responseBodyBytes != null) {
                                    Class<?> responseBodyClass = XposedHelpers.findClass("com.android.okhttp.ResponseBody", applicationContext.getClassLoader());
                                    Class<?> mediaTypeClass = XposedHelpers.findClass("com.android.okhttp.MediaType", applicationContext.getClassLoader());
                                    Method createMethod = XposedHelpers.findMethodExact(responseBodyClass, "create", mediaTypeClass, byte[].class);
                                    Object newResponseBody = createMethod.invoke(null, mediaTypeObj, responseBodyBytes);

                                    XposedHelpers.setObjectField(userResponse, "body", newResponseBody);
                                }
                            }
                        } catch (Throwable e) {
                            XposedBridge.log(LOG_PREFIX + "Android OkHttp body reading error: " + e.getMessage());
                        }
                    }

                    RequestInfo info = buildRequestInfo(url, " HTTP", request, userResponse, responseBodyBytes, contentType, stackTrace);

                    if (checkShouldBlockRequest(info)) {
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

    public static void setupOkHttpRequestHook() {
        // okhttp3.Call.execute
        hookOkHttpMethod("setupOkHttpRequestHook_execute", "Already Executed", "execute");
        // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
        hookOkHttpMethod("setupOkHttp2RequestHook_intercept", "Canceled", "intercept");

        try {
            responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.getClassLoader());
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "OkHttp load error: " + e.getMessage());
        }
    }

    private static void hookOkHttpMethod(String cacheKeySuffix, String methodDescription, String methodName) {
        String cacheKey = applicationContext.getPackageName() + ":" + cacheKeySuffix;
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, methodDescription, methodName);

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(DexKitUtil.INSTANCE.getContext().getClassLoader());
                    XposedBridge.log(LOG_PREFIX + "setupOkHttpRequestHook " + methodData);

                    HookUtil.hookMethod(method, "after", param -> {
                        try {
                            Object response = param.getResult();
                            if (response == null) {
                                return;
                            }

                            Object request = XposedHelpers.callMethod(response, "request");
                            URL url = new URL(XposedHelpers.callMethod(request, "url").toString());
                            String stackTrace = HookUtil.getFormattedStackTrace();

                            byte[] bodyBytes = null;
                            String contentType = null;

                            if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                                Object responseBody = XposedHelpers.callMethod(response, "body");
                                if (responseBody != null) {
                                    try {
                                        Object mediaType = XposedHelpers.callMethod(responseBody, "contentType");
                                        if (mediaType != null) {
                                            contentType = mediaType.toString();
                                        }

                                        bodyBytes = (byte[]) XposedHelpers.callMethod(responseBody, "bytes");

                                        if (bodyBytes != null) {
                                            Object newResponseBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, bodyBytes);
                                            XposedHelpers.setObjectField(response, "body", newResponseBody);
                                        }
                                    } catch (Throwable e) {
                                        XposedBridge.log(LOG_PREFIX + "OkHttp body reading error: " + e.getMessage());
                                    }
                                }
                            }

                            RequestInfo info = buildRequestInfo(url, " OKHTTP", request, response, bodyBytes, contentType, stackTrace);

                            if (checkShouldBlockRequest(info)) {
                                param.setThrowable(new IOException("Request blocked by AdClose"));
                            }
                        } catch (IOException e) {
                            param.setThrowable(e);
                        } catch (Throwable e) {
                            XposedBridge.log(LOG_PREFIX + "OkHttp hook error: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Error hooking OkHttp method: " + methodData + ", " + e.getMessage());
                }
            }
        }
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

    public static void setupCronetRequestHook() {
        try {
            cronetHttpURLConnectionCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetHttpURLConnection", applicationContext.getClassLoader());
            urlResponseInfoCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.UrlResponseInfo", applicationContext.getClassLoader());
            urlRequestCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.UrlRequest", applicationContext.getClassLoader());
            cronetInputStreamCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetInputStream", applicationContext.getClassLoader());

            HookUtil.hookAllMethods(
                cronetHttpURLConnectionCls,
                "getResponse",
                "after",
                param -> {
                    try {
                        Object thisObject = param.thisObject;
                        Object requestObject = null; // mRequest
                        Object responseInfoObject = null; // mResponseInfo
                        Object inputStreamObject = null; // mInputStream

                        for (Field field : thisObject.getClass().getDeclaredFields()) {
                            field.setAccessible(true);
                            Object value = field.get(thisObject);
                            if (urlRequestCls.isInstance(value)) {
                                requestObject = value;
                            } else if (urlResponseInfoCls.isInstance(value)) {
                                responseInfoObject = value;
                            } else if (cronetInputStreamCls.isInstance(value)) {
                                inputStreamObject = value;
                            }
                        }

                        if (requestObject == null || responseInfoObject == null) {
                            return;
                        }

                        String initialUrl = null; // mInitialUrl
                        String method = null; // mInitialMethod
                        String requestHeaders = null; // mRequestHeaders

                        for (Field field : requestObject.getClass().getDeclaredFields()) {
                            field.setAccessible(true);
                            Object value = field.get(requestObject);
                            if (value instanceof String) {
                                String valueStr = (String) value;
                                if (valueStr.startsWith("http") && initialUrl == null) {
                                    initialUrl = valueStr;
                                } else if ((valueStr.equalsIgnoreCase("GET") || valueStr.equalsIgnoreCase("POST")) && method == null) {
                                    method = valueStr;
                                }
                            } else if (value instanceof List && requestHeaders == null) {
                                requestHeaders = value.toString();
                            }
                        }

                        String finalUrl = (String) XposedHelpers.callMethod(responseInfoObject, "getUrl");
                        int httpStatusCode = (int) XposedHelpers.callMethod(responseInfoObject, "getHttpStatusCode");
                        String httpStatusText = (String) XposedHelpers.callMethod(responseInfoObject, "getHttpStatusText");
                        Object responseHeadersMap = XposedHelpers.callMethod(responseInfoObject, "getAllHeaders");
                        String responseHeaders = responseHeadersMap != null ? responseHeadersMap.toString() : "";
                        String negotiatedProtocol = (String) XposedHelpers.callMethod(responseInfoObject, "getNegotiatedProtocol");

                        byte[] responseBody = null;
                        String contentType = null;

                        if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && httpStatusCode >= 200 && httpStatusCode < 400) {
                            if (inputStreamObject != null) {
                                Field bufferField = null;
                                try {
                                    for (Field field : inputStreamObject.getClass().getDeclaredFields()) {
                                        if (field.getType().equals(ByteBuffer.class)) {
                                            bufferField = field; // mBuffer
                                            bufferField.setAccessible(true);
                                            break;
                                        }
                                    }

                                    if (bufferField != null) {
                                        ByteBuffer buffer = (ByteBuffer) bufferField.get(inputStreamObject);
                                        if (buffer != null && buffer.hasArray()) {
                                            responseBody = buffer.array();
                                        }
                                    }
                                } catch (Throwable e) {
                                    XposedBridge.log(LOG_PREFIX + "Cronet buffer field access error: " + e.getMessage());
                                }

                                if (responseBody == null) {
                                    try {
                                        if (inputStreamObject instanceof InputStream) {
                                            InputStream is = (InputStream) inputStreamObject;
                                            responseBody = readStream(is);
                                            if (bufferField != null && responseBody != null) {
                                                bufferField.set(inputStreamObject, ByteBuffer.wrap(responseBody));
                                            }
                                        }
                                    } catch (Throwable e) {
                                        XposedBridge.log(LOG_PREFIX + "Cronet response body reading error: " + e.getMessage());
                                    }
                                }

                                if (responseHeadersMap instanceof Map) {
                                    Map<String, List<String>> headers = (Map<String, List<String>>) responseHeadersMap;
                                    List<String> contentTypeList = headers.get("Content-Type");
                                    if (contentTypeList != null && !contentTypeList.isEmpty()) {
                                        contentType = contentTypeList.get(0);
                                    }
                                }
                            }
                        }

                        String stackTrace = HookUtil.getFormattedStackTrace();
                        String formattedUrl = finalUrl != null ? formatUrlWithoutQuery(Uri.parse(finalUrl)) : null;

                        RequestInfo info = new RequestInfo(
                                " CRONET/" + negotiatedProtocol,
                                formattedUrl,
                                method,
                                finalUrl,
                                requestHeaders,
                                httpStatusCode,
                                httpStatusText,
                                responseHeaders,
                                responseBody,
                                contentType,
                                stackTrace,
                                null,
                                null
                        );

                        if (checkShouldBlockRequest(info)) {
                            param.setThrowable(new IOException("Request blocked by AdClose"));
                        }
                    } catch (Throwable e) {
                        XposedBridge.log(LOG_PREFIX + "Error in Cronet hook: " + e.getMessage());
                    }
                },
                applicationContext.getClassLoader()
            );
        } catch (Throwable e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up Cronet hook: " + e.getMessage());
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
