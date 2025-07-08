package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Parcelize
data class CustomHookInfo(
    val id: String = UUID.randomUUID().toString(),
    val className: String,
    val methodNames: List<String>? = null,
    val returnValue: String? = null,
    val hookMethodType: HookMethodType = HookMethodType.HOOK_MULTIPLE_METHODS,
    val parameterTypes: List<String>? = null,
    val fieldName: String? = null,
    val fieldValue: String? = null,
    val isEnabled: Boolean = false,
    val packageName: String? = null
) : Parcelable