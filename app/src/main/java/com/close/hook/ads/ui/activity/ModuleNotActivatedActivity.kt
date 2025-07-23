package com.close.hook.ads.ui.activity

import android.os.Bundle
import com.close.hook.ads.databinding.ActivityModuleNotActivatedBinding

class ModuleNotActivatedActivity : BaseActivity() {

    private lateinit var binding: ActivityModuleNotActivatedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModuleNotActivatedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonExit.setOnClickListener {
            finish()
        }
    }
}
