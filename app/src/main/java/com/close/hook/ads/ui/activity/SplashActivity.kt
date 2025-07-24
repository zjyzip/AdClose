package com.close.hook.ads.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SplashActivity : Activity() {

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
