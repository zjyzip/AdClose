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

                        val hooks = findSdkMethods(targetPackage)

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

    val adSdkPackages = listOf(
        "com.sjm.sjmsdk",
        "com.ap.android",
        "com.bytedance.pangle",
        "com.bytedance.sdk.openadsdk",
        "com.bytedance.android.openliveplugin",
        "com.ss.android.ad",
        "com.ss.android.downloadlib",
        "com.kwad",
        "com.qq.e",
        "com.baidu.mobads",
        "com.sigmob",
        "com.czhj",
        "cn.admobiletop",
        "com.inmobi.sdk",
        "com.tradplus.ads",
        "com.jd.ad.sdk",
        "com.beizi.fusion",
        "com.meishu.sdk",
        "com.link.sdk",
        "com.xwuad.sdk",
        "com.qumeng",
        "com.huawei.hms.ads",
        "com.huawei.openalliance.ad",
        "com.mbridge.msdk",
        "com.windmill.sdk",
        "com.alimm.tanx",
        "com.umeng",
        "com.anythink",
        "com.miui.zeus.mimo.sdk",
        "cn.xiaochuankeji",
        "com.tencent.bugly",
        "com.tencent.klevin.ads",
        "com.tencent.qqmini.ad",
        "com.baichuan",
        "com.vungle.warren",
        "com.applovin.sdk",
        "com.unity3d.ads",
        "com.unity3d.services",
        "com.google.ads",
        "com.google.unity.ads",
        "com.google.android.ads",
        "com.google.android.gms.ads",
        "com.google.android.gms.admob",
        "com.facebook.ads",
        "com.appsflyer"
    )

    fun findSdkMethods(packageName: String): List<CustomHookInfo> {
        val foundMethods = DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods("$packageName:findSdkMethods") {
                val initMethods = bridge.findMethod {
                    searchPackages(adSdkPackages)
                    matcher {
                        name(StringMatcher("init", StringMatchType.Contains, ignoreCase = true))
                    }
                }
                val getContextMethods = bridge.findMethod {
                    searchPackages(adSdkPackages)
                    matcher {
                        declaredClass(ClassMatcher().apply {
                            className(StringMatcher("Sdk", StringMatchType.Contains, ignoreCase = true))
                        })
                        name(StringMatcher("getContext", StringMatchType.Equals))
                    }
                }
                (initMethods + getContextMethods)
            }?.filter(::isValidSdkMethod)
        } ?: emptyList()

        val hooks = foundMethods.mapNotNull { methodData ->
            if (methodData.returnTypeName == "android.content.Context" || methodData.paramTypeNames?.contains("android.content.Context") == true) {
                CustomHookInfo(
                    hookMethodType = HookMethodType.REPLACE_CONTEXT_WITH_FAKE,
                    hookPoint = "after",
                    className = methodData.className,
                    methodNames = listOf(methodData.name),
                    parameterTypes = methodData.paramTypeNames,
                    isEnabled = true,
                )
            } else {
                val returnValue = when (methodData.returnTypeName) {
                    "boolean" -> "false"
                    "int" -> "0"
                    "long" -> "0L"
                    "float" -> "0.0f"
                    "double" -> "0.0"
                    else -> null
                }

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
        }

        XposedBridge.log("$TAG | Finished finding methods. Total found: ${hooks.size}")
        return hooks
    }

    private fun isValidSdkMethod(methodData: MethodData): Boolean {
        val isNotConstructor = methodData.name != "<init>" && methodData.name != "<clinit>"
        val isNotAbstract = !Modifier.isAbstract(methodData.modifiers)
        return isNotConstructor && isNotAbstract
    }
}
