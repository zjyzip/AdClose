package com.close.hook.ads.ui.activity

import android.os.Bundle
import com.close.hook.ads.R
import com.close.hook.ads.databinding.ActivityDataManagerBinding
import com.close.hook.ads.ui.fragment.DataManagerFragment

class DataManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityDataManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DataManagerFragment())
                .commit()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
