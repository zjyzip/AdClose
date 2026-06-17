package com.close.hook.ads.ui.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.databinding.ActivityAboutBinding
import com.close.hook.ads.databinding.ItemAboutContributorBinding
import com.close.hook.ads.databinding.ItemAboutLinkBinding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.divider.MaterialDivider

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    private data class Contributor(
        val avatarRes: Int,
        val name: String,
        val role: String,
        val url: String
    )

    private data class LinkItem(val title: String, val subtitle: String, val url: String)

    private data class License(val name: String, val author: String, val license: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar(binding.toolbar)
        bindHeader()
        bindDevelopers()
        bindAdRules()
        bindFeedback()
        bindOpenSource()
    }

    private fun setupToolbar(toolbar: MaterialToolbar) {
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun bindHeader() {
        binding.aboutAppName.text = applicationInfo.loadLabel(packageManager)
        binding.aboutAppVersion.text =
            getString(R.string.app_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    private fun bindDevelopers() {
        val items = listOf(
            Contributor(R.drawable.cont_author, "zjyzip", getString(R.string.about_role_developer), "https://github.com/zjyzip"),
            Contributor(R.drawable.cont_bggrgjqaubcoe, "bggRGjQaUbCoE", getString(R.string.about_role_collaborator), "https://github.com/bggRGjQaUbCoE")
        )
        items.forEach { addContributor(binding.aboutDevelopersContainer, it) }
    }

    private fun bindAdRules() {
        val items = listOf(
            LinkItem("AWAvenue-Ads-Rule", "TG-Twilight", "https://github.com/TG-Twilight/AWAvenue-Ads-Rule"),
            LinkItem("banad", "damengzhu", "https://github.com/damengzhu/banad"),
            LinkItem("GOODBYEADS", "8680", "https://github.com/8680/GOODBYEADS"),
            LinkItem("Rules-For-Quantumult-X", "sve1r", "https://github.com/sve1r/Rules-For-Quantumult-X"),
            LinkItem("wool_scripts", "fmz200", "https://github.com/fmz200/wool_scripts")
        )
        addCardList(binding.aboutAdrulesContainer, items)
    }

    private fun bindFeedback() {
        val items = listOf(
            LinkItem(getString(R.string.about_feedback_telegram_channel), "@AdClose", "https://t.me/AdClose"),
            LinkItem(getString(R.string.about_feedback_telegram_group), "@AdClose_Chat", "https://t.me/AdClose_Chat")
        )
        addCardList(binding.aboutFeedbackContainer, items)
    }

    private fun bindOpenSource() {
        val licenses = listOf(
            License("XposedBridge", "rovo89", "Apache-2.0", "https://github.com/rovo89/XposedBridge"),
            License("LibXposed", "libxposed", "Apache-2.0", "https://github.com/libxposed"),
            License("android-inline-hook", "bytedance", "MIT", "https://github.com/bytedance/android-inline-hook"),
            License("DexKit", "LuckyPray", "LGPL-3.0", "https://github.com/LuckyPray/DexKit"),
            License("AndroidX", "Google", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
            License("material-components-android", "Google", "Apache-2.0", "https://github.com/material-components/material-components-android"),
            License("AndroidFastScroll", "zhanghai", "Apache-2.0", "https://github.com/zhanghai/AndroidFastScroll"),
            License("SwipeMenuRecyclerView", "aitsuki", "MIT", "https://github.com/aitsuki/SwipeMenuRecyclerView"),
            License("Kotlin", "JetBrains", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
            License("MPAndroidChart", "PhilJay", "Apache-2.0", "https://github.com/PhilJay/MPAndroidChart"),
            License("Guava", "Google", "Apache-2.0", "https://github.com/google/guava")
        )
        val inflater = LayoutInflater.from(this)
        licenses.forEachIndexed { index, lic ->
            val row = ItemAboutLinkBinding.inflate(inflater, binding.aboutOpenSourceContainer, false)
            row.title.text = lic.name
            row.subtitle.text = "${lic.author} · ${lic.license}"
            row.root.setOnClickListener { openUrl(lic.url) }
            binding.aboutOpenSourceContainer.addView(row.root)
            if (index < licenses.lastIndex) {
                binding.aboutOpenSourceContainer.addView(buildDivider())
            }
        }
    }

    private fun addContributor(parent: ViewGroup, c: Contributor) {
        val item = ItemAboutContributorBinding.inflate(layoutInflater, parent, false)
        item.avatar.setImageResource(c.avatarRes)
        item.name.text = c.name
        item.role.text = c.role
        item.root.setOnClickListener { openUrl(c.url) }
        parent.addView(item.root)
    }

    private fun addCardList(parent: ViewGroup, items: List<LinkItem>) {
        val inflater = LayoutInflater.from(this)
        items.forEach { it ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.spacing_small)
                }
                radius = resources.getDimensionPixelSize(R.dimen.corner_radius_medium).toFloat()
                cardElevation = 0f
                isClickable = true
                isFocusable = true
            }
            val row = ItemAboutLinkBinding.inflate(inflater, card, false)
            row.title.text = it.title
            row.subtitle.text = it.subtitle
            row.root.setOnClickListener { _ -> openUrl(it.url) }
            row.subtitle.isVisible = it.subtitle.isNotEmpty()
            card.addView(row.root)
            card.setOnClickListener { _ -> openUrl(it.url) }
            parent.addView(card)
        }
    }

    private fun buildDivider(): MaterialDivider {
        return MaterialDivider(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.spacing_normal)
                marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_normal)
            }
            dividerInsetStart = 0
            dividerInsetEnd = 0
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }
}
