package com.close.hook.ads.data.model;

public class RequestDetails {
    private final String method;
    private final String urlString;
    private final Object requestHeaders;
    private final int responseCode;
    private final String responseMessage;
    private final Object responseHeaders;
    private final String stack;
    private final String dnsHost;
    private final String dnsCidr;
    private final String fullAddress;

    // WebView | HTTP/HTTPS 请求构造函数
    public RequestDetails(String method, String urlString, Object requestHeaders, int responseCode, String responseMessage, Object responseHeaders, String stack) {
        this.method = method;
        this.urlString = urlString;
        this.requestHeaders = requestHeaders;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.responseHeaders = responseHeaders;
        this.stack = stack;
        this.dnsHost = null;
        this.dnsCidr = null;
        this.fullAddress = null;
    }

    // DNS 请求构造函数
    public RequestDetails(String dnsHost, String dnsCidr, String fullAddress, String stack) {
        this.method = null;
        this.urlString = null;
        this.requestHeaders = null;
        this.responseCode = -1;
        this.responseMessage = null;
        this.responseHeaders = null;
        this.stack = stack;
        this.dnsHost = dnsHost;
        this.dnsCidr = dnsCidr;
        this.fullAddress = fullAddress;
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

    public String getDnsHost() {
        return dnsHost;
    }

    public String getDnsCidr() {
        return dnsCidr;
    }

    public String getFullAddress() {
        return fullAddress;
    }
}
