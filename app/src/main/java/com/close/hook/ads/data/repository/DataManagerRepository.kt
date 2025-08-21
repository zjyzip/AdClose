package com.close.hook.ads.data.repository

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.close.hook.ads.manager.ServiceManager
import com.close.hook.ads.data.model.FrameworkInfo
import com.close.hook.ads.data.model.ItemType
import com.close.hook.ads.data.model.ManagedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DataManagerRepository(private val context: Context) {

    suspend fun getFrameworkInfo(): FrameworkInfo? = withContext(Dispatchers.IO) {
        ServiceManager.service?.let {
            try {
                FrameworkInfo(
                    frameworkName = it.frameworkName,
                    frameworkVersion = it.frameworkVersion,
                    apiVersion = it.apiVersion
                )
            } catch (e: Exception) {
                Log.e("DataManagerRepository", "getFrameworkInfo failed", e)
                null
            }
        }
    }

    suspend fun getManagedFiles(): List<ManagedItem> = withContext(Dispatchers.IO) {
        val service = ServiceManager.service ?: return@withContext emptyList()
        try {
            val fileNames = service.listRemoteFiles() ?: return@withContext emptyList()
            
            fileNames.mapNotNull { fileName ->
                try {
                    service.openRemoteFile(fileName).use { pfd ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val stats = Os.fstat(pfd.fileDescriptor)
                            ManagedItem(
                                name = fileName,
                                size = stats.st_size,
                                lastModified = stats.st_mtime * 1000L,
                                type = ItemType.FILE
                            )
                        } else {
                            ManagedItem(
                                name = fileName,
                                size = pfd.statSize,
                                lastModified = System.currentTimeMillis(),
                                type = ItemType.FILE
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DataManagerRepository", "Could not get metadata for file: $fileName", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "Failed to list remote files", e)
            emptyList()
        }
    }

    suspend fun getPreferenceGroups(): List<ManagedItem> = withContext(Dispatchers.IO) {
        try {
            val prefsDir = File("${context.applicationInfo.dataDir}/shared_prefs")
            prefsDir.listFiles { _, name -> name.endsWith(".xml") }
                ?.mapNotNull { file ->
                    try {
                        ManagedItem(
                            name = file.nameWithoutExtension,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            type = ItemType.PREFERENCE
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "getPreferenceGroups failed", e)
            emptyList()
        }
    }

    suspend fun deleteFile(fileName: String): Boolean = withContext(Dispatchers.IO) {
        ServiceManager.service?.let {
            try {
                it.deleteRemoteFile(fileName)
            } catch (e: Exception) {
                Log.e("DataManagerRepository", "deleteFile failed for: $fileName", e)
                false
            }
        } ?: false
    }

    suspend fun deletePreferenceGroup(groupName: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            context.deleteSharedPreferences(groupName)
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "Failed to delete local preference group: $groupName", e)
            false
        }
    }
}
