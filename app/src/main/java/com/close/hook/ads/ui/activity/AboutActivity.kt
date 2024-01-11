package com.close.hook.ads.ui.activity

import android.annotation.SuppressLint
import android.widget.ImageView
import android.widget.TextView
import com.close.hook.ads.BuildConfig
import com.drakeet.about.*
import com.close.hook.ads.R

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
    |这是一个Xposed模块，请在拥有LSPosed框架环境下使用。
    |
    |
    |主要用于阻止常见平台广告与部分SDK的初始化加载和屏蔽应用的广告请求。
    |
    |同时提供了一些其他Hook功能和特定应用去广告适配。
    |
    |
    |请不要请将此模块发送分享于QQ群，酷安，葫芦侠等社区，谢谢配合。
    |为此，我已经将AdClose上架到了LSPosed模块仓库。
    |做这个模块的初衷是为了能够给大家获得更好的应用体验，所以请不要做出以上的行为，我们真的花了很多心思，非常不希望看到这些事情发生。
    """.trimMargin()))

        items.add(Category("Developer"))
        items.add(Contributor(R.drawable.cont_author, "zjyzip", "Developer & Designer", "https://github.com/zjyzip"))
        items.add(Line())
        items.add(Contributor(R.drawable.cont_bggrgjqaubcoe, "bggRGjQaUbCoE", "Developer & Collaborator", "https://github.com/bggRGjQaUbCoE"))

        items.add(Category("Thanks-AdRules"))
        items.add(Card("Twilight(AWAvenue-Ads-Rule)\nhttps://github.com/TG-Twilight/AWAvenue-Ads-Rule"))
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
        items.add(License("kotlin", "JetBrains", License.APACHE_2, "https://github.com/JetBrains/kotlin"))
        items.add(License("about-page", "drakeet", License.APACHE_2, "https://github.com/drakeet/about-page"))
        items.add(License("AndroidX", "Google", License.APACHE_2, "https://source.google.com"))
        items.add(License("AndroidFastScroll", "zhanghai", License.APACHE_2, "https://github.com/zhanghai/AndroidFastScroll"))
        items.add(License("RikkaX", "RikkaApps", License.MIT, "https://github.com/RikkaApps/RikkaX"))
        items.add(License("material-components-android", "Google", License.APACHE_2, "https://github.com/material-components/material-components-android"))
        items.add(License("Guava", "Google", License.APACHE_2, "https://github.com/google/guava"))
        items.add(License("glide", "bumptech", License.APACHE_2, "https://github.com/bumptech/glide"))
        items.add(License("RxJava", "ReactiveX", License.APACHE_2, "https://github.com/ReactiveX/RxJava"))
        items.add(License("RxJava", "RxAndroid", License.APACHE_2, "https://github.com/ReactiveX/RxAndroid"))
    }
}
