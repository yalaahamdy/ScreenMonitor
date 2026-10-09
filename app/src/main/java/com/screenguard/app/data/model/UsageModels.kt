package com.screenguard.app.data.model

import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import com.screenguard.app.R
import com.screenguard.app.Str
import java.util.Calendar

/**
 * Time period types available for analytics filtering.
 * Localized titles resolved through [Str] at display time.
 */
enum class PeriodType(@StringRes val titleRes: Int) {
    TODAY(R.string.period_today),
    YESTERDAY(R.string.period_yesterday),
    WEEK(R.string.period_week),
    MONTH(R.string.period_month),
    CUSTOM(R.string.period_custom)
}

/**
 * Represents a usage period with computed start/end range
 * plus the previous matching period for comparisons.
 */
data class UsagePeriod(
    val type: PeriodType,
    val customStartMillis: Long = 0L,
    val customEndMillis: Long = 0L
) {
    fun getTimeRange(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()

        return when (type) {
            PeriodType.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Pair(calendar.timeInMillis, now)
            }
            PeriodType.YESTERDAY -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis

                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                val end = calendar.timeInMillis
                Pair(start, end)
            }
            PeriodType.WEEK -> {
                calendar.add(Calendar.DAY_OF_YEAR, -6)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Pair(calendar.timeInMillis, now)
            }
            PeriodType.MONTH -> {
                calendar.add(Calendar.DAY_OF_YEAR, -29)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Pair(calendar.timeInMillis, now)
            }
            PeriodType.CUSTOM -> {
                val start = if (customStartMillis > 0) customStartMillis else now - (24 * 3600 * 1000)
                val end = if (customEndMillis > 0) customEndMillis else now
                Pair(start, end)
            }
        }
    }

    /** Compute the previous equivalent period for comparison. */
    fun getPreviousPeriodRange(): Pair<Long, Long> {
        val (currentStart, currentEnd) = getTimeRange()
        val duration = currentEnd - currentStart
        val prevEnd = currentStart
        val prevStart = prevEnd - duration
        return Pair(prevStart, prevEnd)
    }
}

/** Per-app usage statistics entry. */
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val totalTimeForegroundMs: Long,
    val launchCount: Int = 0,
    val lastTimeUsed: Long = 0L,
    val percentageOfTotal: Float = 0f
) {
    val formattedDuration: String
        get() = formatDuration(totalTimeForegroundMs)

    companion object {
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return when {
                hours > 0 && minutes > 0 -> Str.get(R.string.duration_hm, hours, minutes)
                hours > 0 -> Str.get(R.string.duration_h, hours)
                minutes > 0 -> Str.get(R.string.duration_m, minutes)
                seconds > 0 -> Str.get(R.string.duration_s, seconds)
                durationMs > 0 -> Str.get(R.string.duration_less_1s)
                else -> Str.get(R.string.duration_zero)
            }
        }

        fun formatLaunchCount(count: Int): String {
            return when {
                count == 0 -> Str.get(R.string.launches_none)
                count == 1 -> Str.get(R.string.launches_once)
                count == 2 -> Str.get(R.string.launches_twice)
                else -> Str.get(R.string.launches_times, count)
            }
        }
    }
}

/** Usage distribution within a specific hour of the 24-hour day. */
data class HourlyUsage(
    val hour: Int, // 0..23
    val durationMs: Long = 0L,
    val launchCount: Int = 0
) {
    val hourLabel: String
        get() = when {
            hour == 0 -> Str.get(R.string.hour_12am)
            hour < 12 -> Str.get(R.string.hour_am, hour)
            hour == 12 -> Str.get(R.string.hour_12pm)
            else -> Str.get(R.string.hour_pm, hour - 12)
        }
}

/** Usage comparison against the previous equivalent period. */
data class PeriodComparison(
    val currentPeriodMs: Long,
    val previousPeriodMs: Long
) {
    val diffMs: Long = currentPeriodMs - previousPeriodMs
    val isIncrease: Boolean = diffMs > 0
    val percentageChange: Float = if (previousPeriodMs > 0) {
        ((diffMs.toDouble() / previousPeriodMs.toDouble()) * 100).toFloat()
    } else if (currentPeriodMs > 0) {
        100f
    } else {
        0f
    }

    val formattedChangeText: String
        get() {
            val absChange = kotlin.math.abs(percentageChange).toInt()
            val formattedDiff = AppUsageInfo.formatDuration(kotlin.math.abs(diffMs))
            return when {
                diffMs == 0L -> Str.get(R.string.comparison_same)
                isIncrease -> Str.get(R.string.comparison_up, absChange, formattedDiff)
                else -> Str.get(R.string.comparison_down, absChange, formattedDiff)
            }
        }
}

/** Complete device usage summary for a given period. */
data class DashboardData(
    val period: UsagePeriod,
    val totalScreenTimeMs: Long,
    val appList: List<AppUsageInfo>,
    val hourlyDistribution: List<HourlyUsage>,
    val comparison: PeriodComparison?,
    val totalAppLaunches: Int
)
