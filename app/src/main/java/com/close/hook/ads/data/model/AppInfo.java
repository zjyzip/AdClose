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
	private final Long firstInstallTime;
	private final Long lastUpdateTime;
	private final long size;
	private final int targetSdk;
	private final int isAppEnable;
	private final int isEnable;

	public AppInfo(String appName, String packageName, Drawable appIcon, String versionName,
			Long firstInstallTime, Long lastUpdateTime, long size, int targetSdk, int isAppEnable,
				   int isEnable) {
		this.appName = appName;
		this.packageName = packageName;
		this.appIcon = appIcon;
		this.versionName = versionName;
		this.firstInstallTime = firstInstallTime;
		this.lastUpdateTime = lastUpdateTime;
		this.size = size;
		this.targetSdk = targetSdk;
		this.isAppEnable = isAppEnable;
		this.isEnable = isEnable;
	}

	protected AppInfo(Parcel in) {
		appName = in.readString();
		packageName = in.readString();
		versionName = in.readString();
		appIcon = (Drawable) in.readValue(Drawable.class.getClassLoader());
		firstInstallTime = in.readLong();
		lastUpdateTime = in.readLong();
		size = in.readLong();
		targetSdk = in.readInt();
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

	public int getIsAppEnable() {
		return isAppEnable;
	}
	public int getIsEnable() {
		return isEnable;
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
		dest.writeLong(firstInstallTime);
		dest.writeLong(lastUpdateTime);
		dest.writeLong(size);
		dest.writeInt(targetSdk);
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
