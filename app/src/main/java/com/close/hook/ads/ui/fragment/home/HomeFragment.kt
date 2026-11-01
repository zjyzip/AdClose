package com.close.hook.ads.ui.fragment.home

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.ui.viewmodel.HomeViewModel
import com.close.hook.ads.ui.viewmodel.RequestViewModel
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentHomeBinding
import com.close.hook.ads.debug.PerformanceActivity
import com.close.hook.ads.manager.ConnectionState
import com.close.hook.ads.manager.ServiceManager
import com.close.hook.ads.ui.activity.AboutActivity
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.MotionUtils
import com.close.hook.ads.util.resolveColorAttr
import kotlinx.coroutines.launch

class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    private val homeViewModel by viewModels<HomeViewModel>()
    private val requestViewModel by activityViewModels<RequestViewModel>()

    private var lastStatusActivated: Boolean? = null
    private var statusColorAnimator: ValueAnimator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initToolBar()
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceManager.connectionState.collect { state ->
                    updateStatus(state is ConnectionState.Connected)
                }
            }
        }

        setSystemInfo(requireContext())
        setHyperLinks()
        observeOverview()
        animateHomeEntrance()
    }

    private fun observeOverview() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    requestViewModel.requestList.collect { requests ->
                        binding.overviewRecentSummary.text = getString(R.string.overview_recent_count,
                            requests.count { it.isBlocked == true }, requests.size)
                    }
                }
                homeViewModel.overview.collect { overview ->
                    binding.overviewRulesValue.text = overview.localRules?.toString()
                        ?: getString(R.string.overview_unavailable)
                    binding.overviewSubscriptionsValue.text = overview.subscriptionCount.toString()
                    binding.overviewAppsValue.text = overview.configuredApps?.toString()
                        ?: getString(R.string.overview_unavailable)
                    binding.overviewSubscriptionSummary.text = getString(R.string.overview_subscriptions_status,
                        overview.enabledSubscriptions, overview.subscriptionCount, overview.subscriptionsWithErrors)
                }
            }
        }
    }

    private fun animateHomeEntrance() {
        if (!MotionUtils.areAnimationsEnabled(requireContext())) {
            listOf(binding.status, binding.overviewCard, binding.infoCard, binding.sponsorCard).forEach { target ->
                target.alpha = 1f
                target.translationY = 0f
            }
            return
        }
        listOf(binding.status, binding.overviewCard, binding.infoCard, binding.sponsorCard).forEachIndexed { index, target ->
            target.alpha = 0f
            target.translationY = 18f
            target.post {
                target.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(index * 70L)
                    .setDuration(280L)
                    .start()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus(isActivated: Boolean) {
        val context = requireContext()
        val bgAttr = if (isActivated) {
            androidx.appcompat.R.attr.colorPrimary
        } else {
            // colorErrorContainer is the M3 soft pastel red — same semantic meaning
            // as colorError but readable as a surface, not a screaming alert.
            com.google.android.material.R.attr.colorErrorContainer
        }
        val onBgAttr = if (isActivated) {
            com.google.android.material.R.attr.colorOnPrimary
        } else {
            com.google.android.material.R.attr.colorOnErrorContainer
        }
        val bgColor = context.resolveColorAttr(bgAttr)
        val onBgColor = context.resolveColorAttr(onBgAttr)

        val iconRes = if (isActivated) R.drawable.ic_round_check_circle_24 else R.drawable.ic_about
        val wasActivated = lastStatusActivated
        lastStatusActivated = isActivated

        binding.statusTitle.setTextColor(onBgColor)
        binding.statusSummary.setTextColor(onBgColor)
        binding.statusTitle.text = getString(if (isActivated) R.string.activated else R.string.not_activated)
        binding.statusSummary.text = getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(onBgColor)

        if (wasActivated == null || !MotionUtils.areAnimationsEnabled(context)) {
            binding.status.setCardBackgroundColor(bgColor)
            binding.statusIcon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            return
        }

        val startColor = binding.status.cardBackgroundColor.defaultColor
        statusColorAnimator?.cancel()
        statusColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, bgColor).apply {
            duration = 280L
            addUpdateListener { animator ->
                binding.status.setCardBackgroundColor(animator.animatedValue as Int)
            }
            start()
        }

        binding.statusIcon.animate()
            .alpha(0f)
            .setDuration(100L)
            .withEndAction {
                binding.statusIcon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                binding.statusIcon.animate().alpha(1f).setDuration(160L).start()
            }
            .start()

        binding.status.animate()
            .scaleX(1.018f)
            .scaleY(1.018f)
            .setDuration(120L)
            .withEndAction {
                binding.status.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
            }
            .start()
    }

    override fun onDestroyView() {
        statusColorAnimator?.cancel()
        statusColorAnimator = null
        super.onDestroyView()
    }

    @SuppressLint("HardwareIds")
    private fun setSystemInfo(context: Context) {
        val contentResolver = context.contentResolver

        binding.apply {
            androidVersionValue.text = Build.VERSION.RELEASE
            sdkVersionValue.text = Build.VERSION.SDK_INT.toString()

            androidIdValue.text = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            brandValue.text = Build.MANUFACTURER
            modelValue.text = Build.MODEL

            skuValue.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SKU else ""

            typeValue.text = when {
                Build.TYPE == "user" -> "Release"
                Build.TYPE == "userdebug" -> "Debug"
                Build.TYPE == "eng" -> "Engineering"
                else -> Build.TYPE
            }

            fingerValue.text = Build.FINGERPRINT
        }
    }

    private fun setHyperLinks() {
        val linkMovementMethod = LinkMovementMethod.getInstance()
        binding.apply {
            viewSource.movementMethod = linkMovementMethod
            viewSource.text = HtmlCompat.fromHtml(
                getString(R.string.about_view_source_code, "<b><a href=\"https://github.com/zjyzip/AdClose\">GitHub</a></b>"),
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )

            feedback.movementMethod = linkMovementMethod
            feedback.text = HtmlCompat.fromHtml(
                getString(R.string.join_telegram_channel, "<b><a href=\"https://t.me/AdClose\">Telegram</a></b>"),
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
        }
    }

    private fun initToolBar() {
        binding.toolbar.apply {
            title = getString(R.string.app_name)
            inflateMenu(R.menu.menu_home)

            setOnMenuItemClickListener {
                if (it.itemId == R.id.about) {
                    startActivity(Intent(requireContext(), AboutActivity::class.java))
                }
                true
            }

            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startActivity(Intent(requireContext(), PerformanceActivity::class.java))
                }
            })

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }
    }
}
