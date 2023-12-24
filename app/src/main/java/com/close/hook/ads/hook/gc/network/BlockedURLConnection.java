package com.close.hook.ads.hook.gc.network;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

public class BlockedURLConnection extends HttpURLConnection {
    private final Map<String, List<String>> headers = new HashMap<>();

    protected BlockedURLConnection(URL url) {
        super(url);
    }

    @Override
    public void connect() throws IOException {
        // 模拟连接过程，但不执行实际的网络连接
        connected = true;
    }

    @Override
    public void disconnect() {
        // 断开连接的模拟
        connected = false;
    }

    @Override
    public boolean usingProxy() {
        // 明确指出不使用代理
        return false;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        // 模拟空响应体
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        // 模拟输出流，实际不发送数据
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // 不执行操作
            }
        };
    }

    @Override
    public String getResponseMessage() throws IOException {
        return "Forbidden";
    }

    @Override
    public int getResponseCode() throws IOException {
        // 返回403 Forbidden来表示被阻止的连接
        return HttpURLConnection.HTTP_FORBIDDEN;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        headers.put(key, List.of(value));
    }

    @Override
    public void addRequestProperty(String key, String value) {
        headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    @Override
    public String getRequestProperty(String key) {
        List<String> values = headers.get(key);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        return headers;
    }

}
