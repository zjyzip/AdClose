package com.close.hook.ads.ui.fragment.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.ThemeUtils
import androidx.core.graphics.ColorUtils
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.databinding.DialogAboutBinding
import com.close.hook.ads.databinding.FragmentSettingBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.material.app.LocaleDelegate

class SettingsFragment : BaseFragment<FragmentSettingBinding>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initMenu()

    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.settings_menu)
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.feedback -> {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://t.me/AdClose_Chat")
                            )
                        )
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), "打开失败", Toast.LENGTH_SHORT).show()
                    }
                }

                R.id.about -> AboutDialog().show(childFragmentManager, "about")

            }
            return@setOnMenuItemClickListener true
        }
    }

    class AboutDialog : DialogFragment() {
        @SuppressLint("RestrictedApi")
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val binding: DialogAboutBinding =
                DialogAboutBinding.inflate(layoutInflater, null, false)
            binding.designAboutTitle.setText(R.string.app_name)
            binding.designAboutInfo.movementMethod = LinkMovementMethod.getInstance()
            binding.designAboutInfo.highlightColor = ColorUtils.setAlphaComponent(
                ThemeUtils.getThemeAttrColor(
                    requireContext(),
                    rikka.preference.simplemenu.R.attr.colorPrimaryDark
                ), 128
            )
            binding.designAboutInfo.text = HtmlCompat.fromHtml(
                getString(
                    R.string.about_view_source_code,
                    "<b><a href=\"https://github.com/zjyzip/AdClose\">GitHub</a></b>",
                ), HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            binding.designAboutVersion.text = java.lang.String.format(
                LocaleDelegate.defaultLocale,
                "%s (%d)",
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
            return MaterialAlertDialogBuilder(requireContext()).setView(binding.root).create()
        }
    }

}