package com.close.hook.ads.debug.fragment

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentRoomPerformanceBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

@RequiresApi(Build.VERSION_CODES.N)
class RoomPerformanceFragment : Fragment() {
    private var _binding: FragmentRoomPerformanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataSource: DataSource
    private val chartDataEntries = mutableMapOf<ChartMetric, MutableList<Entry>>()
    private val chartLabels = mutableListOf<String>()
    private val TEST_REPEAT_TIMES = 3
    private val TEST_DATA_SIZE = 1000

    enum class ChartMetric(val label: String, val color: Int) {
        INSERT("插入 (ms)", Color.parseColor("#1E88E5")),
        QUERY_ALL("查询全部 (ms)", Color.parseColor("#6D4C41")),
        EXISTS_URL_MATCH("精准URL前缀 (ms)", Color.parseColor("#00897B")),
        EXISTS_KEYWORD_MATCH("任意包含 (ms)", Color.parseColor("#43A047")),
        NOT_FOUND_URL_PREFIX("URL前缀查找-未找到 (ms)", Color.parseColor("#D81B60")),
        EXISTS_DOMAIN("Domain包含查找 (ms)", Color.parseColor("#8E24AA")),
        NOT_FOUND_KEYWORD("Keyword查找-未找到 (ms)", Color.parseColor("#3949AB")),
        DELETE("删除 (ms)", Color.parseColor("#FB8C00"))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoomPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataSource = DataSource(requireContext())
        ChartMetric.values().forEach { metric -> chartDataEntries[metric] = mutableListOf() }
        setupPerformanceChart()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
            setTouchEnabled(true)
            setScaleEnabled(true)
            isDragEnabled = true
            setPinchZoom(true)
            description.isEnabled = false
            setExtraOffsets(5f, 10f, 10f, 25f)
            legend.apply {
                isWordWrapEnabled = true
                form = Legend.LegendForm.LINE
                textSize = 10f
                textColor = Color.BLACK
                orientation = Legend.LegendOrientation.HORIZONTAL
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                setDrawInside(false)
            }
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                valueFormatter = IndexAxisValueFormatter(chartLabels)
                setDrawGridLines(false)
                textSize = 12f
                textColor = Color.BLACK
            }
            axisLeft.apply {
                axisMinimum = 0f
                textSize = 12f
                textColor = Color.BLACK
            }
            axisRight.isEnabled = false
        }
    }

    private suspend fun runAllPerformanceTests() = withContext(Dispatchers.IO) {
        clearAllPerformanceData()
        val totalTimes = ChartMetric.values().associateWith { 0L }.toMutableMap()

        postText { binding.timeSummary.text = "" }

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

            val insertTime = measurePerformance { dataSource.insertAll(urlList) }
            postLog("📦 批量插入$TEST_DATA_SIZE 条: ${insertTime}ms")
            totalTimes[ChartMetric.INSERT] = totalTimes[ChartMetric.INSERT]!! + insertTime
            delay(400)

            val queryAllTime = measurePerformance { dataSource.getUrlListOnce() }
            postLog("🔍 查询所有: ${queryAllTime}ms")
            totalTimes[ChartMetric.QUERY_ALL] = totalTimes[ChartMetric.QUERY_ALL]!! + queryAllTime
            delay(400)

            val existsUrlToFind = urlList.firstOrNull { it.type == "URL" }?.url ?: "https://nonexistent.com/path/0"
            val existsUrlMatchTime = measurePerformance { dataSource.existsUrlMatch(existsUrlToFind) }
            postLog("🟢 精准URL前缀查找 (找到): ${existsUrlMatchTime}ms")
            totalTimes[ChartMetric.EXISTS_URL_MATCH] = totalTimes[ChartMetric.EXISTS_URL_MATCH]!! + existsUrlMatchTime

            val existsKeywordToFind = urlList.firstOrNull { it.type == "KeyWord" }?.url ?: "nonexistent-keyword"
            val queryLikeTime = measurePerformance { dataSource.existsKeywordMatch("text containing $existsKeywordToFind") }
            postLog("🟡 任意包含 (关键词查找 - 找到): ${queryLikeTime}ms")
            totalTimes[ChartMetric.EXISTS_KEYWORD_MATCH] = totalTimes[ChartMetric.EXISTS_KEYWORD_MATCH]!! + queryLikeTime

            val notFoundUrl = "https://nonexistent.com/path/999999"
            val notFoundUrlPrefixTime = measurePerformance { dataSource.existsUrlMatch(notFoundUrl) }
            postLog("🔷 URL前缀查找 (未找到): ${notFoundUrlPrefixTime}ms")
            totalTimes[ChartMetric.NOT_FOUND_URL_PREFIX] = totalTimes[ChartMetric.NOT_FOUND_URL_PREFIX]!! + notFoundUrlPrefixTime

            val existsDomainToFind = urlList.firstOrNull { it.type == "Domain" }?.url ?: "nonexistent-domain.com"
            val existsDomainTime = measurePerformance { dataSource.existsDomainMatch("http://$existsDomainToFind/some/path") }
            postLog("🔶 Domain包含查找 (找到): ${existsDomainTime}ms")
            totalTimes[ChartMetric.EXISTS_DOMAIN] = totalTimes[ChartMetric.EXISTS_DOMAIN]!! + existsDomainTime

            val notFoundKeyword = "absolutely-nonexistent-keyword"
            val notFoundKeywordTime = measurePerformance { dataSource.existsKeywordMatch("some text without $notFoundKeyword") }
            postLog("🔸 Keyword查找 (未找到): ${notFoundKeywordTime}ms")
            totalTimes[ChartMetric.NOT_FOUND_KEYWORD] = totalTimes[ChartMetric.NOT_FOUND_KEYWORD]!! + notFoundKeywordTime

            val deleteTime = measurePerformance { dataSource.deleteAll() }
            postLog("❌ 删除全部: ${deleteTime}ms")
            totalTimes[ChartMetric.DELETE] = totalTimes[ChartMetric.DELETE]!! + deleteTime

            chartLabels.add("运行 $testRunId")
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
        val dataSets = mutableListOf<ILineDataSet>()
        ChartMetric.values().forEach { metric ->
            val entries = chartDataEntries[metric]
            if (!entries.isNullOrEmpty()) {
                val dataSet = LineDataSet(entries, metric.label).apply {
                    setDrawCircles(true)
                    setDrawValues(true)
                    circleRadius = 5f
                    valueTextSize = 11f
                    color = metric.color
                    lineWidth = 2.5f
                    setDrawCircleHole(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                dataSets.add(dataSet)
            }
        }
        binding.memoryChart.data = LineData(dataSets)
        binding.memoryChart.xAxis.valueFormatter = IndexAxisValueFormatter(chartLabels)
        binding.memoryChart.notifyDataSetChanged()
        binding.memoryChart.invalidate()
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
}
