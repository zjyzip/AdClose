package com.close.hook.ads.hook.gc.network;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class BlockedURLConnection extends HttpURLConnection {
    private final Map<String, List<String>> headers = new HashMap<>();

    protected BlockedURLConnection(URL url) {
        super(url);
    }

    @Override
    public void connect() throws IOException {
        if (!connected) {
            connected = true;
        }
    }

    @Override
    public void disconnect() {
        if (connected) {
            connected = false;
        }
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) {
                // No operation
            }
        };
    }

    @Override
    public String getResponseMessage() {
        return "Forbidden";
    }

    @Override
    public int getResponseCode() {
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
        return new HashMap<>(headers);
    }
}
