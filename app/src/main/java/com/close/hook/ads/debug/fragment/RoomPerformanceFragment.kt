package com.close.hook.ads.debug.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.close.hook.ads.R
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentRoomPerformanceBinding
import com.close.hook.ads.databinding.ItemLegendBinding
import com.close.hook.ads.debug.datasource.TestDataSource
import com.close.hook.ads.util.resolveColorAttr
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

@RequiresApi(Build.VERSION_CODES.N)
class RoomPerformanceFragment : Fragment() {
    private var _binding: FragmentRoomPerformanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var testDatabase: UrlDatabase
    private lateinit var testDataSource: TestDataSource

    private val chartDataEntries = mutableMapOf<ChartMetric, MutableList<Entry>>()
    private val chartLabels = mutableListOf<String>()
    private val TEST_REPEAT_TIMES = 3
    private val TEST_DATA_SIZE = 10000

    enum class ChartMetric(val label: String, val colorResId: Int) {
        INSERT("插入", R.color.md_theme_light_primary),
        QUERY_ALL("查询全部", R.color.md_theme_light_tertiary),
        DELETE("删除", R.color.md_theme_light_error),
        
        EXISTS_URL_MATCH("URL匹配", R.color.md_theme_light_secondary),
        EXISTS_KEYWORD_MATCH("关键词匹配", R.color.md_theme_light_primaryContainer),
        EXISTS_DOMAIN("域名匹配", R.color.md_theme_light_secondaryContainer),
        
        NOT_FOUND_URL_PREFIX("未找到URL", R.color.md_theme_light_tertiaryContainer),
        NOT_FOUND_KEYWORD("未找到关键词", R.color.md_theme_light_surfaceVariant)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoomPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        testDatabase = Room.inMemoryDatabaseBuilder(
            requireContext().applicationContext,
            UrlDatabase::class.java
        ).build()

        testDataSource = TestDataSource(testDatabase.urlDao)

        ChartMetric.values().forEach { metric -> chartDataEntries[metric] = mutableListOf() }
        
        setupPerformanceChart()
        setupCustomLegend()
        setupListeners()
    }

    private fun setupCustomLegend() {
        bindLegendItem(binding.legendInsert, ChartMetric.INSERT)
        bindLegendItem(binding.legendQuery, ChartMetric.QUERY_ALL)
        bindLegendItem(binding.legendDelete, ChartMetric.DELETE)
        
        bindLegendItem(binding.legendMatchUrl, ChartMetric.EXISTS_URL_MATCH)
        bindLegendItem(binding.legendMatchKeyword, ChartMetric.EXISTS_KEYWORD_MATCH)
        bindLegendItem(binding.legendMatchDomain, ChartMetric.EXISTS_DOMAIN)
    }

    private fun bindLegendItem(itemBinding: ItemLegendBinding, metric: ChartMetric) {
        itemBinding.legendColor.backgroundTintList = ColorStateList.valueOf(getColor(metric.colorResId))
        itemBinding.legendText.text = metric.label
    }

    private fun getColor(resId: Int): Int {
        return try {
            ContextCompat.getColor(requireContext(), resId)
        } catch (e: Exception) {
            Color.GRAY
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        testDatabase.close()
        _binding = null
    }

    private fun setupListeners() {
        binding.runTestButton.setOnClickListener {
            it.isEnabled = false
            lifecycleScope.launch {
                try { runAllPerformanceTests() } finally { it.isEnabled = true }
            }
        }
    }

    private fun setupPerformanceChart() {
        with(binding.memoryChart) {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(false)

            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(false)
                granularity = 1f
                valueFormatter = IndexAxisValueFormatter(chartLabels)
                textColor = requireContext().resolveColorAttr(android.R.attr.textColorSecondary)
                textSize = 12f
                yOffset = 10f
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1A000000")
                enableGridDashedLine(10f, 10f, 0f)
                setDrawAxisLine(false)
                textColor = requireContext().resolveColorAttr(android.R.attr.textColorSecondary)
                textSize = 10f
                axisMinimum = 0f
            }

            axisRight.isEnabled = false
            marker = CustomMarkerView(context, R.layout.layout_marker_view)
        }
    }

    private suspend fun runAllPerformanceTests() = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            clearAllPerformanceData()
            binding.timeSummary.text = ""
        }

        val totalTimes = ChartMetric.values().associateWith { 0L }.toMutableMap()

        repeat(TEST_REPEAT_TIMES) { index ->
            val testRunId = index + 1
            postLog("--- ▶️ 第 $testRunId 次Room测试开始 ---")

            val urlList = List(TEST_DATA_SIZE) { i ->
                val uniqueId = index * TEST_DATA_SIZE + i
                if (i % 3 == 0) {
                    Url(type = "URL", url = "https://example.com/path/$uniqueId")
                } else if (i % 3 == 1) {
                    Url(type = "Domain", url = "domain-$uniqueId.com")
                } else {
                    Url(type = "KeyWord", url = "keyword-$uniqueId")
                }
            }

            val insertTime = measurePerformance { testDataSource.insertAll(urlList) }
            postLog("📦 批量插入$TEST_DATA_SIZE 条: ${insertTime}ms")
            totalTimes[ChartMetric.INSERT] = totalTimes[ChartMetric.INSERT]!! + insertTime
            delay(400)

            val queryAllTime = measurePerformance { testDataSource.getUrlListOnce() }
            postLog("🔍 查询所有: ${queryAllTime}ms")
            totalTimes[ChartMetric.QUERY_ALL] = totalTimes[ChartMetric.QUERY_ALL]!! + queryAllTime
            delay(400)

            val existsUrlToFind = urlList.firstOrNull { it.type == "URL" }?.url ?: "https://nonexistent.com/path/0"
            val existsUrlMatchTime = measurePerformance { testDataSource.existsUrlMatch(existsUrlToFind) }
            postLog("🟢 精准URL前缀查找 (找到): ${existsUrlMatchTime}ms")
            totalTimes[ChartMetric.EXISTS_URL_MATCH] = totalTimes[ChartMetric.EXISTS_URL_MATCH]!! + existsUrlMatchTime

            val existsKeywordToFind = urlList.firstOrNull { it.type == "KeyWord" }?.url ?: "nonexistent-keyword"
            val queryLikeTime = measurePerformance { testDataSource.existsKeywordMatch("text containing $existsKeywordToFind") }
            postLog("🟡 任意包含 (关键词查找 - 找到): ${queryLikeTime}ms")
            totalTimes[ChartMetric.EXISTS_KEYWORD_MATCH] = totalTimes[ChartMetric.EXISTS_KEYWORD_MATCH]!! + queryLikeTime

            val notFoundUrl = "https://nonexistent.com/path/999999"
            val notFoundUrlPrefixTime = measurePerformance { testDataSource.existsUrlMatch(notFoundUrl) }
            postLog("🔷 URL前缀查找 (未找到): ${notFoundUrlPrefixTime}ms")
            totalTimes[ChartMetric.NOT_FOUND_URL_PREFIX] = totalTimes[ChartMetric.NOT_FOUND_URL_PREFIX]!! + notFoundUrlPrefixTime

            val existsDomainToFind = urlList.firstOrNull { it.type == "Domain" }?.url ?: "nonexistent-domain.com"
            val existsDomainTime = measurePerformance { testDataSource.existsDomainMatch("http://$existsDomainToFind/some/path") }
            postLog("🔶 Domain包含查找 (找到): ${existsDomainTime}ms")
            totalTimes[ChartMetric.EXISTS_DOMAIN] = totalTimes[ChartMetric.EXISTS_DOMAIN]!! + existsDomainTime

            val notFoundKeyword = "absolutely-nonexistent-keyword"
            val notFoundKeywordTime = measurePerformance { testDataSource.existsKeywordMatch("some text without $notFoundKeyword") }
            postLog("🔸 Keyword查找 (未找到): ${notFoundKeywordTime}ms")
            totalTimes[ChartMetric.NOT_FOUND_KEYWORD] = totalTimes[ChartMetric.NOT_FOUND_KEYWORD]!! + notFoundKeywordTime

            val deleteTime = measurePerformance { testDataSource.deleteAll() }
            postLog("❌ 删除全部: ${deleteTime}ms")
            totalTimes[ChartMetric.DELETE] = totalTimes[ChartMetric.DELETE]!! + deleteTime

            chartLabels.add("Run $testRunId")
            val currentRunResults = mapOf(
                ChartMetric.INSERT to insertTime,
                ChartMetric.QUERY_ALL to queryAllTime,
                ChartMetric.EXISTS_URL_MATCH to existsUrlMatchTime,
                ChartMetric.EXISTS_KEYWORD_MATCH to queryLikeTime,
                ChartMetric.NOT_FOUND_URL_PREFIX to notFoundUrlPrefixTime,
                ChartMetric.EXISTS_DOMAIN to existsDomainTime,
                ChartMetric.NOT_FOUND_KEYWORD to notFoundKeywordTime,
                ChartMetric.DELETE to deleteTime
            )
            updateChartDataEntries(index, currentRunResults)
            
            postLog("--- 第 $testRunId 次Room测试结束 ---\n")
            delay(500)
        }
        withContext(Dispatchers.Main) { updatePerformanceChart() }
        postSummary(totalTimes)
        postLog("\n🚀 Room性能测试完成。")
    }

    private suspend fun measurePerformance(block: suspend () -> Unit): Long {
        return measureTimeMillis { block() }
    }

    private fun updateChartDataEntries(index: Int, results: Map<ChartMetric, Long>) {
        results.forEach { (metric, time) ->
            chartDataEntries[metric]?.add(Entry(index.toFloat(), time.toFloat()))
        }
    }

    private fun postSummary(totalTimes: Map<ChartMetric, Long>) {
        val summary = StringBuilder().apply {
            append("--- 🎯 Room性能测试总结 ---\n")
            ChartMetric.values().forEach { metric ->
                append("平均${metric.label.replace(" (ms)", "")}: ${totalTimes[metric]?.div(TEST_REPEAT_TIMES)}ms\n")
            }
            append("-----------------------\n")
        }.toString()
        
        postText { binding.timeSummary.text = summary }
        
        postLog(summary)
    }

    private fun clearAllPerformanceData() {
        chartDataEntries.values.forEach { it.clear() }
        chartLabels.clear()
        binding.memoryChart.data = null
        binding.memoryChart.notifyDataSetChanged()
        binding.memoryChart.invalidate()
        binding.logView.text = ""
    }

    private fun updatePerformanceChart() {
        val dataSets = mutableListOf<LineDataSet>()
        
        val metricsToDraw = listOf(
            ChartMetric.INSERT, 
            ChartMetric.QUERY_ALL, 
            ChartMetric.DELETE,
            ChartMetric.EXISTS_URL_MATCH,
            ChartMetric.EXISTS_KEYWORD_MATCH,
            ChartMetric.EXISTS_DOMAIN
        )

        metricsToDraw.forEach { metric ->
            val entries = chartDataEntries[metric]
            if (!entries.isNullOrEmpty()) {
                val color = getColor(metric.colorResId)
                val dataSet = LineDataSet(entries, metric.label).apply {
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                    cubicIntensity = 0.2f
                    
                    setDrawIcons(false)
                    setDrawCircles(false)
                    setDrawValues(false)
                    
                    lineWidth = 2f
                    this.color = color
                    
                    setDrawFilled(true)
                    fillDrawable = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(adjustAlpha(color, 0.6f), adjustAlpha(color, 0.1f))
                    )
                    
                    setDrawCircleHole(false)
                    enableDashedHighlightLine(10f, 5f, 0f)
                    highLightColor = requireContext().resolveColorAttr(android.R.attr.textColorPrimary)
                }
                dataSets.add(dataSet)
            }
        }
        
        if (dataSets.isNotEmpty()) {
            val data = LineData(dataSets.toList())
            binding.memoryChart.data = data
            binding.memoryChart.xAxis.valueFormatter = IndexAxisValueFormatter(chartLabels)
            binding.memoryChart.xAxis.axisMinimum = -0.2f
            binding.memoryChart.xAxis.axisMaximum = (chartLabels.size - 1) + 0.2f
            
            binding.memoryChart.animateY(1000)
            binding.memoryChart.invalidate()
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    private fun postLog(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.logView.append("$message\n")
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun postText(action: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { action() }
    }
    
    inner class CustomMarkerView(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {
        private val tvContent: TextView = findViewById(R.id.tvContent)

        override fun refreshContent(e: Entry?, highlight: Highlight?) {
            if (e != null) {
                tvContent.text = "${e.y.toInt()} ms"
            }
            super.refreshContent(e, highlight)
        }

        override fun getOffset(): MPPointF {
            return MPPointF(-(width / 2).toFloat(), -height.toFloat())
        }
    }
}
