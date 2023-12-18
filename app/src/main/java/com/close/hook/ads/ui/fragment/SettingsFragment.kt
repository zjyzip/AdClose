package com.close.hook.ads.ui.fragment

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.databinding.DialogAboutBinding
import com.close.hook.ads.databinding.FragmentSettingBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.material.app.LocaleDelegate

class SettingsFragment : Fragment() {

    private lateinit var binding: FragmentSettingBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

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
                                Uri.parse("https://t.me/AdClose")
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
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val binding: DialogAboutBinding =
                DialogAboutBinding.inflate(layoutInflater, null, false)
            binding.designAboutTitle.setText(R.string.app_name)
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