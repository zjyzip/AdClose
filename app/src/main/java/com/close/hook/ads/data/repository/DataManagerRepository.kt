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
import java.io.FileInputStream

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
        getLocalDirectoryItems("shared_prefs", ".xml", ItemType.PREFERENCE)
    }

    suspend fun getDatabases(): List<ManagedItem> = withContext(Dispatchers.IO) {
        val dbFilter = { name: String ->
            !name.endsWith("-journal") && 
            !name.endsWith("-shm") && 
            !name.endsWith("-wal")
        }
        getLocalDirectoryItems("databases", null, ItemType.DATABASE, dbFilter)
    }

    private fun getLocalDirectoryItems(
        dirName: String, 
        extension: String?, 
        itemType: ItemType,
        additionalFilter: ((String) -> Boolean)? = null
    ): List<ManagedItem> {
        return try {
            val dir = File("${context.applicationInfo.dataDir}/$dirName")
            dir.listFiles { _, name -> 
                val extMatch = extension == null || name.endsWith(extension)
                val customMatch = additionalFilter?.invoke(name) ?: true
                extMatch && customMatch
            }
                ?.mapNotNull { file ->
                    try {
                        ManagedItem(
                            name = if (extension != null) file.nameWithoutExtension else file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            type = itemType
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "Failed to get items from $dirName", e)
            emptyList()
        }
    }

    suspend fun getFileContent(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        ServiceManager.service?.let {
            try {
                it.openRemoteFile(fileName).use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        fis.readBytes()
                    }
                }
            } catch (e: Exception) {
                Log.e("DataManagerRepository", "Failed to get content for file: $fileName", e)
                null
            }
        }
    }

    suspend fun getPreferenceContent(groupName: String): ByteArray? = withContext(Dispatchers.IO) {
        getLocalFileContent("shared_prefs", "$groupName.xml")
    }

    suspend fun getDatabaseContent(dbName: String): ByteArray? = withContext(Dispatchers.IO) {
        getLocalFileContent("databases", dbName)
    }

    private fun getLocalFileContent(dirName: String, fileName: String): ByteArray? {
        return try {
            val file = File("${context.applicationInfo.dataDir}/$dirName", fileName)
            if (file.exists()) {
                if (file.length() > 10 * 1024 * 1024) {
                    Log.w("DataManagerRepository", "File too large to read into memory: $fileName")
                    null
                } else {
                    file.readBytes()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "Failed to get content for $dirName/$fileName", e)
            null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(groupName)
            } else {
                val file = File("${context.applicationInfo.dataDir}/shared_prefs", "$groupName.xml")
                if (file.exists()) file.delete() else false
            }
            true
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "Failed to delete local preference group: $groupName", e)
            false
        }
    }

    suspend fun deleteDatabase(dbName: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            context.deleteDatabase(dbName)
        } catch (e: Exception) {
            Log.e("DataManagerRepository", "Failed to delete database: $dbName", e)
            false
        }
    }
}
