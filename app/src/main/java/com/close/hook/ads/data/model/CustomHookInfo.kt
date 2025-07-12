package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

@Parcelize
@Serializable
data class CustomHookInfo(
    val id: String = UUID.randomUUID().toString(),
    val hookMethodType: HookMethodType,
    val packageName: String? = null,
    val isEnabled: Boolean = false,
    val className: String,
    val hookPoint: String? = null,
    val searchStrings: List<String>? = null,
    val methodNames: List<String>? = null,
    val parameterTypes: List<String>? = null,
    val fieldName: String? = null,
    val fieldValue: String? = null,
    val returnValue: String? = null
) : Parcelable
