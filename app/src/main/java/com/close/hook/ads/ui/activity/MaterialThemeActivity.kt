package com.close.hook.ads.ui.activity

import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity that applies user-selected theme overlays once per Resources.Theme
 * and recreates the activity when [computeUserThemeKey] changes between lifecycle resumes.
 *
 * Drop-in replacement for rikka.material.app.MaterialActivity (which has not been
 * updated in two years).
 */
abstract class MaterialThemeActivity : AppCompatActivity() {

    private var appliedThemeKey: String? = null
    private var themeOverlayApplied = false

    protected abstract fun computeUserThemeKey(): String

    protected abstract fun onApplyUserThemeResource(theme: Resources.Theme, isDecorView: Boolean)

    override fun getTheme(): Resources.Theme {
        val theme = super.getTheme()
        if (!themeOverlayApplied) {
            themeOverlayApplied = true
            onApplyUserThemeResource(theme, isDecorView = false)
        }
        return theme
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeKey = computeUserThemeKey()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedThemeKey != computeUserThemeKey()) {
            recreate()
        }
    }
}
