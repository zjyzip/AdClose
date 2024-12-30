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

    private fun dumpDexFilesToPath(outputPath: String) {
        try {
            DexKitUtil.initializeDexKitBridge()
            val bridge = DexKitUtil.getBridge()

            val outputDir = File(outputPath)
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    XposedBridge.log("Failed to create output directory: $outputPath")
                    return
                } else {
                    XposedBridge.log("Directory created successfully: $outputPath")
                }
            }

            bridge.exportDexFile(outputPath)

            val dexFiles = outputDir.listFiles { _, name -> name.endsWith(".dex") }
            if (dexFiles != null && dexFiles.isNotEmpty()) {
                XposedBridge.log("Exported ${dexFiles.size} dex files to: $outputPath")
                dexFiles.forEach { file ->
                    XposedBridge.log("Exported dex file: ${file.absolutePath}")
                }
            } else {
                XposedBridge.log("No dex files found in output directory: $outputPath")
            }

        } catch (e: Throwable) {
            XposedBridge.log("Error dumping dex files: ${e.message}")
        } finally {
            DexKitUtil.releaseBridge()
        }
    }

    private val defaultOutputPath: String
        get() = Environment.getExternalStorageDirectory().absolutePath + "/Download/DexkitDump/"

    private fun getOutputPath(packageName: String): String {
        val baseDir = defaultOutputPath
        return "$baseDir$packageName/"
    }
}
