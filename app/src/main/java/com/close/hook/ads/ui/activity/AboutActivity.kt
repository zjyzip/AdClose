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
    |这个一个Xposed模块
    |
    |用于阻止常见平台广告与部分SDK的初始化和屏蔽域名请求。
    |同时提供了一些其他Hook功能和特定应用去广告适配。
    |请在拥有LSPosed框架环境下使用。
    |
    |
    |这个模块也是本人在空闲之时写的，目前只是提供了基础的功能，
    |后续也会慢慢更新，也欢迎大家提供建议和反馈。
    """.trimMargin()))

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
    }
}
