package com.close.hook.ads.ui.fragment.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentSettingBinding
import com.close.hook.ads.ui.activity.AboutActivity
import com.close.hook.ads.ui.fragment.base.BaseFragment

class SettingsFragment : BaseFragment<FragmentSettingBinding>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initMenu()
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.settings_menu)
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.feedback -> openTelegramGroup()
                R.id.about -> startActivity(Intent(requireContext(), AboutActivity::class.java))
            }
            true
        }
    }

    private fun openTelegramGroup() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/AdClose_Chat")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.launch_app_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
