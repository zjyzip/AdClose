package com.close.hook.ads.data.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

public class BlockedRequest implements Parcelable {
    public String appName;
    public String packageName;
    public String request;
    public long timestamp;
    @Nullable
    public String requestType;
    @Nullable
    public Boolean isBlocked;
    @Nullable
    public String method;
    @Nullable
    public String urlString;
    @Nullable
    public String blockType;
    @Nullable
    public String requestHeaders;
    public int responseCode;
    @Nullable
    public String responseMessage;
    @Nullable
    public String responseHeaders;

    public BlockedRequest(String appName, String packageName, String request, long timestamp,
                          @Nullable String requestType, @Nullable Boolean isBlocked,@Nullable String blockType,
                          @Nullable String method, @Nullable String urlString, @Nullable String requestHeaders,
                          int responseCode, @Nullable String responseMessage,
                          @Nullable String responseHeaders) {
        this.appName = appName;
        this.packageName = packageName;
        this.request = request;
        this.timestamp = timestamp;
        this.requestType = requestType;
        this.isBlocked = isBlocked;
        this.blockType = blockType;
        this.method = method;
        this.urlString = urlString;
        this.requestHeaders = requestHeaders;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.responseHeaders = responseHeaders;
    }

    protected BlockedRequest(Parcel in) {
        appName = in.readString();
        packageName = in.readString();
        request = in.readString();
        timestamp = in.readLong();
        requestType = in.readString();
        byte tmpIsBlocked = in.readByte();
        isBlocked = tmpIsBlocked == 0 ? null : tmpIsBlocked == 1;
        method = in.readString();
        blockType = in.readString();
        urlString = in.readString();
        requestHeaders = in.readString();
        responseCode = in.readInt();
        responseMessage = in.readString();
        responseHeaders = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(appName);
        dest.writeString(packageName);
        dest.writeString(request);
        dest.writeLong(timestamp);
        dest.writeString(requestType);
        dest.writeByte((byte) (isBlocked == null ? 0 : isBlocked ? 1 : 2));
        dest.writeString(method);
        dest.writeString(blockType);
        dest.writeString(urlString);
        dest.writeString(requestHeaders);
        dest.writeInt(responseCode);
        dest.writeString(responseMessage);
        dest.writeString(responseHeaders);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BlockedRequest> CREATOR = new Creator<BlockedRequest>() {
        @Override
        public BlockedRequest createFromParcel(Parcel in) {
            return new BlockedRequest(in);
        }

        @Override
        public BlockedRequest[] newArray(int size) {
            return new BlockedRequest[size];
        }
    };
}
