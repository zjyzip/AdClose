package com.close.hook.ads.data.module;

import androidx.annotation.Nullable;

import java.io.Serializable;

public class BlockedRequest implements Serializable {
    public String appName;
    public String packageName;
    public String request;
    public long timestamp;
    public String blockType;
    public Boolean isBlocked;

    public BlockedRequest(String appName, String packageName, String request, long timestamp, @Nullable String blockType, @Nullable Boolean isBlocked) {
        this.appName = appName;
        this.packageName = packageName;
        this.request = request;
        this.timestamp = timestamp;
        this.blockType = blockType;
        this.isBlocked = isBlocked;
    }
}
