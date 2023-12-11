package com.close.hook.ads.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.close.hook.ads.BuildConfig
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentAboutBinding
import com.close.hook.ads.databinding.ItemAboutBinding
import com.example.c001apk.util.DensityTool
import com.google.android.material.card.MaterialCardView


class AboutFragment : Fragment() {

    private lateinit var binding: FragmentAboutBinding
    private lateinit var aboutList: ArrayList<ItemBean>
    private lateinit var developerList: ArrayList<ItemBean>
    private lateinit var companyList: ArrayList<ItemBean>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolBar.title = requireContext().getString(R.string.bottom_item_2)

        initDataList()

        binding.aboutLayout.apply {
            addView(generateCard(aboutList, null))
            addView(generateCard(developerList, "Developers"))
            addView(generateCard(companyList, "Company"))
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
                "这个一个Xposed模块，用于阻止常见广告与部分sdk的初始化加载，和屏蔽域名请求。" +
                "同时提供了一些其他Hook功能和特定应用去广告适配。\n请在拥有环境下使用。"
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
        companyList = ArrayList()
        companyList.apply {
            add(
                ItemBean(
                    R.drawable.ic_company,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "your name",
                    "your desc"
                )
            )
            add(
                ItemBean(
                    R.drawable.ic_location,
                    DensityTool.dp2px(requireContext(), 20f).toInt(),
                    "your name",
                    "your desc"
                )
            )
        }
    }

    private fun generateCard(dataList: List<ItemBean>, title: String?): View {
        val aboutCardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
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
        return binding.root
    }

    data class ItemBean(
        val resourceId: Int,
        val size: Int?,
        val title: String,
        val desc: String
    )

}