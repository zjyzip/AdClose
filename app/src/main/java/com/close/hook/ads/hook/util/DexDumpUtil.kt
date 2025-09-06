package com.close.hook.ads.hook.util

import android.os.Environment
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File

object DexDumpUtil {

    private const val LOG_PREFIX = "[DexDumpUtil] "
    private const val DUMP_SUB_PATH = "Download/DexKitDump/"

    private val defaultOutputPath: String
        get() = "${Environment.getExternalStorageDirectory().absolutePath}/$DUMP_SUB_PATH"

    private fun getOutputPath(packageName: String): String {
        return "$defaultOutputPath$packageName/"
    }

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

                if (!outputDir.exists()) {
                    XposedBridge.log("${LOG_PREFIX}Output directory does not exist. Creating...")
                    if (!outputDir.mkdirs()) {
                        XposedBridge.log("${LOG_PREFIX}Failed to create output directory: $outputPath")
                        return@withBridge
                    }
                }

                bridge.exportDexFile(outputPath)
                
                val dexFiles = outputDir.listFiles { _, name -> name.endsWith(".dex") }
    
                if (!dexFiles.isNullOrEmpty()) {
                    XposedBridge.log("${LOG_PREFIX}Exported ${dexFiles.size} dex files to: $outputPath")
                    dexFiles.forEachIndexed { index, file ->
                        XposedBridge.log("${LOG_PREFIX}${index + 1}. ${file.absolutePath}")
                    }
                } else {
                    XposedBridge.log("${LOG_PREFIX}No dex files found in output directory: $outputPath")
                }

            } catch (e: Throwable) {
                XposedBridge.log("${LOG_PREFIX}Error dumping dex files: ${e.message}")
            }
        }
    }
}
