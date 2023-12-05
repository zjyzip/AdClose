package com.close.hook.ads.data.model;

import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

public class AppInfo implements Parcelable {
    private final String appName;
    private final String packageName;
    private final Drawable appIcon;
    private final String versionName;

    public AppInfo(String appName, String packageName, Drawable appIcon, String versionName) {
        this.appName = appName;
        this.packageName = packageName;
        this.appIcon = appIcon;
        this.versionName = versionName;
    }

    protected AppInfo(Parcel in) {
        appName = in.readString();
        packageName = in.readString();
        versionName = in.readString();
        appIcon = (Drawable) in.readValue(Drawable.class.getClassLoader());
    }

    public String getAppName() {
        return appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public String getVersionName() {
        return versionName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppInfo)) return false;
        AppInfo appInfo = (AppInfo) o;
        return Objects.equals(getPackageName(), appInfo.getPackageName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getPackageName());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(appName);
        dest.writeString(packageName);
        dest.writeString(versionName);
        dest.writeValue(appIcon);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AppInfo> CREATOR = new Creator<AppInfo>() {
        @Override
        public AppInfo createFromParcel(Parcel in) {
            return new AppInfo(in);
        }

        @Override
        public AppInfo[] newArray(int size) {
            return new AppInfo[size];
        }
    };
}
