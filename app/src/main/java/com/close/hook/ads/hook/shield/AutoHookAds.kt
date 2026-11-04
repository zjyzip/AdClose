package com.close.hook.ads.hook.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.util.DexKitUtil
import com.close.hook.ads.hook.util.AdSdkScanRules
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AutoHookAds {

    private const val TAG = "AutoHookAds"
    private const val ACTION_AUTO_DETECT_ADS = "com.close.hook.ads.ACTION_AUTO_DETECT_ADS"
    private val PROVIDER_URI = Uri.parse("content://com.close.hook.ads.provider.auto_detect")

    @Volatile
    private var cachedHooks: List<CustomHookInfo>? = null
    private val receiverRegistered = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    val adSdkPackages: List<String> get() = AdSdkScanRules.adSdkPackages

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerAutoDetectReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_AUTO_DETECT_ADS) {
                    intent.getStringExtra("target_package")?.let { targetPackage ->
                        if (targetPackage == ctx.packageName) {
                            XposedBridge.log("$TAG | Received trigger broadcast. Pushing cached results.")
                            // A scan still in progress is different from a completed empty result.
                            cachedHooks?.let { pushResultsToManager(ctx, it) }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_AUTO_DETECT_ADS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Throwable) {
            receiverRegistered.set(false)
            throw e
        }
    }

    suspend fun findAndCacheSdkMethods(context: Context, packageName: String) = withContext(Dispatchers.IO) {
        XposedBridge.log("$TAG | Start finding SDK methods for package: $packageName")

        val hooks = DexKitUtil.withBridge { bridge ->
            AdSdkScanRules.findSdkHooks(bridge)
        } ?: return@withContext

        cachedHooks = hooks

        pushResultsToManager(context, hooks)

        XposedBridge.log("$TAG | Finished finding and pushed methods. Total found: ${hooks.size}")
    }

    private fun pushResultsToManager(context: Context, hooks: List<CustomHookInfo>) {
        try {
            val hooksJson = json.encodeToString(hooks)
            val bundle = Bundle().apply {
                putString("detected_hooks_result", hooksJson)
            }
            context.contentResolver.call(PROVIDER_URI, "sendResult", null, bundle)
        } catch (e: Exception) {
            XposedBridge.log("$TAG | Failed to push results via Provider: ${e.message}")
        }
    }
}
