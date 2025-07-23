package com.close.hook.ads.ui.activity

import android.content.Intent
import android.os.Bundle

class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        navigateToNextScreen()
    }

    private fun navigateToNextScreen() {
        val intent: Intent = if (MainActivity.isModuleActivated()) {
            Intent(this@SplashActivity, MainActivity::class.java)
        } else {
            Intent(this@SplashActivity, ModuleNotActivatedActivity::class.java)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
