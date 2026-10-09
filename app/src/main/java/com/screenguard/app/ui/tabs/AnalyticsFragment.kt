package com.screenguard.app.ui.tabs

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.datepicker.MaterialDatePicker
import com.screenguard.app.CrashLogger
import com.screenguard.app.Prefs
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.UiSafety
import com.screenguard.app.data.model.AppUsageInfo
import com.screenguard.app.data.model.DashboardData
import com.screenguard.app.data.model.PeriodType
import com.screenguard.app.data.model.UsagePeriod
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.UsageStatsRepository
import com.screenguard.app.ui.detail.AppDetailActivity
import com.screenguard.app.ui.permission.UsageAccessHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Analytics tab: screen-time dashboard with period filters, hourly
 * distribution, time-of-day breakdown and per-app usage ranking.
 */
class AnalyticsFragment : Fragment() {

    enum class SortMode { DURATION, LAUNCHES, NAME }

    private var currentType = PeriodType.TODAY
    private var customStart = 0L
    private var customEnd = 0L
    private var sortMode = SortMode.DURATION
    private var dashboardData: DashboardData? = null
    private lateinit var adapter: UsageAppsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        inflater.inflate(R.layout.fragment_analytics, container, false)
    } catch (t: Throwable) {
        // v3.1.2: never return null — a built error card instead of a blank tab
        CrashLogger.log(t, "AnalyticsFragment.inflate")
        UiSafety.errorCard(requireContext(), "AnalyticsFragment.inflate", t)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiSafety.guard("AnalyticsFragment.onViewCreated") { onViewCreatedSafe(view, savedInstanceState) }
    }

    private fun onViewCreatedSafe(view: View, savedInstanceState: Bundle?) {
        if (UiSafety.isErrorCard(view)) return
        adapter = UsageAppsAdapter { info ->
            val intent = Intent(requireContext(), AppDetailActivity::class.java).apply {
                putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, info.packageName)
            }
            startActivity(intent)
        }
        view.findViewById<RecyclerView>(R.id.appUsageList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AnalyticsFragment.adapter
            isNestedScrollingEnabled = false
        }

        setupPeriodChips(view)

        view.findViewById<View>(R.id.btnSortMode).setOnClickListener {
            sortMode = when (sortMode) {
                SortMode.DURATION -> SortMode.LAUNCHES
                SortMode.LAUNCHES -> SortMode.NAME
                SortMode.NAME -> SortMode.DURATION
            }
            view.findViewById<TextView>(R.id.btnSortMode).text = getString(
                when (sortMode) {
                    SortMode.DURATION -> R.string.sort_by_duration
                    SortMode.LAUNCHES -> R.string.sort_by_launches
                    SortMode.NAME -> R.string.sort_by_name
                }
            )
            applySortAndRender()
        }

        view.findViewById<LinearLayout>(R.id.emptyState).setOnClickListener {
            if (!UsageAccessHelper.hasUsageAccess(requireContext())) {
                UsageAccessHelper.openUsageAccessSettings(requireContext())
            }
        }
        (view.findViewById<View>(R.id.swipeRefresh) as androidx.swiperefreshlayout.widget.SwipeRefreshLayout)
            .setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        UiSafety.guard("AnalyticsFragment.onResume") { onResumeSafe() }
    }

    private fun onResumeSafe() {
        val view = view ?: return
        if (!UsageAccessHelper.hasUsageAccess(requireContext())) {
            // Show the empty state with a grant prompt instead of nagging the user
            view.findViewById<LinearLayout>(R.id.emptyState).visibility = View.VISIBLE
            view.findViewById<RecyclerView>(R.id.appUsageList).visibility = View.GONE
            view.findViewById<TextView>(R.id.tvTotalTime).text = Str.get(R.string.duration_zero)
            view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefresh)
                .isRefreshing = false
        } else {
            load()
        }
    }

    private fun setupPeriodChips(view: View) {
        val chips = mapOf(
            R.id.chipToday to PeriodType.TODAY,
            R.id.chipYesterday to PeriodType.YESTERDAY,
            R.id.chipWeek to PeriodType.WEEK,
            R.id.chipMonth to PeriodType.MONTH,
            R.id.chipCustom to PeriodType.CUSTOM
        )
        chips.forEach { (id, type) ->
            view.findViewById<View>(id).setOnClickListener {
                if (type == PeriodType.CUSTOM) {
                    showCustomRangePicker()
                } else {
                    currentType = type
                    highlightChips(view)
                    load()
                }
            }
        }
        highlightChips(view)
    }

    private fun highlightChips(view: View) {
        val chips = mapOf(
            R.id.chipToday to PeriodType.TODAY,
            R.id.chipYesterday to PeriodType.YESTERDAY,
            R.id.chipWeek to PeriodType.WEEK,
            R.id.chipMonth to PeriodType.MONTH,
            R.id.chipCustom to PeriodType.CUSTOM
        )
        chips.forEach { (id, type) ->
            val chip = view.findViewById<TextView>(id)
            val selected = type == currentType
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(
                requireContext().getColor(if (selected) R.color.on_gold else R.color.text_secondary)
            )
        }
    }

    private fun showCustomRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.period_custom)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            customStart = selection.first ?: 0L
            customEnd = selection.second ?: 0L
            currentType = PeriodType.CUSTOM
            view?.let { highlightChips(it) }
            load()
        }
        picker.show(parentFragmentManager, "custom_range")
    }

    private fun load() {
        val view = view ?: return
        if (!UsageAccessHelper.hasUsageAccess(requireContext())) {
            view.findViewById<LinearLayout>(R.id.emptyState).visibility = View.VISIBLE
            return
        }

        val swipe = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefresh)
        swipe.isRefreshing = true

        lifecycleScope.launch(Dispatchers.Main) {
            val period = UsagePeriod(currentType, customStart, customEnd)
            // v3.1: أي استثناء غير متوقع يعرض الحالة الفارغة بدل انهيار التطبيق
            val data = try {
                withContext(Dispatchers.IO) {
                    UsageStatsRepository(requireContext(), AppInfoManager.getInstance(requireContext())).getDashboardData(period)
                }
            } catch (e: Exception) {
                null
            }
            swipe.isRefreshing = false
            if (data == null) {
                view.findViewById<LinearLayout>(R.id.emptyState).visibility = View.VISIBLE
                view.findViewById<RecyclerView>(R.id.appUsageList).visibility = View.GONE
            } else {
                dashboardData = data
                UiSafety.guard("AnalyticsFragment.render") { render(data) }
            }
        }
    }

    private fun applySortAndRender() {
        dashboardData?.let { render(it) }
    }

    private fun render(data: DashboardData) {
        val view = view ?: return
        val ctx = requireContext()

        view.findViewById<TextView>(R.id.tvTotalTime).text =
            AppUsageInfo.formatDuration(data.totalScreenTimeMs)

        val comparison = data.comparison
        if (comparison != null) {
            view.findViewById<TextView>(R.id.tvComparison).text = comparison.formattedChangeText
            view.findViewById<ImageView>(R.id.imgComparisonTrend).setRotation(
                if (comparison.isIncrease) 0f else 180f
            )
        } else {
            view.findViewById<TextView>(R.id.tvComparison).text = ""
        }

        view.findViewById<TextView>(R.id.tvLaunches).text =
            getString(R.string.analytics_launches_summary, AppUsageInfo.formatLaunchCount(data.totalAppLaunches))

        // Hourly chart
        view.findViewById<com.screenguard.app.ui.charts.HourlyUsageBarChartView>(R.id.hourlyChart)
            .setData(data.hourlyDistribution)

        val peak = data.hourlyDistribution.maxByOrNull { it.durationMs }
        view.findViewById<TextView>(R.id.tvPeakHour).text = if (peak != null && peak.durationMs > 0) {
            getString(R.string.analytics_peak_hour, peak.hourLabel, AppUsageInfo.formatDuration(peak.durationMs))
        } else ""

        // Time distribution
        val morning = data.hourlyDistribution.filter { it.hour in 5..11 }.sumOf { it.durationMs }
        val afternoon = data.hourlyDistribution.filter { it.hour in 12..17 }.sumOf { it.durationMs }
        val evening = data.hourlyDistribution.filter { it.hour in 18..22 }.sumOf { it.durationMs }
        val night = data.hourlyDistribution.filter { it.hour == 23 || it.hour in 0..4 }.sumOf { it.durationMs }
        val total = (morning + afternoon + evening + night).coerceAtLeast(1)

        view.findViewById<com.screenguard.app.ui.charts.TimeOfDayDistributionView>(R.id.timeDistribution)
            .setData(
                listOf(
                    com.screenguard.app.ui.charts.TimeOfDayDistributionView.Portion(
                        Str.get(R.string.time_morning), morning, morning.toFloat() / total
                    ),
                    com.screenguard.app.ui.charts.TimeOfDayDistributionView.Portion(
                        Str.get(R.string.time_afternoon), afternoon, afternoon.toFloat() / total
                    ),
                    com.screenguard.app.ui.charts.TimeOfDayDistributionView.Portion(
                        Str.get(R.string.time_evening), evening, evening.toFloat() / total
                    ),
                    com.screenguard.app.ui.charts.TimeOfDayDistributionView.Portion(
                        Str.get(R.string.time_night), night, night.toFloat() / total
                    )
                )
            )

        // App list
        val sorted = when (sortMode) {
            SortMode.DURATION -> data.appList.sortedByDescending { it.totalTimeForegroundMs }
            SortMode.LAUNCHES -> data.appList.sortedByDescending { it.launchCount }
            SortMode.NAME -> data.appList.sortedBy { it.appName.lowercase() }
        }
        adapter.submit(sorted, data.totalScreenTimeMs)

        view.findViewById<LinearLayout>(R.id.emptyState).visibility =
            if (data.appList.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<RecyclerView>(R.id.appUsageList).visibility =
            if (data.appList.isEmpty()) View.GONE else View.VISIBLE
    }
}

/** RecyclerView adapter for the per-app usage ranking list. */
class UsageAppsAdapter(
    private val onClick: (AppUsageInfo) -> Unit
) : RecyclerView.Adapter<UsageAppsAdapter.Holder>() {

    private val items = mutableListOf<AppUsageInfo>()
    private var totalMs: Long = 0L

    fun submit(list: List<AppUsageInfo>, total: Long) {
        items.clear()
        items.addAll(list)
        totalMs = total
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val duration: TextView = v.findViewById(R.id.appDuration)
        val launches: TextView = v.findViewById(R.id.appLaunches)
        val percentage: TextView = v.findViewById(R.id.appPercentage)
        val progress: ProgressBar = v.findViewById(R.id.appUsageProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_usage_app, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.name.text = item.appName
        holder.duration.text = item.formattedDuration
        holder.launches.text = AppUsageInfo.formatLaunchCount(item.launchCount)
        val pct = if (totalMs > 0) (item.totalTimeForegroundMs * 100f / totalMs) else 0f
        holder.percentage.text = String.format(java.util.Locale.getDefault(), "%.1f%%", pct)
        holder.progress.progress = pct.toInt().coerceIn(0, 100)

        val icon: Drawable? = try {
            ctx.packageManager.getApplicationIcon(item.packageName)
        } catch (e: Exception) {
            null
        }
        if (icon != null) holder.icon.setImageDrawable(icon)
        else holder.icon.setImageResource(R.drawable.ic_home)

        holder.itemView.setOnClickListener { onClick(item) }
    }
}
