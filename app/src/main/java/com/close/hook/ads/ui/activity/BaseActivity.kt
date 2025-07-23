package com.close.hook.ads.ui.activity

import android.content.res.Resources
import android.graphics.Color
import com.close.hook.ads.R
import com.close.hook.ads.util.ThemeUtils
import rikka.material.app.MaterialActivity

abstract class BaseActivity : MaterialActivity() {

    override fun computeUserThemeKey() =
        ThemeUtils.colorTheme + ThemeUtils.getNightThemeStyleRes(this)

    override fun onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    override fun onApplyUserThemeResource(theme: Resources.Theme, isDecorView: Boolean) {
        if (!ThemeUtils.isSystemAccent) theme.applyStyle(ThemeUtils.colorThemeStyleRes, true)
        theme.applyStyle(ThemeUtils.getNightThemeStyleRes(this), true) //blackDarkMode
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(R.anim.slide_in_scale, R.anim.slide_out_scale)
    }
}
