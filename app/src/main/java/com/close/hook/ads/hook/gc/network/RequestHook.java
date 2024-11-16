package com.close.hook.ads.hook.gc.network;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import android.webkit.WebView;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.StringFinderKit;
import com.close.hook.ads.provider.UrlContentProvider;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.luckypray.dexkit.result.MethodData;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Triple;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";
    private static final Context APP_CONTEXT = ContextUtil.appContext;

    private static final Object EMPTY_WEB_RESPONSE = createEmptyWebResourceResponse();

    private static final ConcurrentHashMap<String, Boolean> dnsHostCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> urlStringCache = new ConcurrentHashMap<>();

    private static final Cache<String, Triple<Boolean, String, String>> queryCache = CacheBuilder.newBuilder()
        .maximumSize(12948)
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build();

    public static void init() {
        try {
            setupDNSRequestHook();
            setupHttpRequestHook();
            setupOkHttpRequestHook();
            setWebViewRequestHook();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
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

    private static String formatUrlWithoutQuery(URL url) {
        try {
            String decodedPath = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8.name());
            URL formattedUrl = new URL(url.getProtocol(), url.getHost(), url.getPort(), decodedPath);
            return formattedUrl.toExternalForm();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error formatting URL: " + e.getMessage());
            return null;
        }
    }

    private static boolean shouldBlockDnsRequest(final String host, final RequestDetails details) {
        return checkShouldBlockRequest(host, details, " DNS", "host");
    }

    private static boolean shouldBlockHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " HTTP", "url");
    }

    private static boolean shouldBlockOkHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " OKHTTP", "url");
    }

    private static boolean shouldBlockWebRequest(final String url, final RequestDetails details) {
        try {
            URL parsedUrl = new URL(url);
            String formattedUrl = formatUrlWithoutQuery(parsedUrl);
            return checkShouldBlockRequest(formattedUrl, details, " Web", "url");
        } catch (MalformedURLException e) {
            XposedBridge.log(LOG_PREFIX + "Invalid URL in shouldBlockWebRequest: " + e.getMessage());
        }
        return false;
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
        Triple<Boolean, String, String> result = queryCache.getIfPresent(cacheKey);
        if (result != null) {
            return result;
        }

        ContentResolver contentResolver = APP_CONTEXT.getContentResolver();
        Uri uri = new Uri.Builder()
            .scheme("content")
            .authority(UrlContentProvider.AUTHORITY)
            .appendPath(UrlContentProvider.URL_TABLE_NAME)
            .build();

        String[] projection = {Url.URL_TYPE, Url.URL_ADDRESS};
        String[] selectionArgs = {queryType, queryValue};

        try (Cursor cursor = contentResolver.query(uri, projection, null, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String urlType = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_TYPE));
                String urlValue = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_ADDRESS));

                result = new Triple<>(true, urlType, urlValue);
                queryCache.put(cacheKey, result);
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        result = new Triple<>(false, null, null);
        queryCache.put(cacheKey, result);
        return result;
    }

    private static void setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
            InetAddress.class,
            "getByName",
            new Object[]{String.class},
            "after",
            param -> {
                try {
                    String host = (String) param.args[0];
                    InetAddress inetAddress = (InetAddress) param.getResult();

                    if (host == null || inetAddress == null) return;

                    String cidr = calculateCidrNotation(inetAddress);
                    String fullAddress = inetAddress.getHostAddress();
                    String stackTrace = HookUtil.getFormattedStackTrace();

                    RequestDetails details = new RequestDetails(host, cidr, fullAddress, stackTrace);

                    if (shouldBlockDnsRequest(host, details)) {
                        param.setResult(null);
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Error in getByName hook: " + e.getMessage());
                }
            }
        );

        HookUtil.findAndHookMethod(
            InetAddress.class,
            "getAllByName",
            new Object[]{String.class},
            "after",
            param -> {
                try {
                    String host = (String) param.args[0];
                    InetAddress[] inetAddresses = (InetAddress[]) param.getResult();

                    if (host == null || inetAddresses == null || inetAddresses.length == 0) return;

                    StringBuilder cidrBuilder = new StringBuilder();
                    StringBuilder fullAddressBuilder = new StringBuilder();

                    for (InetAddress inetAddress : inetAddresses) {
                        String cidr = calculateCidrNotation(inetAddress);
                        String addr = inetAddress.getHostAddress();

                        if (cidrBuilder.length() > 0) {
                            cidrBuilder.append(", ");
                            fullAddressBuilder.append(", ");
                        }
                        cidrBuilder.append(cidr);
                        fullAddressBuilder.append(addr);
                    }

                    String cidr = cidrBuilder.toString();
                    String fullAddress = fullAddressBuilder.toString();
                    String stackTrace = HookUtil.getFormattedStackTrace();

                    RequestDetails details = new RequestDetails(host, cidr, fullAddress, stackTrace);

                    if (shouldBlockDnsRequest(host, details)) {
                        param.setResult(new InetAddress[0]);
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Error in getAllByName hook: " + e.getMessage());
                }
            }
        );
    }

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

                    if (shouldBlockHttpsRequest(url, details)) {
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

    public static void setupOkHttpRequestHook() {
        hookMethod("setupOkHttpRequestHook", "Already Executed", "execute");  // okhttp3.Call.execute -overload method
        hookMethod("setupOkHttp2RequestHook", "Canceled", "intercept");  // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
    }

    private static void hookMethod(String cacheKeySuffix, String methodDescription, String methodName) {
        String cacheKey = ContextUtil.appContext.getPackageName() + ":" + cacheKeySuffix;
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, methodDescription, methodName);

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(ContextUtil.appContext.getClassLoader());
                    XposedBridge.log(LOG_PREFIX+ "setupOkHttpRequestHook" + methodData);
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

                            if (shouldBlockOkHttpsRequest(url, details)) {
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

    public static void setWebViewRequestHook() {
        try {
            HookUtil.findAndHookMethod(
                WebView.class,
                "setWebViewClient",
                new Object[]{"android.webkit.WebViewClient"},
                "before",
                param -> {
                    Object client = param.args[0];
                    if (client != null) {
                        String clientClassName = client.getClass().getName();
                        XposedBridge.log(LOG_PREFIX + " - WebViewClient set: " + clientClassName);

                        hookClientMethods(clientClassName);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error hooking WebViewClient methods: " + e.getMessage());
        }
    }

    private static void hookClientMethods(String clientClassName) {
        try {
            HookUtil.findAndHookMethod(
                clientClassName,
                "shouldInterceptRequest",
                new Object[]{"android.webkit.WebView", "android.webkit.WebResourceRequest"},
                "after",
                param -> {
                    Object request = param.args[1];
                    if (request != null) {
                        String method = (String) XposedHelpers.callMethod(request, "getMethod");
                        Object webUrl = XposedHelpers.callMethod(request, "getUrl");
                        Object requestHeaders = XposedHelpers.callMethod(request, "getRequestHeaders");
                        String urlString = webUrl != null ? webUrl.toString() : null;

                        Object response = param.getResult();
                        int responseCode = 0;
                        String responseMessage = null;
                        Object responseHeaders = null;

                        if (response != null) {
                            try {
                                responseHeaders = XposedHelpers.callMethod(response, "getResponseHeaders");
                                responseCode = (int) XposedHelpers.callMethod(response, "getStatusCode");
                                responseMessage = (String) XposedHelpers.callMethod(response, "getReasonPhrase");
                            } catch (Exception e) {
                                XposedBridge.log(LOG_PREFIX + " - Error extracting response details: " + e.getMessage());
                            }
                        }

                        String stackTrace = HookUtil.getFormattedStackTrace();

                        RequestDetails details = new RequestDetails(
                            method, urlString, requestHeaders, responseCode, responseMessage, responseHeaders, stackTrace
                        );

                        if (shouldBlockWebRequest(urlString, details)) {
                            try {
                                param.setResult(EMPTY_WEB_RESPONSE);
                            } catch (Exception e) {
                                XposedBridge.log(LOG_PREFIX + "Error creating WebResourceResponse: " + e.getMessage());
                            }
                        }
                    }
                }, ContextUtil.appContext.getClassLoader()
            );
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error hooking WebViewClient methods: " + e.getMessage());
        }
    }

    private static Object createEmptyWebResourceResponse() {
        try {
            Class<?> webResourceResponseClass = Class.forName("android.webkit.WebResourceResponse");
            return webResourceResponseClass
                    .getConstructor(String.class, String.class, int.class, String.class, java.util.Map.class, java.io.InputStream.class)
                    .newInstance("text/plain", "UTF-8", 204, "No Content", null, null);
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
            String appName = APP_CONTEXT.getApplicationInfo().loadLabel(APP_CONTEXT.getPackageManager()).toString() + requestType;
            String packageName = APP_CONTEXT.getPackageName();

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
            APP_CONTEXT.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: Error broadcasting request", e);
        }
    }
}
