package com.close.hook.ads.hook.ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.hook.util.DexKitUtil
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.base.StringMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object AutoHookAds {

    private const val TAG = "AutoHookAds"

    private const val ACTION_AUTO_DETECT_ADS = "com.close.hook.ads.ACTION_AUTO_DETECT_ADS"
    private const val ACTION_AUTO_DETECT_ADS_RESULT = "com.close.hook.ads.ACTION_AUTO_DETECT_ADS_RESULT"
    private const val RESULT_KEY = "detected_hooks_result"

    fun registerAutoDetectReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_AUTO_DETECT_ADS) {
                    val targetPackage = intent.getStringExtra("target_package")
                    if (targetPackage != null && targetPackage == ctx.packageName) {
                        XposedBridge.log("$TAG | Received auto-detect request for package: $targetPackage")

                        val hooks = findAdSdkInitMethods(targetPackage)

                        val resultIntent = Intent(ACTION_AUTO_DETECT_ADS_RESULT).apply {
                            putParcelableArrayListExtra(RESULT_KEY, ArrayList(hooks))
                            setPackage("com.close.hook.ads")
                        }
                        ctx.sendBroadcast(resultIntent)

                        XposedBridge.log("$TAG | Finished scanning and sending results to UI for: $targetPackage")
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_AUTO_DETECT_ADS)
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun findAdSdkInitMethods(packageName: String): List<CustomHookInfo> {
        val foundMethods = DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods("$packageName:findAdSdkInitMethods") {
                bridge.findMethod {
                    matcher {
                        declaredClass(ClassMatcher().apply {
                            className(StringMatcher("Sdk", StringMatchType.Contains, ignoreCase = true))
                        })
                        name(StringMatcher("init", StringMatchType.Contains, ignoreCase = true))
                    }
                }
            }
        } ?: emptyList()

        val hooks = foundMethods.mapNotNull { methodData ->
            if (isValidAdSdkInitMethod(methodData)) {
                if (methodData.returnTypeName == "android.content.Context") {
                    CustomHookInfo(
                        hookMethodType = HookMethodType.REPLACE_CONTEXT_WITH_FAKE,
                        hookPoint = "after",
                        className = methodData.className,
                        methodNames = listOf(methodData.name),
                        parameterTypes = methodData.paramTypeNames,
                        returnValue = null,
                        isEnabled = true,
                    )
                } else {
                    val returnValue = if (methodData.returnTypeName == "boolean") "false" else null

                    if (methodData.paramTypeNames?.isNotEmpty() == true) {
                        CustomHookInfo(
                            hookMethodType = HookMethodType.FIND_AND_HOOK_METHOD,
                            hookPoint = "before",
                            className = methodData.className,
                            methodNames = listOf(methodData.name),
                            parameterTypes = methodData.paramTypeNames,
                            returnValue = returnValue,
                            isEnabled = true,
                        )
                    } else {
                        CustomHookInfo(
                            hookMethodType = HookMethodType.HOOK_MULTIPLE_METHODS,
                            className = methodData.className,
                            methodNames = listOf(methodData.name),
                            returnValue = returnValue,
                            isEnabled = true,
                        )
                    }
                }
            } else {
                null
            }
        }

        XposedBridge.log("$TAG | Finished finding methods. Total found: ${hooks.size}")
        return hooks
    }

    private fun isValidAdSdkInitMethod(methodData: MethodData): Boolean {
        val isNotConstructor = methodData.name != "<init>" && methodData.name != "<clinit>"
        val isValidReturnType = methodData.returnTypeName == "void" || methodData.returnTypeName == "boolean" || methodData.returnTypeName == "android.content.Context"
        val isNotAbstract = !Modifier.isAbstract(methodData.modifiers)
        return isNotConstructor && isValidReturnType && isNotAbstract
    }
}
