package com.screenguard.app.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.screenguard.app.data.model.AppUsageInfo
import com.screenguard.app.data.model.DashboardData
import com.screenguard.app.data.model.HourlyUsage
import com.screenguard.app.data.model.PeriodComparison
import com.screenguard.app.data.model.PeriodType
import com.screenguard.app.data.model.UsagePeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * مستودع جلب وتحليل إحصائيات وأحداث استخدام التطبيقات
 */
class UsageStatsRepository(
    private val context: Context,
    private val appInfoManager: AppInfoManager
) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * التحقق من صلاحية الوصول إلى إحصائيات الاستخدام
     */
    fun hasUsagePermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * جلب بيانات لوحة التحكم الشاملة لفترة محددة.
     * جسم الدالة بالكامل محمي — أي استثناء غير متوقع (حتى مع منح الصلاحية،
     * بعض أجهزة OEM ترمي استثناءات) يُرجع بيانات فارغة بدل انهيار التطبيق.
     */
    suspend fun getDashboardData(period: UsagePeriod): DashboardData = withContext(Dispatchers.IO) {
        try {
            getDashboardDataInternal(period)
        } catch (e: Exception) {
            DashboardData(
                period = period,
                totalScreenTimeMs = 0L,
                appList = emptyList(),
                hourlyDistribution = (0..23).map { HourlyUsage(it) },
                comparison = null,
                totalAppLaunches = 0
            )
        }
    }

    private suspend fun getDashboardDataInternal(period: UsagePeriod): DashboardData {
        if (!hasUsagePermission() || usageStatsManager == null) {
            return DashboardData(
                period = period,
                totalScreenTimeMs = 0L,
                appList = emptyList(),
                hourlyDistribution = (0..23).map { HourlyUsage(it) },
                comparison = null,
                totalAppLaunches = 0
            )
        }

        val (startTime, endTime) = period.getTimeRange()
        val (prevStart, prevEnd) = period.getPreviousPeriodRange()

        // تحليل الأحداث للفترة الحالية
        val currentAnalysis = analyzeUsageEvents(startTime, endTime)
        // تحليل الوقت للفترة السابقة للمقارنة
        val prevTotalTime = try {
            calculateTotalForegroundTime(prevStart, prevEnd)
        } catch (e: Exception) { 0L }

        val totalScreenTime = currentAnalysis.appTimes.values.sum()
        val totalLaunches = currentAnalysis.appLaunches.values.sum()

        // تجميع وتجهيز قائمة التطبيقات
        val appList = currentAnalysis.appTimes.mapNotNull { (pkg, durationMs) ->
            if (durationMs < 1000L) return@mapNotNull null // تجاهل الاستخدامات التي تقل عن ثانية
            if (!appInfoManager.isInteractiveApp(pkg)) return@mapNotNull null

            val launchCount = currentAnalysis.appLaunches[pkg] ?: 0
            val lastUsed = currentAnalysis.lastUsedTimestamps[pkg] ?: 0L
            val percentage = if (totalScreenTime > 0) {
                ((durationMs.toDouble() / totalScreenTime.toDouble()) * 100).toFloat()
            } else 0f

            AppUsageInfo(
                packageName = pkg,
                appName = appInfoManager.getAppName(pkg),
                icon = appInfoManager.getAppIcon(pkg),
                totalTimeForegroundMs = durationMs,
                launchCount = launchCount,
                lastTimeUsed = lastUsed,
                percentageOfTotal = percentage
            )
        }.sortedByDescending { it.totalTimeForegroundMs }

        // إحصائيات المقارنة مع الفترة السابقة
        val comparison = PeriodComparison(
            currentPeriodMs = totalScreenTime,
            previousPeriodMs = prevTotalTime
        )

        // توزيع الاستخدام الساعي على مدار الـ 24 ساعة
        val hourlyList = (0..23).map { hour ->
            HourlyUsage(
                hour = hour,
                durationMs = currentAnalysis.hourlyUsageMs[hour] ?: 0L,
                launchCount = currentAnalysis.hourlyLaunches[hour] ?: 0
            )
        }

        return DashboardData(
            period = period,
            totalScreenTimeMs = totalScreenTime,
            appList = appList,
            hourlyDistribution = hourlyList,
            comparison = comparison,
            totalAppLaunches = totalLaunches
        )
    }

    /**
     * جلب تفاصيل دقيقة لتطبيق معين.
     * تتحقق أولاً من صلاحية الوصول للاستخدام — الاستعلام بدونها يرمي
     * SecurityException ويسبب انهيارًا فوريًا (علة v3.0 الحاسمة).
     */
    suspend fun getAppDetailData(
        packageName: String,
        period: UsagePeriod
    ): AppDetailResult = withContext(Dispatchers.IO) {
        try {
            getAppDetailDataInternal(packageName, period)
        } catch (e: Exception) {
            // صلاحية مفقودة أو استثناء من النظام — إرجاع نتيجة فارغة آمنة
            AppDetailResult(
                appInfo = AppUsageInfo(
                    packageName = packageName,
                    appName = appInfoManager.getAppName(packageName),
                    icon = appInfoManager.getAppIcon(packageName),
                    totalTimeForegroundMs = 0L,
                    launchCount = 0,
                    lastTimeUsed = 0L,
                    percentageOfTotal = 0f
                ),
                hourlyDistribution = (0..23).map { HourlyUsage(it) },
                periodsSummary = emptyMap()
            )
        }
    }

    private suspend fun getAppDetailDataInternal(
        packageName: String,
        period: UsagePeriod
    ): AppDetailResult {
        if (!hasUsagePermission() || usageStatsManager == null) {
            return AppDetailResult(
                appInfo = AppUsageInfo(
                    packageName = packageName,
                    appName = appInfoManager.getAppName(packageName),
                    icon = appInfoManager.getAppIcon(packageName),
                    totalTimeForegroundMs = 0L,
                    launchCount = 0,
                    lastTimeUsed = 0L,
                    percentageOfTotal = 0f
                ),
                hourlyDistribution = (0..23).map { HourlyUsage(it) },
                periodsSummary = emptyMap()
            )
        }

        val (startTime, endTime) = period.getTimeRange()
        val analysis = analyzeUsageEvents(startTime, endTime, targetPackage = packageName)

        val appDuration = analysis.appTimes[packageName] ?: 0L
        val appLaunches = analysis.appLaunches[packageName] ?: 0
        val lastUsed = analysis.lastUsedTimestamps[packageName] ?: 0L

        // إجمالي وقت الهاتف للفترة لحساب النسبة
        val totalPhoneTime = analyzeUsageEvents(startTime, endTime).appTimes.values.sum()
        val percentage = if (totalPhoneTime > 0) {
            ((appDuration.toDouble() / totalPhoneTime.toDouble()) * 100).toFloat()
        } else 0f

        val appInfo = AppUsageInfo(
            packageName = packageName,
            appName = appInfoManager.getAppName(packageName),
            icon = appInfoManager.getAppIcon(packageName),
            totalTimeForegroundMs = appDuration,
            launchCount = appLaunches,
            lastTimeUsed = lastUsed,
            percentageOfTotal = percentage
        )

        val hourlyList = (0..23).map { hour ->
            HourlyUsage(
                hour = hour,
                durationMs = analysis.hourlyUsageMs[hour] ?: 0L,
                launchCount = analysis.hourlyLaunches[hour] ?: 0
            )
        }

        // جلب ملخص استخدام هذا التطبيق عبر الفترات الزمنية الأخرى
        val periodsSummary = mapOf(
            PeriodType.TODAY to getSingleAppTime(packageName, UsagePeriod(PeriodType.TODAY)),
            PeriodType.YESTERDAY to getSingleAppTime(packageName, UsagePeriod(PeriodType.YESTERDAY)),
            PeriodType.WEEK to getSingleAppTime(packageName, UsagePeriod(PeriodType.WEEK)),
            PeriodType.MONTH to getSingleAppTime(packageName, UsagePeriod(PeriodType.MONTH))
        )

        return AppDetailResult(
            appInfo = appInfo,
            hourlyDistribution = hourlyList,
            periodsSummary = periodsSummary
        )
    }

    /**
     * استرجاع مدة استخدام تطبيق معين في فترة محددة
     */
    private suspend fun getSingleAppTime(packageName: String, period: UsagePeriod): Pair<Long, Int> {
        val (start, end) = period.getTimeRange()
        val analysis = analyzeUsageEvents(start, end, targetPackage = packageName)
        val duration = analysis.appTimes[packageName] ?: 0L
        val launches = analysis.appLaunches[packageName] ?: 0
        return Pair(duration, launches)
    }

    /**
     * تحليل تفصيلي لأحداث UsageEvents لحساب الوقت والفتحات والساعات بدقة متناهية
     */
    private fun analyzeUsageEvents(
        startTime: Long,
        endTime: Long,
        targetPackage: String? = null
    ): UsageAnalysisResult {
        val appTimes = mutableMapOf<String, Long>()
        val appLaunches = mutableMapOf<String, Int>()
        val lastUsedTimestamps = mutableMapOf<String, Long>()
        val hourlyUsageMs = mutableMapOf<Int, Long>()
        val hourlyLaunches = mutableMapOf<Int, Int>()

        if (usageStatsManager == null) {
            return UsageAnalysisResult(appTimes, appLaunches, lastUsedTimestamps, hourlyUsageMs, hourlyLaunches)
        }

        // الاستعلام نفسه محمي — بعض الأجهزة ترمي SecurityException أو IllegalArgumentException
        // (مثلاً نطاق زمني غير صالح) حتى بعد منح الصلاحية
        val events = try {
            usageStatsManager.queryEvents(startTime, endTime)
        } catch (e: Exception) {
            return UsageAnalysisResult(appTimes, appLaunches, lastUsedTimestamps, hourlyUsageMs, hourlyLaunches)
        }
        val event = UsageEvents.Event()

        // تعقب الجلسات النشطة: packageName -> وقت بدء الاستخدام (Resume Timestamp)
        var currentForegroundPkg: String? = null
        var currentForegroundStart: Long = 0L

        val calendar = Calendar.getInstance()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            if (targetPackage != null && pkg != targetPackage) {
                // إذا كنا نحلل تطبيقًا واحدًا فقط ولكن تغيرت الواجهة، ننهي جلسة التطبيق المستهدف
                if (currentForegroundPkg == targetPackage && isMoveToBackgroundEvent(event.eventType)) {
                    val duration = (time - currentForegroundStart).coerceAtLeast(0L)
                    recordSession(
                        targetPackage,
                        currentForegroundStart,
                        time,
                        duration,
                        appTimes,
                        hourlyUsageMs,
                        calendar
                    )
                    currentForegroundPkg = null
                }
                continue
            }

            when {
                isMoveToForegroundEvent(event.eventType) -> {
                    // إذا كان هناك تطبيق سابق لا يزال مفتوحًا، يتم إغلاق جلسته
                    if (currentForegroundPkg != null && currentForegroundStart > 0) {
                        val duration = (time - currentForegroundStart).coerceAtLeast(0L)
                        recordSession(
                            currentForegroundPkg,
                            currentForegroundStart,
                            time,
                            duration,
                            appTimes,
                            hourlyUsageMs,
                            calendar
                        )
                    }

                    currentForegroundPkg = pkg
                    currentForegroundStart = time
                    lastUsedTimestamps[pkg] = time

                    // تسجيل عملية فتح جديدة للتطبيق
                    appLaunches[pkg] = (appLaunches[pkg] ?: 0) + 1

                    // تسجيل الفتح في الساعة المحددة
                    calendar.timeInMillis = time
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)
                    hourlyLaunches[hour] = (hourlyLaunches[hour] ?: 0) + 1
                }

                isMoveToBackgroundEvent(event.eventType) -> {
                    if (currentForegroundPkg == pkg && currentForegroundStart > 0) {
                        val duration = (time - currentForegroundStart).coerceAtLeast(0L)
                        recordSession(
                            pkg,
                            currentForegroundStart,
                            time,
                            duration,
                            appTimes,
                            hourlyUsageMs,
                            calendar
                        )
                        lastUsedTimestamps[pkg] = time
                        currentForegroundPkg = null
                        currentForegroundStart = 0L
                    }
                }
            }
        }

        // معالجة التطبيق الذي قد يزال مفتوحًا في اللحظة الحالية (عند نهاية النطاق)
        if (currentForegroundPkg != null && currentForegroundStart > 0 && currentForegroundStart < endTime) {
            val sessionEnd = endTime.coerceAtMost(System.currentTimeMillis())
            val duration = (sessionEnd - currentForegroundStart).coerceAtLeast(0L)
            recordSession(
                currentForegroundPkg,
                currentForegroundStart,
                sessionEnd,
                duration,
                appTimes,
                hourlyUsageMs,
                calendar
            )
        }

        // كإجراء أمان مكمل، إذا كانت بيانات queryEvents خالية (مثل بعض إصدارات أندرويد المعدلة)، ندمج نتائج queryUsageStats
        if (appTimes.isEmpty() && targetPackage == null) {
            fallbackToAggregatedStats(startTime, endTime, appTimes, lastUsedTimestamps)
        }

        return UsageAnalysisResult(appTimes, appLaunches, lastUsedTimestamps, hourlyUsageMs, hourlyLaunches)
    }

    private fun isMoveToForegroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == 29) // MOVE_TO_FOREGROUND
    }

    private fun isMoveToBackgroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == 30) // MOVE_TO_BACKGROUND
    }

    private fun recordSession(
        pkg: String,
        sessionStart: Long,
        sessionEnd: Long,
        duration: Long,
        appTimes: MutableMap<String, Long>,
        hourlyUsageMs: MutableMap<Int, Long>,
        calendar: Calendar
    ) {
        if (duration <= 0) return

        // إضافة للوقت الكلي للتطبيق
        appTimes[pkg] = (appTimes[pkg] ?: 0L) + duration

        // توزيع الجلسة على الساعات المناسبة
        calendar.timeInMillis = sessionStart
        val startHour = calendar.get(Calendar.HOUR_OF_DAY)
        calendar.timeInMillis = sessionEnd
        val endHour = calendar.get(Calendar.HOUR_OF_DAY)

        if (startHour == endHour) {
            hourlyUsageMs[startHour] = (hourlyUsageMs[startHour] ?: 0L) + duration
        } else {
            // توزيع تقريبي في حال امتدت الجلسة عبر أكثر من ساعة
            val hoursSpan = (endHour - startHour + 24) % 24 + 1
            val slice = duration / hoursSpan
            for (h in 0 until hoursSpan) {
                val hour = (startHour + h) % 24
                hourlyUsageMs[hour] = (hourlyUsageMs[hour] ?: 0L) + slice
            }
        }
    }

    /**
     * خطة بديلة (Fallback) باستخدام queryUsageStats لضمان عدم ظهور شاشة فارغة أبداً
     */
    private fun fallbackToAggregatedStats(
        startTime: Long,
        endTime: Long,
        appTimes: MutableMap<String, Long>,
        lastUsedTimestamps: MutableMap<String, Long>
    ) {
        val statsList: List<UsageStats>? = try {
            usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
        } catch (e: Exception) {
            null
        }

        statsList?.forEach { stat ->
            val pkg = stat.packageName ?: return@forEach
            val timeInForeground = stat.totalTimeInForeground
            if (timeInForeground > 0) {
                appTimes[pkg] = (appTimes[pkg] ?: 0L) + timeInForeground
                lastUsedTimestamps[pkg] = stat.lastTimeUsed
            }
        }
    }

    /**
     * حساب إجمالي الوقت المستغرق للشاشة في فترة معينة
     */
    private fun calculateTotalForegroundTime(startTime: Long, endTime: Long): Long {
        if (usageStatsManager == null) return 0L
        return analyzeUsageEvents(startTime, endTime).appTimes.values.sum()
    }
}

/**
 * كائن تخزين نتيجة التحليل الوسيط
 */
private data class UsageAnalysisResult(
    val appTimes: Map<String, Long>,
    val appLaunches: Map<String, Int>,
    val lastUsedTimestamps: Map<String, Long>,
    val hourlyUsageMs: Map<Int, Long>,
    val hourlyLaunches: Map<Int, Int>
)

/**
 * كائن نتائج صفحة تفاصيل التطبيق
 */
data class AppDetailResult(
    val appInfo: AppUsageInfo,
    val hourlyDistribution: List<HourlyUsage>,
    val periodsSummary: Map<PeriodType, Pair<Long, Int>>
)
