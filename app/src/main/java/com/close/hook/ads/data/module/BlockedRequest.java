package com.close.hook.ads.data.module;

import android.graphics.drawable.Drawable;

import java.io.Serializable;

public class BlockedRequest implements Serializable {
	public String appName;
	public String packageName;
	public String request;
	public long timestamp;

	public BlockedRequest(String appName, String packageName, String request, long timestamp) {
		this.appName = appName;
		this.packageName = packageName;
		this.request = request;
		this.timestamp = timestamp;
	}
}
