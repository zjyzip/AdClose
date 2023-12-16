package com.close.hook.ads.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentAboutBinding
import com.close.hook.ads.databinding.ItemAboutBinding
import com.close.hook.ads.ui.activity.AboutActivity
import com.example.c001apk.util.DensityTool
import com.google.android.material.card.MaterialCardView


class AboutFragment : Fragment() {

    private lateinit var binding: FragmentAboutBinding
    private lateinit var aboutList: ArrayList<ItemBean>
    private lateinit var developerList: ArrayList<ItemBean>
    private lateinit var feedBackList: ArrayList<ItemBean>
    private lateinit var thanksList: ArrayList<ItemBean>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolBar.title = requireContext().getString(R.string.bottom_item_3)

        binding.toolBar.apply {
            inflateMenu(R.menu.menu_about)
            setOnMenuItemClickListener {
                if (it.itemId == R.id.about) {
                    requireContext().startActivity(
                        Intent(
                            requireContext(),
                            AboutActivity::class.java
                        )
                    )
                }
                true
            }
        }

        initDataList()

        binding.aboutLayout.apply {
            addView(generateCard(aboutList, null))
            addView(generateCard(developerList, "Developers"))
            addView(generateCard(thanksList, "Thanks"))
            addView(generateCard(feedBackList, "FeedBack"))
        }

    }

    private fun initDataList() {
        aboutList = ArrayList()
        aboutList.apply {
            add(
                ItemBean(
                    R.drawable.ic_launcher,
                    null,
                    requireContext().getString(R.string.app_name),
                    """
    |这个一个Xposed模块
    |
    |用于阻止常见平台广告与部分SDK的初始化加载和屏蔽应用的广告请求。
    |
    |同时提供了一些其他Hook功能和特定应用去广告适配。
    |
    |请在拥有LSPosed框架环境下使用。
    |
    |
    |这个模块也是本人在空闲之时写的，目前只是提供了基础的功能，后续也会慢慢更新，也欢迎大家提供建议和反馈。
    """.trimMargin()
                )
            )
            add(
                ItemBean(
                    R.drawable.ic_about,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "Version",
                    "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
                )
            )
        }
        developerList = ArrayList()
        developerList.apply {
            add(
                ItemBean(
                    R.drawable.ic_person,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "Reese",
                    "Loyal to life."
                )
            )
            add(
                ItemBean(
                    R.drawable.ic_person,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "bggRGjQaUbCoE",
                    "No desc, is a lazy man."
                )
            )
        }
        feedBackList = ArrayList()
        feedBackList.apply {
            add(
                ItemBean(
                    R.drawable.ic_telegram,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "Reese_XPModule",
                    "https://t.me/Reese_XPModule"
                )
            )
        }
        thanksList = ArrayList()
        thanksList.apply {
            add(
                ItemBean(
                    R.drawable.ic_github,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "Twilight(AWAvenue-Ads-Rule)",
                    "https://github.com/TG-Twilight/AWAvenue-Ads-Rule"
                )
            )
        }
    }

    private fun generateCard(dataList: List<ItemBean>, title: String?): View {
        val aboutCardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (title == "FeedBack")
                setMargins(
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt()
                )
            else
                setMargins(
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    0
                )
        }
        val aboutCard = MaterialCardView(requireContext()).apply {
            strokeWidth = 0
            cardElevation = DensityTool.dp2px(requireContext(), 1f)
            isClickable = false
            layoutParams = aboutCardParams
        }
        val aboutLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        title?.let {
            val textView = TextView(requireContext())
            val textViewParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    DensityTool.dp2px(requireContext(), 10f).toInt(),
                    0
                )
            }
            textView.apply {
                text = it
                textSize = 16f
                paint.isFakeBoldText = true
                layoutParams = textViewParams
            }
            aboutLayout.addView(textView)
        }
        for (element in dataList) {
            aboutLayout.addView(
                generateView(
                    element.resourceId,
                    element.size,
                    element.title,
                    element.desc
                )
            )
        }
        aboutCard.addView(aboutLayout)
        return aboutCard
    }

    private fun generateView(resourceId: Int, size: Int?, title: String, desc: String): View {
        val binding = ItemAboutBinding.inflate(layoutInflater, null, false)
        binding.imageView.setImageResource(resourceId)
        size?.let {
            val lp = ConstraintLayout.LayoutParams(it, it)
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.imageView.layoutParams = lp
        }
        binding.title.text = title
        binding.desc.text = desc
        if (title == "Reese_XPModule")
            binding.root.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Reese_XPModule"))
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), "打开失败", Toast.LENGTH_SHORT).show()
                    Log.w("error", "Activity was not found for intent, $intent")
                }
            }
        else if (title == "Twilight(AWAvenue-Ads-Rule)") {
            binding.root.setOnClickListener {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/TG-Twilight/AWAvenue-Ads-Rule")
                )
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), "打开失败", Toast.LENGTH_SHORT).show()
                    Log.w("error", "Activity was not found for intent, $intent")
                }
            }
        }
        return binding.root
    }

    data class ItemBean(
        val resourceId: Int,
        val size: Int?,
        val title: String,
        val desc: String
    )

}