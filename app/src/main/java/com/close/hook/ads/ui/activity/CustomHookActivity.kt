package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.close.hook.ads.databinding.ActivityCustomHookBinding
import com.close.hook.ads.ui.fragment.hook.CustomHookManagerFragment
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener

class CustomHookActivity : BaseActivity(), OnBackPressContainer {

    private lateinit var binding: ActivityCustomHookBinding
    override var backController: OnBackPressListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomHookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val packageName = intent.getStringExtra("packageName")
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 1

            override fun createFragment(position: Int): Fragment {
                return CustomHookManagerFragment.newInstance(packageName)
            }
        }
    }

    override fun onBackPressed() {
        if (backController?.onBackPressed() == true) {
            return
        }
        super.onBackPressed()
    }
}
