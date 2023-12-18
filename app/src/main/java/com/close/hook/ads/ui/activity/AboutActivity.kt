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
    |(建议关闭LSPosed管理器中的Xposed API调用保护功能，否则导致部分功能失效)
    |
    |
    |主要用于阻止常见平台广告与部分SDK的初始化加载和屏蔽应用的广告请求。
    |(感谢Twilight提供的AWAvenue-Ads-Rule广告规则)
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

        items.add(Category("Thanks"))
        items.add(Card("Twilight(AWAvenue-Ads-Rule)\nhttps://github.com/TG-Twilight/AWAvenue-Ads-Rule"))

        items.add(Category("FeedBack"))
        items.add(Card("Telegram\nhttps://t.me/AdClose"))

        items.add(Category("Open Source"))
        items.add(License("kotlin", "JetBrains", License.APACHE_2, "https://github.com/JetBrains/kotlin"))
        items.add(License("AndroidX", "Google", License.APACHE_2, "https://source.google.com"))
        items.add(License("material-components-android", "Google", License.APACHE_2, "https://github.com/material-components/material-components-android"))
        items.add(License("about-page", "drakeet", License.APACHE_2, "https://github.com/drakeet/about-page"))
        items.add(License("RxJava", "ReactiveX", License.APACHE_2, "https://github.com/ReactiveX/RxJava"))
        items.add(License("RxJava", "RxAndroid", License.APACHE_2, "https://github.com/ReactiveX/RxAndroid"))
        items.add(License("glide", "bumptech", License.APACHE_2, "https://github.com/bumptech/glide"))
        items.add(License("AndroidFastScroll", "zhanghai", License.APACHE_2, "https://github.com/zhanghai/AndroidFastScroll"))
        items.add(License("RikkaX", "RikkaApps", License.MIT, "https://github.com/RikkaApps/RikkaX"))
    }
}
