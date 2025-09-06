package com.close.hook.ads.hook

import com.close.hook.ads.hook.util.ContextUtil
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

class LibXposedEntry(
    base: XposedInterface,
    param: XposedModuleInterface.ModuleLoadedParam
) : XposedModule(base, param) {

    private val processName = param.processName

    init {
        // 将框架提供的 base 接口实例传递给逻辑核心，连接 Hook 进程与框架功能。
        HookLogic.xposedInterface = base

        ContextUtil.setupContextHooks()

        XposedBridge.log("LibXposedEntry: Initialized for process $processName")
    }

    /**
     * 当模块作用域内的应用进程启动时，框架会回调此方法。
     */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        HookLogic.loadPackage(param)
    }
}
