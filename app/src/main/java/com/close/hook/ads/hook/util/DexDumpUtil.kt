package com.close.hook.ads.hook.util

import android.os.Environment
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File

object DexDumpUtil {

    fun dumpDexFilesByPackageName(packageName: String) {
        val outputPath = getOutputPath(packageName)
        dumpDexFilesToPath(outputPath)
    }

    fun dumpDexFilesToDefaultPath() {
        dumpDexFilesToPath(defaultOutputPath)
    }

    fun dumpDexFilesToPath(outputPath: String) {
        DexKitUtil.withBridge { bridge ->
            try {
                val outputDir = File(outputPath)
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    XposedBridge.log("Failed to create output directory: $outputPath")
                    return@withBridge
                }

                bridge.exportDexFile(outputPath)
                val dexFiles = outputDir.listFiles { _, name -> name.endsWith(".dex") }
    
                if (!dexFiles.isNullOrEmpty()) {
                    XposedBridge.log("Exported ${dexFiles.size} dex files to: $outputPath")
                    dexFiles.forEach { file ->
                        XposedBridge.log("Exported dex file: ${file.absolutePath}")
                    }
                } else {
                    XposedBridge.log("No dex files found in output directory: $outputPath")
                }

            } catch (e: Throwable) {
                XposedBridge.log("Error dumping dex files: ${e.message}")
            }
        }
    }

    private val defaultOutputPath: String
        get() = Environment.getExternalStorageDirectory().absolutePath + "/Download/DexkitDump/"

    private fun getOutputPath(packageName: String): String {
        val baseDir = defaultOutputPath
        return "$baseDir$packageName/"
    }
}
