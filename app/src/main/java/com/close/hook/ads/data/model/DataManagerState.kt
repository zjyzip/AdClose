package com.close.hook.ads.data.model

data class DataManagerState(
    val isLoading: Boolean = true,
    val frameworkInfo: FrameworkInfo? = null,
    val managedFiles: List<ManagedItem> = emptyList(),
    val preferenceGroups: List<ManagedItem> = emptyList(),
    val databases: List<ManagedItem> = emptyList()
)