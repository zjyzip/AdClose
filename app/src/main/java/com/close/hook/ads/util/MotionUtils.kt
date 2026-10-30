package com.close.hook.ads.util

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings

object MotionUtils {
    fun areAnimationsEnabled(context: Context): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        val resolver = context.contentResolver
        val animatorScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        val transitionScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        )
        return animatorScale > 0f && transitionScale > 0f
    }

    fun duration(context: Context, baseMillis: Long): Long =
        if (areAnimationsEnabled(context)) baseMillis else 0L
}
