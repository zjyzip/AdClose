package com.close.hook.ads.ui.fragment.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.text.HtmlCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.preference.PrefManager
import com.close.hook.ads.ui.activity.AboutActivity
import com.close.hook.ads.ui.activity.CustomHookActivity
import com.close.hook.ads.ui.activity.DataManagerActivity
import com.close.hook.ads.util.CacheDataManager
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.LangList
import com.close.hook.ads.util.isNightMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private var recyclerView: RecyclerView? = null
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            if (dy > 0) {
                (activity as? INavContainer)?.hideNavigation()
            } else if (dy < 0) {
                (activity as? INavContainer)?.showNavigation()
            }
        }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val createdRecyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        recyclerView = createdRecyclerView
        createdRecyclerView.apply {
            isVerticalScrollBarEnabled = false
            addOnScrollListener(scrollListener)
        }
        return createdRecyclerView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = null
    }

    private inner class SettingsPreferenceDataStore : PreferenceDataStore() {

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "darkTheme" -> PrefManager.darkTheme.toString()
                "themeColor" -> PrefManager.themeColor
                "language" -> currentLanguageTag()
                "defaultPage" -> PrefManager.defaultPage.toString()
                HookPrefs.KEY_REQUEST_CACHE_EXPIRATION ->
                    HookPrefs.getLong(key, 5L).toString()
                else -> defValue
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "themeColor" -> PrefManager.themeColor = value!!
                "language" -> applyLanguageTag(value!!)
                "defaultPage" -> PrefManager.defaultPage = value!!.toInt()
                HookPrefs.KEY_REQUEST_CACHE_EXPIRATION ->
                    HookPrefs.setLong(key, value?.toLongOrNull() ?: 5L)
            }
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "blackDarkTheme" -> PrefManager.blackDarkTheme
                "followSystemAccent" -> PrefManager.followSystemAccent
                "hideIcon" -> PrefManager.hideIcon
                HookPrefs.KEY_ENABLE_DEX_DUMP,
                HookPrefs.KEY_ENABLE_PACKAGE_VISIBILITY_BYPASS ->
                    HookPrefs.getBoolean(key, defValue)
                else -> defValue
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "blackDarkTheme" -> PrefManager.blackDarkTheme = value
                "followSystemAccent" -> PrefManager.followSystemAccent = value
                "hideIcon" -> PrefManager.hideIcon = value
                HookPrefs.KEY_ENABLE_DEX_DUMP,
                HookPrefs.KEY_ENABLE_PACKAGE_VISIBILITY_BYPASS ->
                    HookPrefs.setBoolean(key, value)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
        setPreferencesFromResource(R.xml.settings, rootKey)

        setupCustomHookPreference()
        setupDataManagerPreference()
        setupLanguagePreference()
        setupThemePreferences()
        setupCacheClearing()
        setupAboutPreference()
    }

    override fun onResume() {
        super.onResume()
        syncHookPreferences()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ListPreference) {
            showMaterialListDialog(preference)
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun showMaterialListDialog(preference: ListPreference) {
        val entries = preference.entries
        val values = preference.entryValues
        if (entries == null || values == null) return
        val initial = values.indexOf(preference.value).coerceAtLeast(0)
        var chosen = initial
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.dialogTitle ?: preference.title)
            .setSingleChoiceItems(entries, initial) { _, which -> chosen = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValue = values[chosen].toString()
                if (preference.callChangeListener(newValue)) {
                    preference.value = newValue
                }
            }
            .show()
    }

    private fun syncHookPreferences() {
        listOf(
            HookPrefs.KEY_ENABLE_DEX_DUMP,
            HookPrefs.KEY_ENABLE_PACKAGE_VISIBILITY_BYPASS
        ).forEach { key ->
            findPreference<SwitchPreferenceCompat>(key)?.let { pref ->
                val value = HookPrefs.getBoolean(key, false)
                if (pref.isChecked != value) pref.isChecked = value
            }
        }

        findPreference<ListPreference>(HookPrefs.KEY_REQUEST_CACHE_EXPIRATION)?.let { pref ->
            val value = HookPrefs.getLong(HookPrefs.KEY_REQUEST_CACHE_EXPIRATION, 5L).toString()
            if (pref.value != value) pref.value = value
        }
    }

    private fun setupCustomHookPreference() {
        findPreference<Preference>("custom_hook_sdk_ads")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), CustomHookActivity::class.java))
            true
        }
    }

    private fun setupDataManagerPreference() {
        findPreference<Preference>("data_manager")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DataManagerActivity::class.java))
            true
        }
    }

    private fun setupLanguagePreference() {
        findPreference<ListPreference>("language")?.let {
            val displayLocale = Locale.getDefault()
            val entries = buildList {
                for (lang in LangList.LOCALES) {
                    if (lang == "SYSTEM") add(getString(R.string.follow_system))
                    else {
                        val locale = Locale.forLanguageTag(lang)
                        add(HtmlCompat.fromHtml(locale.getDisplayName(locale), HtmlCompat.FROM_HTML_MODE_LEGACY))
                    }
                }
            }
            it.entries = entries.toTypedArray()
            it.entryValues = LangList.LOCALES
            it.value = currentLanguageTag()
            setLanguageSummary(it, displayLocale)
        }
    }

    private fun setLanguageSummary(preference: ListPreference, userLocale: Locale) {
        if (preference.value == "SYSTEM") {
            preference.summary = getString(R.string.follow_system)
        } else {
            val locale = Locale.forLanguageTag(preference.value)
            preference.summary =
                if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(userLocale)
                else locale.getDisplayName(userLocale)
        }
    }

    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "SYSTEM" else locales.toLanguageTags()
    }

    private fun applyLanguageTag(tag: String) {
        val locales = if (tag == "SYSTEM") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun setupThemePreferences() {
        findPreference<ListPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
            val newMode = (newValue as String).toInt()
            if (PrefManager.darkTheme != newMode) {
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
            true
        }

        findPreference<SwitchPreferenceCompat>("blackDarkTheme")?.setOnPreferenceChangeListener { _, _ ->
            if (requireContext().resources.configuration.isNightMode())
                activity?.recreate()
            true
        }
    }

    private fun setupCacheClearing() {
        findPreference<Preference>("clean")?.apply {
            summary = CacheDataManager.getTotalCacheSize(requireContext())
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(getString(R.string.confirm_clear_cache_title))
                    setMessage(getString(R.string.confirm_clear_cache_message, CacheDataManager.getTotalCacheSize(requireContext())))
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        CacheDataManager.clearAllCache(requireContext())
                        findPreference<Preference>("clean")?.summary = getString(R.string.cache_cleared_recently)
                    }
                    show()
                }
                true
            }
        }
    }

    private fun setupAboutPreference() {
        findPreference<Preference>("about")?.apply {
            summary = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AboutActivity::class.java))
                true
            }
        }
    }
}
