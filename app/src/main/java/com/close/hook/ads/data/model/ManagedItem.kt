package com.close.hook.ads.data.model

data class ManagedItem(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val type: ItemType
)

enum class ItemType {
    FILE,
    PREFERENCE,
    DATABASE
}
