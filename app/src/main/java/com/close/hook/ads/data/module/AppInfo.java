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
	private int isEnable;
	private final Long firstInstallTime;
	private final Long lastUpdateTime;
	private final long size;
	private final int targetSdk;

	public AppInfo(String appName, String packageName, Drawable appIcon, String versionName, int isEnable,
			Long firstInstallTime, Long lastUpdateTime, long size, int targetSdk) {
		this.appName = appName;
		this.packageName = packageName;
		this.appIcon = appIcon;
		this.versionName = versionName;
		this.isEnable = isEnable;
		this.firstInstallTime = firstInstallTime;
		this.lastUpdateTime = lastUpdateTime;
		this.size = size;
		this.targetSdk = targetSdk;
	}

	protected AppInfo(Parcel in) {
		appName = in.readString();
		packageName = in.readString();
		versionName = in.readString();
		appIcon = (Drawable) in.readValue(Drawable.class.getClassLoader());
		isEnable = in.readInt();
		firstInstallTime = in.readLong();
		lastUpdateTime = in.readLong();
		size = in.readLong();
		targetSdk = in.readInt();
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

	public int getIsEnable() {
		return isEnable;
	}
	public void setIsEnable(int isEnable) {
		this.isEnable = isEnable;
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
		dest.writeValue(appIcon);
		dest.writeInt(isEnable);
		dest.writeLong(firstInstallTime);
		dest.writeLong(lastUpdateTime);
		dest.writeLong(size);
		dest.writeInt(targetSdk);
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
