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

    private var cachedHooks: List<CustomHookInfo>? = null

    fun registerAutoDetectReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_AUTO_DETECT_ADS) {
                    intent.getStringExtra("target_package")?.let { targetPackage ->
                        if (targetPackage == ctx.packageName) {
                            XposedBridge.log("$TAG | Received auto-detect request for package: $targetPackage. Sending cached results.")
                            val hooksToSend = cachedHooks ?: emptyList()

                            val resultIntent = Intent(ACTION_AUTO_DETECT_ADS_RESULT).apply {
                                putParcelableArrayListExtra(RESULT_KEY, ArrayList(hooksToSend))
                                setPackage("com.close.hook.ads")
                            }
                            ctx.sendBroadcast(resultIntent)

                            XposedBridge.log("$TAG | Finished sending cached results to UI for: $targetPackage. Total found: ${hooksToSend.size}")
                        }
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

    fun findAndCacheSdkMethods(packageName: String) {
        XposedBridge.log("$TAG | Start finding SDK methods for package: $packageName")
        
        val hooks = DexKitUtil.withBridge { bridge ->
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
            }?.filter { methodData ->
                isValidSdkMethod(methodData)
            }?.mapNotNull { methodData ->
                val className = methodData.className
                val methodName = methodData.name
                val paramTypeNames = methodData.paramTypeNames
                val returnTypeName = methodData.returnTypeName

                val isContextMethod = returnTypeName == "android.content.Context" ||
                                        (paramTypeNames?.contains("android.content.Context") == true)
                
                if (isContextMethod) {
                    CustomHookInfo(
                        hookMethodType = HookMethodType.REPLACE_CONTEXT_WITH_FAKE,
                        hookPoint = "after",
                        className = className,
                        methodNames = listOf(methodName),
                        parameterTypes = paramTypeNames,
                        isEnabled = true,
                    )
                } else {
                    val returnValue = when (returnTypeName) {
                        "boolean" -> "false"
                        "int" -> "0"
                        "long" -> "0L"
                        "float" -> "0.0f"
                        "double" -> "0.0"
                        "void" -> "null"
                        else -> null
                    }

                    if (paramTypeNames?.isNotEmpty() == true) {
                        CustomHookInfo(
                            hookMethodType = HookMethodType.FIND_AND_HOOK_METHOD,
                            hookPoint = "before",
                            className = className,
                            methodNames = listOf(methodName),
                            parameterTypes = paramTypeNames,
                            returnValue = returnValue,
                            isEnabled = true,
                        )
                    } else {
                        CustomHookInfo(
                            hookMethodType = HookMethodType.HOOK_MULTIPLE_METHODS,
                            className = className,
                            methodNames = listOf(methodName),
                            returnValue = returnValue,
                            isEnabled = true,
                        )
                    }
                }
            }
        } ?: emptyList()

        cachedHooks = hooks
        XposedBridge.log("$TAG | Finished finding and caching methods. Total found: ${hooks.size}")
    }

    private fun isValidSdkMethod(methodData: MethodData): Boolean {
        val isNotConstructor = methodData.name != "<init>" && methodData.name != "<clinit>"
        val isNotAbstract = !Modifier.isAbstract(methodData.modifiers)
        return isNotConstructor && isNotAbstract
    }
}
