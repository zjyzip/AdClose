package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.widget.ThemeUtils
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentHomeBinding
import com.close.hook.ads.ui.activity.AboutActivity
import com.close.hook.ads.ui.activity.MainActivity

class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initToolBar()
        initInfo()

    }

    @SuppressLint("SetTextI18n", "RestrictedApi", "HardwareIds")
    private fun initInfo() {
        val isActivated = MainActivity.isModuleActivated()
        binding.apply {
            status.setCardBackgroundColor(
                ThemeUtils.getThemeAttrColor(
                    requireContext(),
                    if (isActivated) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorError
                )
            )
            statusIcon.setImageDrawable(
                requireContext().getDrawable(
                    if (isActivated) R.drawable.ic_round_check_circle_24
                    else R.drawable.ic_about
                )
            )
            statusTitle.text = if (isActivated) "已激活"
            else "未激活"
            statusSummary.text = "版本: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

            androidVersionValue.text = Build.VERSION.RELEASE
            sdkVersionValue.text = Build.VERSION.SDK_INT.toString()
            androidIdValue.text = Settings.Secure.getString(
                requireContext().contentResolver,
                Settings.Secure.ANDROID_ID
            )
            brandValue.text = Build.BRAND
            modelValue.text = Build.MODEL
            skuValue.text = if (Build.VERSION.SDK_INT >= 31) Build.SKU else ""
            typeValue.text = Build.TYPE
            fingerValue.text = Build.FINGERPRINT
        }
    }

    private fun initToolBar() {
        binding.toolbar.apply {
            title = getString(R.string.app_name)
            inflateMenu(R.menu.menu_home)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.about -> requireContext().startActivity(
                        Intent(
                            requireContext(),
                            AboutActivity::class.java
                        )
                    )
                }

                return@setOnMenuItemClickListener true
            }
        }
    }

}
