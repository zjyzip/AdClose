package com.close.hook.ads.data.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

public class BlockedRequest implements Parcelable {
    public String appName;
    public String packageName;
    public String request;
    public long timestamp;
    public String requestType;
    public Boolean isBlocked;

    public BlockedRequest(String appName, String packageName, String request, long timestamp, @Nullable String requestType, @Nullable Boolean isBlocked) {
        this.appName = appName;
        this.packageName = packageName;
        this.request = request;
        this.timestamp = timestamp;
        this.requestType = requestType;
        this.isBlocked = isBlocked;
    }

    protected BlockedRequest(Parcel in) {
        appName = in.readString();
        packageName = in.readString();
        request = in.readString();
        timestamp = in.readLong();
        requestType = in.readString();
        byte tmpIsBlocked = in.readByte();
        isBlocked = tmpIsBlocked == 0 ? null : tmpIsBlocked == 1;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(appName);
        dest.writeString(packageName);
        dest.writeString(request);
        dest.writeLong(timestamp);
        dest.writeString(requestType);
        dest.writeByte((byte) (isBlocked == null ? 0 : isBlocked ? 1 : 2));
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
