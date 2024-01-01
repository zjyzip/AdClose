package com.close.hook.ads.data.model;

public class RequestDetails {
    private final String method;
    private final String urlString;
    private final Object requestHeaders;
    private final int responseCode;
    private final String responseMessage;
    private final Object responseHeaders;

    public RequestDetails(String method, String urlString, Object requestHeaders, int responseCode, String responseMessage, Object responseHeaders) {
        this.method = method;
        this.urlString = urlString;
        this.requestHeaders = requestHeaders;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.responseHeaders = responseHeaders;
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
}
