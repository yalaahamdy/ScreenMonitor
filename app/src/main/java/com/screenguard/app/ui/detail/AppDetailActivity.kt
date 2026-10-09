package com.screenguard.app.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenguard.app.BaseActivity
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.AppUsageInfo
import com.screenguard.app.data.model.PeriodType
import com.screenguard.app.data.model.UsagePeriod
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.DataUsageRepository
import com.screenguard.app.data.repository.UsageStatsRepository
import com.screenguard.app.ui.charts.HourlyUsageBarChartView
import com.screenguard.app.ui.permission.UsageAccessHelper
import com.screenguard.app.ui.restrictions.AddEditRestrictionActivity
import com.screenguard.app.ui.tabs.UsageAppsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app detail: header, key metrics, hourly chart, per-period summary
 * and direct access to restriction editing. Ported from AppDetailScreen.
 */
class AppDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private var packageName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appInfo = AppInfoManager.getInstance(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.detailAppName).text = appInfo.getAppName(packageName)
        findViewById<TextView>(R.id.detailAppPackage).text = packageName
        val icon = appInfo.getAppIcon(packageName)
        if (icon != null) findViewById<ImageView>(R.id.detailAppIcon).setImageDrawable(icon)

        findViewById<View>(R.id.btnOpenApp).setOnClickListener {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            } catch (e: Exception) {}
        }
        findViewById<View>(R.id.btnRestrictThisApp).setOnClickListener {
            val intent = Intent(this, AddEditRestrictionActivity::class.java)
            startActivity(intent)
        }

        loadDetail(PeriodType.TODAY)

        val periodChips = mapOf(
            R.id.detailChipToday to PeriodType.TODAY,
            R.id.detailChipWeek to PeriodType.WEEK,
            R.id.detailChipMonth to PeriodType.MONTH
        )
        periodChips.forEach { (id, type) ->
            findViewById<View>(id).setOnClickListener {
                syncPeriodChips(periodChips, type)
                loadDetail(type)
            }
        }
        syncPeriodChips(periodChips, PeriodType.TODAY)
    }

    private fun syncPeriodChips(map: Map<Int, PeriodType>, selected: PeriodType) {
        map.forEach { (id, type) ->
            val chip = findViewById<TextView>(id)
            val isSelected = type == selected
            chip.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(getColor(if (isSelected) R.color.on_gold else R.color.text_secondary))
        }
    }

    private fun loadDetail(periodType: PeriodType) {
        lifecycleScope.launch {
            // v3.1: الاستعلام بدون صلاحية "الوصول للاستخدام" كان يرمي
            // SecurityException ويسبب انهيار التطبيق فورًا — نتحقق أولاً ونعالج
            // الاستثناءات بأي حال بدل الانهيار.
            if (!UsageAccessHelper.hasUsageAccess(this@AppDetailActivity)) {
                renderPermissionMissing()
                return@launch
            }

            val usageRepo = UsageStatsRepository(this@AppDetailActivity, AppInfoManager.getInstance(this@AppDetailActivity))
            val period = UsagePeriod(periodType)

            val detail = try {
                withContext(Dispatchers.IO) {
                    usageRepo.getAppDetailData(packageName, period)
                }
            } catch (e: Exception) {
                renderPermissionMissing()
                return@launch
            }
            val dataUsage = withContext(Dispatchers.IO) {
                try {
                    DataUsageRepository(this@AppDetailActivity, AppInfoManager.getInstance(this@AppDetailActivity)).getAppDataUsage(packageName, period)
                } catch (e: Exception) {
                    null
                }
            }

            findViewById<TextView>(R.id.detailTotalTime).text =
                AppUsageInfo.formatDuration(detail.appInfo.totalTimeForegroundMs)
            findViewById<TextView>(R.id.detailLaunches).text =
                AppUsageInfo.formatLaunchCount(detail.appInfo.launchCount)
            findViewById<TextView>(R.id.detailLastUsed).text =
                if (detail.appInfo.lastTimeUsed > 0) {
                    java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(detail.appInfo.lastTimeUsed))
                } else getString(R.string.never)

            findViewById<HourlyUsageBarChartView>(R.id.detailHourlyChart)
                .setData(detail.hourlyDistribution)

            findViewById<TextView>(R.id.detailDataUsage).text =
                if (dataUsage != null && dataUsage.totalBytes > 0) dataUsage.formattedTotal
                else getString(R.string.duration_zero)
            findViewById<TextView>(R.id.detailDataWifi).text =
                dataUsage?.formattedWifi ?: getString(R.string.duration_zero)
            findViewById<TextView>(R.id.detailDataMobile).text =
                dataUsage?.formattedMobile ?: getString(R.string.duration_zero)
        }
    }

    /** Shown instead of crashing when usage access is missing or queries fail. */
    private fun renderPermissionMissing() {
        try {
            findViewById<TextView>(R.id.detailTotalTime).text = Str.get(R.string.duration_zero)
            findViewById<TextView>(R.id.detailLaunches).text = ""
            findViewById<TextView>(R.id.detailLastUsed).text = getString(R.string.never)
            findViewById<TextView>(R.id.detailDataUsage).text = getString(R.string.duration_zero)
            findViewById<TextView>(R.id.detailDataWifi).text = getString(R.string.duration_zero)
            findViewById<TextView>(R.id.detailDataMobile).text = getString(R.string.duration_zero)
            findViewById<HourlyUsageBarChartView>(R.id.detailHourlyChart).setData(emptyList())

            val empty = findViewById<LinearLayout>(R.id.detailEmptyState)
            empty.visibility = View.VISIBLE
            empty.setOnClickListener {
                UsageAccessHelper.openUsageAccessSettings(this)
            }
        } catch (e: Exception) {
            // لا ننهار أبدًا في مسار المعالجة نفسه
        }
    }
}
