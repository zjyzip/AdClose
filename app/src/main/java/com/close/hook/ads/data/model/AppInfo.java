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
    private final int versionCode;
    private final Long firstInstallTime;
    private final Long lastUpdateTime;
    private final long size;
    private final int targetSdk;
    private final int minSdk;
    private final int isAppEnable;
    private int isEnable;

    public AppInfo(String appName, String packageName, Drawable appIcon, String versionName, int versionCode,
                   Long firstInstallTime, Long lastUpdateTime, long size, int targetSdk, int minSdk, int isAppEnable,
                   int isEnable) {
        this.appName = appName;
        this.packageName = packageName;
        this.appIcon = appIcon;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.firstInstallTime = firstInstallTime;
        this.lastUpdateTime = lastUpdateTime;
        this.size = size;
        this.targetSdk = targetSdk;
		this.minSdk = minSdk;
        this.isAppEnable = isAppEnable;
        this.isEnable = isEnable;
    }

    protected AppInfo(Parcel in) {
        appName = in.readString();
        packageName = in.readString();
        appIcon = (Drawable) in.readValue(Drawable.class.getClassLoader());
        versionName = in.readString();
        versionCode = in.readInt();
        firstInstallTime = in.readLong();
        lastUpdateTime = in.readLong();
        size = in.readLong();
        targetSdk = in.readInt();
		minSdk = in.readInt();
        isAppEnable = in.readInt();
        isEnable = in.readInt();
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

    public int getVersionCode() {
        return versionCode;
    }

    public Long getFirstInstallTime() {
        return firstInstallTime;
    }

    public Long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public long getSize() {
        return size;
    }

    public int getTargetSdk() {
        return targetSdk;
    }

	public int getMinSdk() {
		return minSdk;
	}

    public int getIsAppEnable() {
        return isAppEnable;
    }

    public int getIsEnable() {
        return isEnable;
    }

    public void setIsEnable(int isEnable) {
        this.isEnable = isEnable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AppInfo appInfo))
            return false;
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
        dest.writeInt(versionCode);
        dest.writeValue(appIcon);
        dest.writeLong(firstInstallTime);
        dest.writeLong(lastUpdateTime);
        dest.writeLong(size);
        dest.writeInt(targetSdk);
		dest.writeInt(minSdk);
        dest.writeInt(isAppEnable);
        dest.writeInt(isEnable);
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
