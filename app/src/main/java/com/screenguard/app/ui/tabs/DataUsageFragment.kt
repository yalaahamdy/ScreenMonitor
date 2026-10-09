package com.screenguard.app.ui.tabs

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
import com.screenguard.app.CrashLogger
import com.screenguard.app.R
import com.screenguard.app.UiSafety
import com.screenguard.app.data.model.DataUsageInfo
import com.screenguard.app.data.model.DataUsageOverview
import com.screenguard.app.data.model.NetworkTypeFilter
import com.screenguard.app.data.model.PeriodType
import com.screenguard.app.data.model.UsagePeriod
import com.screenguard.app.data.model.formatBytes
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.DataUsageRepository
import com.screenguard.app.ui.permission.UsageAccessHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data usage tab: device-wide WiFi vs mobile consumption with a ranked
 * per-app list, filterable by network type and period.
 */
class DataUsageFragment : Fragment() {

    private var currentType = PeriodType.TODAY
    private var networkFilter = NetworkTypeFilter.ALL
    private var overview: DataUsageOverview? = null
    private lateinit var adapter: DataAppsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        inflater.inflate(R.layout.fragment_data_usage, container, false)
    } catch (t: Throwable) {
        // v3.1.2: never return null — a built error card instead of a blank tab
        CrashLogger.log(t, "DataUsageFragment.inflate")
        UiSafety.errorCard(requireContext(), "DataUsageFragment.inflate", t)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiSafety.guard("DataUsageFragment.onViewCreated") { onViewCreatedSafe(view) }
    }

    private fun onViewCreatedSafe(view: View) {
        if (UiSafety.isErrorCard(view)) return
        adapter = DataAppsAdapter()
        view.findViewById<RecyclerView>(R.id.dataAppList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DataUsageFragment.adapter
            isNestedScrollingEnabled = false
        }

        val periods = mapOf(
            R.id.dataChipToday to PeriodType.TODAY,
            R.id.dataChipYesterday to PeriodType.YESTERDAY,
            R.id.dataChipWeek to PeriodType.WEEK,
            R.id.dataChipMonth to PeriodType.MONTH
        )
        periods.forEach { (id, type) ->
            view.findViewById<View>(id).setOnClickListener {
                currentType = type
                highlight(view.findViewById(R.id.dataPeriodChips), periods, currentType)
                load()
            }
        }
        highlight(view.findViewById(R.id.dataPeriodChips), periods, currentType)

        val filters = mapOf(
            R.id.filterAll to NetworkTypeFilter.ALL,
            R.id.filterMobile to NetworkTypeFilter.MOBILE,
            R.id.filterWifi to NetworkTypeFilter.WIFI
        )
        filters.forEach { (id, filter) ->
            view.findViewById<View>(id).setOnClickListener {
                networkFilter = filter
                highlight(view.findViewById<View>(R.id.filterAll).parent as ViewGroup, filters, networkFilter)
                overview?.let { render(it) }
            }
        }
        highlight(view.findViewById<View>(R.id.filterAll).parent as ViewGroup, filters, networkFilter)

        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefreshData)
            .setOnRefreshListener { load() }
    }

    private fun highlight(parent: ViewGroup, map: Map<Int, Any>, selected: Any) {
        for ((id, value) in map) {
            val chip = parent.findViewById<TextView>(id) ?: continue
            val isSelected = value == selected
            chip.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(
                requireContext().getColor(if (isSelected) R.color.on_gold else R.color.text_secondary)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        UiSafety.guard("DataUsageFragment.onResume") { load() }
    }

    private fun load() {
        val view = view ?: return
        val swipe = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefreshData)
        swipe.isRefreshing = true

        lifecycleScope.launch {
            val period = UsagePeriod(currentType)
            // v3.1: الاستعلام محمي بالكامل — عدم منح الصلاحية أو أي استثناء من
            // NetworkStatsManager يعرض الحالة الفارغة بدل انهيار التطبيق
            val data = try {
                withContext(Dispatchers.IO) {
                    if (UsageAccessHelper.hasUsageAccess(requireContext())) {
                        DataUsageRepository(requireContext(), AppInfoManager.getInstance(requireContext())).getDataUsageOverview(period)
                    } else null
                }
            } catch (e: Exception) {
                null
            }
            swipe.isRefreshing = false
            if (data != null) {
                overview = data
                UiSafety.guard("DataUsageFragment.render") { render(data) }
            } else {
                view.findViewById<LinearLayout>(R.id.dataEmptyState).visibility = View.VISIBLE
                view.findViewById<RecyclerView>(R.id.dataAppList).visibility = View.GONE
            }
        }
    }

    private fun render(data: DataUsageOverview) {
        val view = view ?: return

        view.findViewById<TextView>(R.id.tvDataTotal).text = formatBytes(data.totalBytes)
        view.findViewById<TextView>(R.id.tvMobileTotal).text = formatBytes(data.totalMobileBytes)
        view.findViewById<TextView>(R.id.tvWifiTotal).text = formatBytes(data.totalWifiBytes)

        view.findViewById<ProgressBar>(R.id.progressMobile).progress =
            (data.mobileRatio * 100).toInt().coerceIn(0, 100)
        view.findViewById<ProgressBar>(R.id.progressWifi).progress =
            (data.wifiRatio * 100).toInt().coerceIn(0, 100)

        val filtered = data.getFilteredAppList(networkFilter)
        adapter.submit(filtered, networkFilter)

        view.findViewById<LinearLayout>(R.id.dataEmptyState).visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<RecyclerView>(R.id.dataAppList).visibility =
            if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }
}

/** Adapter for the ranked per-app data consumption list. */
class DataAppsAdapter : RecyclerView.Adapter<DataAppsAdapter.Holder>() {

    private val items = mutableListOf<DataUsageInfo>()
    private var filter = NetworkTypeFilter.ALL

    fun submit(list: List<DataUsageInfo>, networkFilter: NetworkTypeFilter) {
        items.clear()
        items.addAll(list)
        filter = networkFilter
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.dataAppIcon)
        val name: TextView = v.findViewById(R.id.dataAppName)
        val total: TextView = v.findViewById(R.id.dataAppTotal)
        val wifi: TextView = v.findViewById(R.id.dataAppWifi)
        val mobile: TextView = v.findViewById(R.id.dataAppMobile)
        val rx: TextView = v.findViewById(R.id.dataAppRx)
        val tx: TextView = v.findViewById(R.id.dataAppTx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_data_app, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.name.text = item.appName
        holder.total.text = item.getFormattedByFilter(filter)

        if (filter == NetworkTypeFilter.ALL || filter == NetworkTypeFilter.WIFI) {
            holder.wifi.text = ctx.getString(com.screenguard.app.R.string.data_wifi_label, item.formattedWifi)
            holder.wifi.visibility = View.VISIBLE
        } else {
            holder.wifi.visibility = View.GONE
        }

        if (filter == NetworkTypeFilter.ALL || filter == NetworkTypeFilter.MOBILE) {
            holder.mobile.text = ctx.getString(com.screenguard.app.R.string.data_mobile_label, item.formattedMobile)
            holder.mobile.visibility = View.VISIBLE
        } else {
            holder.mobile.visibility = View.GONE
        }

        holder.rx.text = ctx.getString(com.screenguard.app.R.string.data_rx_label, item.formattedRx)
        holder.tx.text = ctx.getString(com.screenguard.app.R.string.data_tx_label, item.formattedTx)

        val icon = try {
            ctx.packageManager.getApplicationIcon(item.packageName)
        } catch (e: Exception) {
            null
        }
        if (icon != null) holder.icon.setImageDrawable(icon)
        else holder.icon.setImageResource(com.screenguard.app.R.drawable.ic_home)
    }
}
