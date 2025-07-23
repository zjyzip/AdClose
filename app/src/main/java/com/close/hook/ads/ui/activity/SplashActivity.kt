package com.close.hook.ads.ui.activity

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        setupSplashScreen(splashScreen)
    }

    private fun setupSplashScreen(splashScreen: SplashScreen) {
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val animators = mutableListOf<ObjectAnimator>()

            val alphaAnimator = ObjectAnimator.ofFloat(
                splashScreenView.view,
                View.ALPHA,
                1f,
                0f
            ).apply {
                duration = 500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            animators.add(alphaAnimator)

            val scaleXAnimator = ObjectAnimator.ofFloat(
                splashScreenView.view,
                View.SCALE_X,
                1f,
                0.98f
            ).apply {
                duration = 500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            animators.add(scaleXAnimator)

            val scaleYAnimator = ObjectAnimator.ofFloat(
                splashScreenView.view,
                View.SCALE_Y,
                1f,
                0.98f
            ).apply {
                duration = 500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            animators.add(scaleYAnimator)

            val animatorSet = AnimatorSet()
            animatorSet.playTogether(animators.toList())
            animatorSet.doOnEnd {
                splashScreenView.remove()
                navigateToNextScreen()
            }
            animatorSet.start()
        }
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
