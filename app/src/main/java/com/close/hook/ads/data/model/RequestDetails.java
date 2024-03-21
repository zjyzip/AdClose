package com.close.hook.ads.data.model;

public class RequestDetails {
    private final String method;
    private final String urlString;
    private final Object requestHeaders;
    private final int responseCode;
    private final String responseMessage;
    private final Object responseHeaders;
    private final String stack;

    public RequestDetails(String method, String urlString, Object requestHeaders, int responseCode, String responseMessage, Object responseHeaders, String stack) {
        this.method = method;
        this.urlString = urlString;
        this.requestHeaders = requestHeaders;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.responseHeaders = responseHeaders;
        this.stack = stack;
    }

    public String getMethod() {
        return method;
    }

    public String getUrlString() {
        return urlString;
    }

    public Object getRequestHeaders() {
        return requestHeaders;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public Object getResponseHeaders() {
        return responseHeaders;
    }

    public String getStack() {
        return stack;
    }
}
