package com.close.hook.ads.ui.activity

import android.annotation.SuppressLint
import android.widget.ImageView
import android.widget.TextView
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.drakeet.about.AbsAboutActivity
import com.drakeet.about.Card
import com.drakeet.about.Category
import com.drakeet.about.Contributor
import com.drakeet.about.License
import com.drakeet.about.Line

class AboutActivity : AbsAboutActivity() {

    @SuppressLint("SetTextI18n")
    override fun onCreateHeader(icon: ImageView, slogan: TextView, version: TextView) {
        icon.setImageResource(R.drawable.ic_launcher)
        slogan.text = applicationInfo.loadLabel(packageManager)
        version.text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
    }

    override fun onItemsCreated(items: MutableList<Any>) {
        items.add(Category("About"))
        items.add(Card("""
    |这是一个Xposed模块，请在LSPosed框架环境中使用。
    |
    |此模块旨在优化用户体验，减少不必要的广告干扰和提高应用效率。
    |仅供学习交流，请勿用于违法违规用途，且模块完全免费使用。
    |
    |主要用于：
    |阻止常见SDK广告与部分统计SDK的初始化加载。
    |拦截屏蔽应用的网络广告请求。
    |
    |同时提供了一些其他Hook功能和特定应用去广告适配。
    """.trimMargin()))

        items.add(Category("Developer"))
        items.add(Contributor(R.drawable.cont_author, "zjyzip", "Developer & Designer", "https://github.com/zjyzip"))
        items.add(Line())
        items.add(Contributor(R.drawable.cont_bggrgjqaubcoe, "bggRGjQaUbCoE", "Developer & Collaborator", "https://github.com/bggRGjQaUbCoE"))

        items.add(Category("Thanks-AdRules"))
        items.add(Card("Twilight(秋风广告规则)\nhttps://github.com/TG-Twilight/AWAvenue-Ads-Rule"))
        items.add(Card("大萌主(轻量广告拦截规则)\nhttps://github.com/damengzhu/banad"))
        items.add(Card("8680(GOODBYEADS)\nhttps://github.com/8680/GOODBYEADS"))
        items.add(Card("sve1r(Rules-For-Quantumult-X)\nhttps://github.com/sve1r/Rules-For-Quantumult-X"))
        items.add(Card("fmz200(wool_scripts)\nhttps://github.com/fmz200/wool_scripts"))

        items.add(Category("FeedBack"))
        items.add(Card("Telegram\nhttps://t.me/AdClose"))
        items.add(Card("Telegram Group\nhttps://t.me/AdClose_Chat"))

        items.add(Category("Open Source"))
        items.add(License("XposedBridge", "rovo89", License.APACHE_2, "https://github.com/rovo89/XposedBridge"))
        items.add(License("DexKit", "LuckyPray", License.GPL_V3, "https://github.com/LuckyPray/DexKit"))
        items.add(License("AndroidX", "Google", License.APACHE_2, "https://source.google.com"))
        items.add(License("material-components-android", "Google", License.APACHE_2, "https://github.com/material-components/material-components-android"))
        items.add(License("about-page", "drakeet", License.APACHE_2, "https://github.com/drakeet/about-page"))
        items.add(License("AndroidFastScroll", "zhanghai", License.APACHE_2, "https://github.com/zhanghai/AndroidFastScroll"))
        items.add(License("SwipeMenuRecyclerView", "aitsuki", License.MIT, "https://github.com/aitsuki/SwipeMenuRecyclerView"))
        items.add(License("RikkaX", "RikkaApps", License.MIT, "https://github.com/RikkaApps/RikkaX"))
        items.add(License("kotlin", "JetBrains", License.APACHE_2, "https://github.com/JetBrains/kotlin"))
        items.add(License("glide", "bumptech", License.APACHE_2, "https://github.com/bumptech/glide"))
        items.add(License("Guava", "Google", License.APACHE_2, "https://github.com/google/guava"))

    }
}
