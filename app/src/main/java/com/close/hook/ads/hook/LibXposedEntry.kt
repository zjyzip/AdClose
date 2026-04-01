package com.close.hook.ads.hook

import android.util.Log
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.system.PackageVisibilityHandler
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class LibXposedEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookLogic.xposedInterface = this
        
        if (param.processName == "system") {
            PackageVisibilityHandler.init(this)
        } else {
            ContextUtil.setupContextHooks()
        }
        
        this.log(Log.INFO, "LibXposedEntry", "Initialized for process: ${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        HookLogic.loadPackage(param)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
    }
}
