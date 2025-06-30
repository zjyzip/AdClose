package com.close.hook.ads.ui.fragment.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.text.HtmlCompat
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.closeApp
import com.close.hook.ads.ui.activity.AboutActivity
import com.close.hook.ads.util.CacheDataManager
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.LangList
import com.close.hook.ads.preference.PrefManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.material.preference.MaterialSwitchPreference
import rikka.preference.SimpleMenuPreference
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

    class SettingsPreferenceDataStore : PreferenceDataStore() {
        override fun getString(key: String?, defValue: String?): String {
            return when (key) {
                "darkTheme" -> PrefManager.darkTheme.toString()
                "themeColor" -> PrefManager.themeColor
                "language" -> PrefManager.language
                "defaultPage" -> PrefManager.defaultPage.toString()
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "themeColor" -> PrefManager.themeColor = value!!
                "language" -> PrefManager.language = value!!
                "defaultPage" -> PrefManager.defaultPage = value!!.toInt()
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "blackDarkTheme" -> PrefManager.blackDarkTheme
                "followSystemAccent" -> PrefManager.followSystemAccent
                "hideIcon" -> PrefManager.hideIcon
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "blackDarkTheme" -> PrefManager.blackDarkTheme = value
                "followSystemAccent" -> PrefManager.followSystemAccent = value
                "hideIcon" -> PrefManager.hideIcon = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    @SuppressLint("SetTextI19n")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
        setPreferencesFromResource(R.xml.settings, rootKey)

        setupLanguagePreference()
        setupThemePreferences()
        setupCacheClearing()
        setupAboutPreference()
    }

    private fun setupLanguagePreference() {
        findPreference<SimpleMenuPreference>("language")?.let {
            val userLocale = closeApp.getLocale(PrefManager.language)
            val entries = buildList {
                for (lang in LangList.LOCALES) {
                    if (lang == "SYSTEM") add(getString(rikka.core.R.string.follow_system))
                    else {
                        val locale = Locale.forLanguageTag(lang)
                        add(HtmlCompat.fromHtml(locale.getDisplayName(locale), HtmlCompat.FROM_HTML_MODE_LEGACY))
                    }
                }
            }
            it.entries = entries.toTypedArray()
            it.entryValues = LangList.LOCALES
            setLanguageSummary(it, userLocale)

            it.setOnPreferenceChangeListener { _, newValue ->
                val locale = closeApp.getLocale(newValue as String)
                updateLocale(locale)
                true
            }
        }
    }

    private fun setLanguageSummary(preference: SimpleMenuPreference, userLocale: Locale) {
        if (preference.value == "SYSTEM") {
            preference.summary = getString(rikka.core.R.string.follow_system)
        } else {
            val locale = Locale.forLanguageTag(preference.value)
            preference.summary =
                if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(userLocale) else locale.getDisplayName(userLocale)
        }
    }

    private fun updateLocale(locale: Locale) {
        val config = resources.configuration
        config.setLocale(locale)
        LocaleDelegate.defaultLocale = locale
        requireContext().createConfigurationContext(config)
        requireActivity().recreate()
    }

    private fun setupThemePreferences() {
        findPreference<SimpleMenuPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
            val newMode = (newValue as String).toInt()
            if (PrefManager.darkTheme != newMode) {
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
            true
        }

        findPreference<MaterialSwitchPreference>("blackDarkTheme")?.setOnPreferenceChangeListener { _, _ ->
            if (ResourceUtils.isNightMode(requireContext().resources.configuration))
                activity?.recreate()
            true
        }

        findPreference<MaterialSwitchPreference>("followSystemAccent")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<SimpleMenuPreference>("themeColor")?.setOnPreferenceChangeListener { _, _ ->
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
        findPreference<Preference>("about")?.summary =
            "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            true
        }
    }
}
