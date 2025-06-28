package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AppInfo(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val size: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val isAppEnable: Int,
    var isEnable: Int,
    val isSystem: Boolean
) : Parcelable
