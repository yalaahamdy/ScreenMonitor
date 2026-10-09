package com.screenguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.AppRestriction
import com.screenguard.app.data.model.BlockReason
import com.screenguard.app.data.model.GroupLimitType
import com.screenguard.app.data.model.LimitPeriod
import com.screenguard.app.data.model.RestrictionEvaluation
import com.screenguard.app.data.model.TimeWindow
import com.screenguard.app.data.model.isSettingsPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

/**
 * مستودع إدارة وتخزين وتقييم قيود استخدام التطبيقات
 */
class AppRestrictionsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _restrictionsFlow = MutableStateFlow<List<AppRestriction>>(emptyList())
    val restrictionsFlow: StateFlow<List<AppRestriction>> = _restrictionsFlow.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_RESTRICTIONS_JSON) {
            loadRestrictions()
        }
    }

    companion object {
        private const val PREFS_NAME = "screenguard_restrictions_prefs"
        private const val KEY_RESTRICTIONS_JSON = "restrictions_json"
        private const val PREFIX_BYPASS_UNTIL = "bypass_until_"
        private const val PREFIX_BYPASS_DURATION = "bypass_duration_"

        @Volatile
        private var instance: AppRestrictionsRepository? = null

        fun getInstance(context: Context): AppRestrictionsRepository {
            return instance ?: synchronized(this) {
                instance ?: AppRestrictionsRepository(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Format a bypass duration precisely (1 minute .. 5 hours), localized.
         */
        fun formatBypassDuration(minutes: Int): String {
            if (minutes <= 0) return Str.get(R.string.bypass_less_than_minute)
            val hours = minutes / 60
            val mins = minutes % 60
            val hoursStr = when (hours) {
                0 -> null
                1 -> Str.get(R.string.bypass_hour_one)
                2 -> Str.get(R.string.bypass_hour_two)
                else -> Str.get(R.string.bypass_hours, hours)
            }
            val minsStr = when (mins) {
                0 -> null
                1 -> Str.get(R.string.bypass_minute_one)
                2 -> Str.get(R.string.bypass_minute_two)
                else -> Str.get(R.string.bypass_minutes, mins)
            }
            return when {
                hoursStr != null && minsStr != null -> Str.get(R.string.bypass_combined, hoursStr, minsStr)
                hoursStr != null -> hoursStr
                minsStr != null -> minsStr
                else -> Str.get(R.string.bypass_minutes, minutes)
            }
        }
    }

    init {
        loadRestrictions()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    @Synchronized
    fun loadRestrictions() {
        val jsonString = prefs.getString(KEY_RESTRICTIONS_JSON, null) ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<AppRestriction>()
            for (i in 0 until jsonArray.length()) {
                jsonArray.optJSONObject(i)?.let {
                    list.add(AppRestriction.fromJsonObject(it))
                }
            }
            _restrictionsFlow.value = list
        } catch (e: Exception) {
            _restrictionsFlow.value = emptyList()
        }
    }

    private fun persistRestrictions(list: List<AppRestriction>) {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it.toJsonObject()) }
        prefs.edit().putString(KEY_RESTRICTIONS_JSON, jsonArray.toString()).apply()
        _restrictionsFlow.value = list
    }

    fun getAllRestrictions(): List<AppRestriction> {
        loadRestrictions()
        return _restrictionsFlow.value
    }

    fun getRestrictionForPackage(packageName: String): AppRestriction? {
        loadRestrictions()
        return _restrictionsFlow.value.find { it.isEnabled && it.appliesTo(packageName) }
            ?: _restrictionsFlow.value.find { it.appliesTo(packageName) }
    }

    fun saveRestriction(restriction: AppRestriction) {
        val currentList = _restrictionsFlow.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == restriction.id }
        if (index >= 0) {
            currentList[index] = restriction
        } else {
            currentList.add(restriction)
        }
        persistRestrictions(currentList)
    }

    fun deleteRestriction(id: String) {
        val currentList = _restrictionsFlow.value.filter { it.id != id }
        persistRestrictions(currentList)
    }

    fun toggleRestriction(id: String, isEnabled: Boolean) {
        val currentList = _restrictionsFlow.value.map {
            if (it.id == id) it.copy(isEnabled = isEnabled) else it
        }
        persistRestrictions(currentList)
    }

    /**
     * استبدال كامل للقيود (تستخدم عند استيراد نسخة احتياطية في وضع الاستبدال)
     */
    fun replaceAllRestrictions(newList: List<AppRestriction>) {
        persistRestrictions(newList)
    }

    /**
     * دمج قيود مستوردة مع القيود الحالية مع تحديث المتطابق منها وإضافة الجديد
     * يعيد عدد القيود التي تم دمجها
     */
    fun mergeRestrictions(importedList: List<AppRestriction>): Int {
        val currentList = _restrictionsFlow.value.toMutableList()
        var mergedCount = 0

        for (imported in importedList) {
            val existingIndex = currentList.indexOfFirst {
                it.id == imported.id || (it.allPackages.toSet() == imported.allPackages.toSet() && it.allPackages.isNotEmpty())
            }
            if (existingIndex >= 0) {
                currentList[existingIndex] = imported
            } else {
                currentList.add(imported)
            }
            mergedCount++
        }
        persistRestrictions(currentList)
        return mergedCount
    }

    /**
     * تفعيل تخطي الحظر المؤقت لتطبيق معين لعدد محدد من الدقائق لمرة واحدة
     */
    fun setTemporaryBypass(packageName: String, durationMinutes: Int) {
        if (durationMinutes <= 0) return
        val expiry = System.currentTimeMillis() + (durationMinutes * 60_000L)
        prefs.edit()
            .putLong("$PREFIX_BYPASS_UNTIL$packageName", expiry)
            .putInt("$PREFIX_BYPASS_DURATION$packageName", durationMinutes)
            .apply()
        loadRestrictions()
    }

    /**
     * استرجاع الثواني المتبقية للتخطي المؤقت للتطبيق
     */
    fun getTemporaryBypassRemainingSeconds(packageName: String): Long {
        val expiry = prefs.getLong("$PREFIX_BYPASS_UNTIL$packageName", 0L)
        val diff = expiry - System.currentTimeMillis()
        if (diff > 0) return (diff / 1000L) + 1
        if (isSettingsPackage(packageName)) {
            val primarySettings = getAllRestrictions()
                .flatMap { it.allPackages }
                .firstOrNull { isSettingsPackage(it) }
            if (primarySettings != null && primarySettings != packageName) {
                val primaryExpiry = prefs.getLong("$PREFIX_BYPASS_UNTIL$primarySettings", 0L)
                val primaryDiff = primaryExpiry - System.currentTimeMillis()
                if (primaryDiff > 0) return (primaryDiff / 1000L) + 1
            }
        }
        return 0L
    }

    /**
     * التحقق مما إذا كان هناك تخطٍ مؤقت نشط للتطبيق حاليًا
     */
    fun isPackageBypassed(packageName: String): Boolean {
        if (checkBypassInternal(packageName)) return true
        if (isSettingsPackage(packageName)) {
            val primarySettings = getAllRestrictions()
                .flatMap { it.allPackages }
                .firstOrNull { isSettingsPackage(it) }
            if (primarySettings != null && primarySettings != packageName) {
                if (checkBypassInternal(primarySettings)) return true
            }
        }
        return false
    }

    private fun checkBypassInternal(pkg: String): Boolean {
        val remaining = getTemporaryBypassRemainingSeconds(pkg)
        if (remaining <= 0L) {
            if (prefs.contains("$PREFIX_BYPASS_UNTIL$pkg")) {
                prefs.edit()
                    .remove("$PREFIX_BYPASS_UNTIL$pkg")
                    .remove("$PREFIX_BYPASS_DURATION$pkg")
                    .apply()
            }
            return false
        }
        return true
    }

    /**
     * إلغاء التخطي المؤقت فورًا
     */
    fun clearTemporaryBypass(packageName: String) {
        prefs.edit()
            .remove("$PREFIX_BYPASS_UNTIL$packageName")
            .remove("$PREFIX_BYPASS_DURATION$packageName")
            .apply()
        if (isSettingsPackage(packageName)) {
            val primarySettings = getAllRestrictions()
                .flatMap { it.allPackages }
                .firstOrNull { isSettingsPackage(it) }
            if (primarySettings != null && primarySettings != packageName) {
                prefs.edit()
                    .remove("$PREFIX_BYPASS_UNTIL$primarySettings")
                    .remove("$PREFIX_BYPASS_DURATION$primarySettings")
                    .apply()
            }
        }
        loadRestrictions()
    }

    /**
     * تقييم قيد تطبيق معين في اللحظة الحالية والتحقق مما إذا كان محظورًا وسبب الحظر
     */
    fun evaluateRestriction(
        restriction: AppRestriction?,
        consumedMinutes: Int,
        calendar: Calendar = Calendar.getInstance(),
        targetPackage: String? = null
    ): RestrictionEvaluation {
        if (restriction == null || !restriction.isEnabled) {
            return RestrictionEvaluation(
                isBlocked = false,
                reason = BlockReason.NONE,
                consumedMinutes = consumedMinutes,
                allowedMinutes = restriction?.limitDurationMinutes ?: 0,
                nextAvailableText = null,
                restriction = restriction ?: AppRestriction(packageName = "", appName = "")
            )
        }

        // فحص التخطي المؤقت للتطبيق المستهدف تحديداً
        // إذا حُدد تطبيق معين (targetPackage)، نتحقق من التخطي المؤقت لهذا التطبيق فقط!
        // أما إذا لم يُحدد تطبيق وكان القيد لتطبيق فردي واحد، نتحقق من هذا التطبيق الفردي.
        // في حالة المجموعة، لا يُرفع الحظر عن باقي تطبيقات المجموعة إذا تم تخطي أحدها فقط.
        val packageToCheckBypass = when {
            targetPackage != null -> {
                if (restriction.allPackages.contains(targetPackage)) {
                    targetPackage
                } else if (isSettingsPackage(targetPackage)) {
                    restriction.allPackages.firstOrNull { isSettingsPackage(it) } ?: targetPackage
                } else {
                    targetPackage
                }
            }
            restriction.allPackages.size == 1 -> restriction.allPackages.first()
            else -> null
        }

        if (packageToCheckBypass != null && isPackageBypassed(packageToCheckBypass)) {
            val remainingSec = getTemporaryBypassRemainingSeconds(packageToCheckBypass)
            val remainingMin = ((remainingSec + 59) / 60).toInt().coerceAtLeast(1)
            val formattedTime = formatBypassDuration(remainingMin)
            return RestrictionEvaluation(
                isBlocked = false,
                reason = BlockReason.NONE,
                consumedMinutes = consumedMinutes,
                allowedMinutes = restriction.limitDurationMinutes,
                nextAvailableText = Str.get(R.string.bypass_active_remaining, formattedTime),
                restriction = restriction
            )
        }

        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentTotalMinutes = currentHour * 60 + currentMinute

        // 0. فحص الحظر الكامل التام (Total Block)
        if (restriction.isTotalBlock) {
            return RestrictionEvaluation(
                isBlocked = true,
                reason = BlockReason.TOTAL_BLOCK,
                consumedMinutes = consumedMinutes,
                allowedMinutes = 0,
                nextAvailableText = Str.get(R.string.next_total_block),
                restriction = restriction
            )
        }

        // 1. فحص جدول الأوقات المسموحة (Schedule)
        if (restriction.hasSchedule && restriction.timeWindows.isNotEmpty()) {
            val isDayActive = restriction.activeDays.contains(currentDay)

            if (!isDayActive) {
                // اليوم الحالي غير مسموح بالاستخدام فيه
                val nextDay = findNextActiveDay(currentDay, restriction.activeDays)
                val firstWindow = restriction.timeWindows.minByOrNull { it.startHour * 60 + it.startMinute }
                val nextTime = if (firstWindow != null) {
                    Str.get(
                        R.string.next_on_day_at_time,
                        AppRestriction.getDayName(nextDay),
                        firstWindow.startHour,
                        firstWindow.startMinute
                    )
                } else {
                    Str.get(R.string.next_on_day, AppRestriction.getDayName(nextDay))
                }

                return RestrictionEvaluation(
                    isBlocked = true,
                    reason = BlockReason.OUTSIDE_SCHEDULE,
                    consumedMinutes = consumedMinutes,
                    allowedMinutes = restriction.limitDurationMinutes,
                    nextAvailableText = nextTime,
                    restriction = restriction
                )
            }

            // اليوم نشط، نتحقق من مطابقة أي من النوافذ الزمنية المسموحة
            val isInsideAllowedWindow = restriction.timeWindows.any { it.contains(currentHour, currentMinute) }

            if (!isInsideAllowedWindow) {
                val nextWindowToday = restriction.timeWindows
                    .filter { (it.startHour * 60 + it.startMinute) > currentTotalMinutes }
                    .minByOrNull { it.startHour * 60 + it.startMinute }

                val nextTime = if (nextWindowToday != null) {
                    Str.get(R.string.next_today_at_time, nextWindowToday.startHour, nextWindowToday.startMinute)
                } else {
                    // First window on the next active day
                    val nextDay = findNextActiveDay(currentDay, restriction.activeDays)
                    val firstWindow = restriction.timeWindows.minByOrNull { it.startHour * 60 + it.startMinute }
                    if (firstWindow != null) {
                        Str.get(
                            R.string.next_on_day_at_time,
                            AppRestriction.getDayName(nextDay),
                            firstWindow.startHour,
                            firstWindow.startMinute
                        )
                    } else {
                        Str.get(R.string.next_next_window)
                    }
                }

                return RestrictionEvaluation(
                    isBlocked = true,
                    reason = BlockReason.OUTSIDE_SCHEDULE,
                    consumedMinutes = consumedMinutes,
                    allowedMinutes = restriction.limitDurationMinutes,
                    nextAvailableText = nextTime,
                    restriction = restriction
                )
            }
        }

        // 2. فحص حد الاستخدام (Usage Limit)
        if (restriction.hasUsageLimit) {
            val allowedMinutes = restriction.limitDurationMinutes
            if (consumedMinutes >= allowedMinutes) {
                val nextTime = if (restriction.limitPeriod == LimitPeriod.DAILY) {
                    Str.get(R.string.next_limit_daily)
                } else {
                    Str.get(R.string.next_limit_weekly)
                }

                return RestrictionEvaluation(
                    isBlocked = true,
                    reason = BlockReason.LIMIT_EXCEEDED,
                    consumedMinutes = consumedMinutes,
                    allowedMinutes = allowedMinutes,
                    nextAvailableText = nextTime,
                    restriction = restriction
                )
            }
        }

        // اجتاز كافة الشروط، التطبيق متاح
        return RestrictionEvaluation(
            isBlocked = false,
            reason = BlockReason.NONE,
            consumedMinutes = consumedMinutes,
            allowedMinutes = restriction.limitDurationMinutes,
            nextAvailableText = null,
            restriction = restriction
        )
    }

    private fun findNextActiveDay(currentDay: Int, activeDays: Set<Int>): Int {
        for (i in 1..7) {
            val next = ((currentDay - 1 + i) % 7) + 1
            if (activeDays.contains(next)) return next
        }
        return currentDay
    }

    /**
     * حساب الدقائق المستهلكة بدقة عالية في الوقت الحقيقي لكل تطبيق مشمول في القيد
     * يُعيد خريطة (Map) تربط اسم كل حزمة بعدد الدقائق المستهلكة لها
     */
    fun calculatePackageUsageMap(context: Context, restriction: AppRestriction): Map<String, Int> {
        val targetPackages = restriction.allPackages.toSet()
        if (targetPackages.isEmpty() || restriction.isTotalBlock) {
            return targetPackages.associateWith { 0 }
        }

        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()

        val startTime = when (restriction.limitPeriod) {
            LimitPeriod.DAILY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            LimitPeriod.WEEKLY -> {
                calendar.add(Calendar.DAY_OF_YEAR, -6)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            ?: return targetPackages.associateWith { 0 }

        val durations = targetPackages.associateWith { 0L }.toMutableMap()
        val bootTime = now - android.os.SystemClock.elapsedRealtime()

        try {
            val events = usageStatsManager.queryEvents(startTime, now)
            val event = android.app.usage.UsageEvents.Event()
            val startTimes = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val time = event.timeStamp
                val type = event.eventType

                // إغلاق أي جلسات مفتوحة فور حدوث إغلاق للنظام أو إطفاء للشاشة
                if (type == 16 || type == 26 || type == 27) { // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN, DEVICE_STARTUP
                    for ((pkg, start) in startTimes) {
                        if (time > start) {
                            durations[pkg] = (durations[pkg] ?: 0L) + (time - start)
                        }
                    }
                    startTimes.clear()
                    continue
                }

                val pkg = event.packageName ?: continue
                if (!targetPackages.contains(pkg)) continue

                if (type == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && type == 29)
                ) {
                    startTimes[pkg] = time
                } else if (type == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && type == 30)
                ) {
                    val start = startTimes.remove(pkg)
                    if (start != null && time > start) {
                        durations[pkg] = (durations[pkg] ?: 0L) + (time - start)
                    }
                }
            }

            for ((pkg, start) in startTimes) {
                if (start >= bootTime && now > start) {
                    durations[pkg] = (durations[pkg] ?: 0L) + (now - start)
                } else if (start < bootTime) {
                    val safeDuration = (bootTime - start).coerceIn(0L, 60_000L)
                    durations[pkg] = (durations[pkg] ?: 0L) + safeDuration
                }
            }
        } catch (e: Exception) {
            try {
                val stats = usageStatsManager.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    now
                )
                stats?.filter { targetPackages.contains(it.packageName) }?.forEach {
                    durations[it.packageName] = (durations[it.packageName] ?: 0L) + it.totalTimeInForeground
                }
            } catch (ex: Exception) {}
        }

        // خطة بديلة في حال كانت النتائج صفرية لجميع الحزم
        if (durations.values.all { it == 0L }) {
            try {
                val stats = usageStatsManager.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    now
                )
                stats?.filter { targetPackages.contains(it.packageName) }?.forEach {
                    durations[it.packageName] = (durations[it.packageName] ?: 0L) + it.totalTimeInForeground
                }
            } catch (ex: Exception) {}
        }

        return durations.mapValues { (_, ms) -> (ms / 60_000L).toInt() }
    }

    /**
     * حساب الدقائق المستهلكة بدقة عالية في الوقت الحقيقي لتطبيق أو لمجموعة تطبيقات
     * @param targetPackage التطبيق المحدد المطلوب معرفة استهلاكه.
     * إذا كان النمط EACH_APP، يُحسب استهلاك targetPackage فقط، وإذا كان SHARED_SUM يُحسب إجمالي المجموعة.
     */
    fun calculateConsumedMinutes(
        context: Context,
        restriction: AppRestriction,
        targetPackage: String? = null
    ): Int {
        if (restriction.isTotalBlock) return 0

        val usageMap = calculatePackageUsageMap(context, restriction)
        val resolvedPackage = if (targetPackage != null) {
            if (restriction.allPackages.contains(targetPackage)) {
                targetPackage
            } else if (isSettingsPackage(targetPackage)) {
                restriction.allPackages.firstOrNull { isSettingsPackage(it) } ?: targetPackage
            } else {
                targetPackage
            }
        } else null

        return when {
            resolvedPackage != null -> {
                if (restriction.groupLimitType == GroupLimitType.SHARED_SUM) {
                    usageMap.values.sum()
                } else {
                    usageMap[resolvedPackage] ?: 0
                }
            }
            restriction.groupLimitType == GroupLimitType.SHARED_SUM -> {
                usageMap.values.sum()
            }
            else -> {
                // في حالة عدم تحديد تطبيق والنمط EACH_APP، نأخذ القيمة القصوى لاستهلاك أي تطبيق في المجموعة
                usageMap.values.maxOrNull() ?: 0
            }
        }
    }

    /**
     * التحقق مما إذا كان تطبيق معين خاضعاً للحظر حالياً داخل القيد
     */
    fun isPackageBlocked(
        context: Context,
        restriction: AppRestriction,
        packageName: String,
        calendar: Calendar = Calendar.getInstance()
    ): Boolean {
        if (!restriction.isEnabled) return false
        if (isPackageBypassed(packageName)) return false
        val consumed = calculateConsumedMinutes(context, restriction, packageName)
        return evaluateRestriction(restriction, consumed, calendar, packageName).isBlocked
    }
}
