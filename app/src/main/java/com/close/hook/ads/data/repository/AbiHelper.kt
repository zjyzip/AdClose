package com.close.hook.ads.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.close.hook.ads.CloseApplication
import java.io.File
import java.util.zip.ZipFile

// from libchecker
object AbiHelper {

    private const val ARMV8 = 0
    private const val ARMV7 = 1
    private const val ARMV5 = 2
    private const val NO_LIBS = 3

    fun getAbi(packageName: String): Int {
        val appList = CloseApplication.context.packageManager
            .getInstalledApplications(PackageManager.GET_SHARED_LIBRARY_FILES)
        for (info in appList) {
            if (packageName == info.packageName) {
                return getAbi(info.sourceDir, info.nativeLibraryDir)
            }
        }
        return 0;
    }


    private fun getAbi(path: String, nativePath: String): Int {
        val file = File(path)
        val zipFile = ZipFile(file)
        val entries = zipFile.entries()
        val abiList = ArrayList<String>()

        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (name.contains("lib/")) {
                abiList.add(name.split("/")[1])
            }
        }
        zipFile.close()

        return when {
            abiList.contains("arm64-v8a") -> ARMV8
            abiList.contains("armeabi-v7a") -> ARMV7
            abiList.contains("armeabi") -> ARMV5
            else -> getAbiByNativeDir(nativePath)
        }
    }

    private fun getAbiByNativeDir(nativePath: String): Int {
        val file = File(nativePath.substring(0, nativePath.lastIndexOf("/")))
        val abiList = ArrayList<String>()

        val fileList = file.listFiles() ?: return NO_LIBS

        for (abi in fileList) {
            abiList.add(abi.name)
        }

        return when {
            abiList.contains("arm64") -> ARMV8
            abiList.contains("arm") -> ARMV7
            else -> NO_LIBS
        }
    }
}