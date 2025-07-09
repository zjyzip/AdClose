package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

@Parcelize
@Serializable
data class CustomHookInfo(
    val id: String = UUID.randomUUID().toString(),
    val className: String,
    val methodNames: List<String>? = null,
    val returnValue: String? = null,
    val hookMethodType: HookMethodType,
    val parameterTypes: List<String>? = null,
    val fieldName: String? = null,
    val fieldValue: String? = null,
    val isEnabled: Boolean = false,
    val packageName: String? = null,
    val hookPoint: String? = null
) : Parcelable
