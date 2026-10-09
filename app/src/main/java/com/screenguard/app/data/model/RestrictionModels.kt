package com.screenguard.app.data.model

import androidx.annotation.StringRes
import com.screenguard.app.R
import com.screenguard.app.Str
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Allowed usage time window (from - to). */
data class TimeWindow(
    val startHour: Int, // 0..23
    val startMinute: Int, // 0..59
    val endHour: Int, // 0..23
    val endMinute: Int // 0..59
) {
    val displayRange: String
        get() = String.format(Locale.getDefault(), "%02d:%02d – %02d:%02d", startHour, startMinute, endHour, endMinute)

    /** Check whether the given time falls inside this window (supports overnight ranges). */
    fun contains(hour: Int, minute: Int): Boolean {
        val currentTotalMinutes = hour * 60 + minute
        val startTotalMinutes = startHour * 60 + startMinute
        val endTotalMinutes = endHour * 60 + endMinute

        return if (startTotalMinutes <= endTotalMinutes) {
            currentTotalMinutes in startTotalMinutes until endTotalMinutes
        } else {
            // Window extends past midnight
            currentTotalMinutes >= startTotalMinutes || currentTotalMinutes < endTotalMinutes
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("startHour", startHour)
            put("startMinute", startMinute)
            put("endHour", endHour)
            put("endMinute", endMinute)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): TimeWindow {
            return TimeWindow(
                startHour = json.optInt("startHour", 0),
                startMinute = json.optInt("startMinute", 0),
                endHour = json.optInt("endHour", 23),
                endMinute = json.optInt("endMinute", 59)
            )
        }
    }
}

/** Usage limit cycle type. */
enum class LimitPeriod(@StringRes val titleRes: Int) {
    DAILY(R.string.limit_period_daily),
    WEEKLY(R.string.limit_period_weekly)
}

/** How the usage limit applies within multi-app groups. */
enum class GroupLimitType(@StringRes val titleRes: Int, @StringRes val descriptionRes: Int) {
    EACH_APP(R.string.group_limit_each_title, R.string.group_limit_each_desc),
    SHARED_SUM(R.string.group_limit_shared_title, R.string.group_limit_shared_desc)
}

/** Reason a package is currently blocked. */
enum class BlockReason(@StringRes val titleRes: Int) {
    NONE(R.string.block_reason_none),
    TOTAL_BLOCK(R.string.block_reason_total),
    LIMIT_EXCEEDED(R.string.block_reason_limit),
    OUTSIDE_SCHEDULE(R.string.block_reason_schedule)
}

/** Usage restriction applying to one or more packages. */
data class AppRestriction(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String = "", // primary package (compatibility)
    val appName: String = "",
    val targetPackages: List<String> = emptyList(), // multi-app groups support
    val isEnabled: Boolean = true,
    // immediate total block on open
    val isTotalBlock: Boolean = false,
    // first: usage limit
    val hasUsageLimit: Boolean = false,
    val limitDurationMinutes: Int = 30,
    val limitPeriod: LimitPeriod = LimitPeriod.DAILY,
    val groupLimitType: GroupLimitType = GroupLimitType.EACH_APP,
    // second: usage schedule
    val hasSchedule: Boolean = false,
    val timeWindows: List<TimeWindow> = emptyList(),
    val activeDays: Set<Int> = (1..7).toSet(), // Calendar days (1 = Sunday .. 7 = Saturday)
    val createdAt: Long = System.currentTimeMillis()
) {
    /** All packages governed by this restriction. */
    val allPackages: List<String>
        get() = if (targetPackages.isNotEmpty()) targetPackages else if (packageName.isNotBlank()) listOf(packageName) else emptyList()

    /** Whether this restriction applies to the given package (incl. Settings variants). */
    fun appliesTo(pkg: String): Boolean {
        if (allPackages.contains(pkg)) return true
        // If the restriction includes a Settings package, it applies to all Settings sub-screens
        val hasSettings = allPackages.any { isSettingsPackage(it) }
        if (hasSettings && isSettingsPackage(pkg)) {
            return true
        }
        return false
    }

    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("packageName", packageName)
        json.put("appName", appName)

        val pkgsArray = JSONArray()
        allPackages.forEach { pkgsArray.put(it) }
        json.put("targetPackages", pkgsArray)

        json.put("isEnabled", isEnabled)
        json.put("isTotalBlock", isTotalBlock)
        json.put("hasUsageLimit", hasUsageLimit)
        json.put("limitDurationMinutes", limitDurationMinutes)
        json.put("limitPeriod", limitPeriod.name)
        json.put("groupLimitType", groupLimitType.name)
        json.put("hasSchedule", hasSchedule)

        val windowsArray = JSONArray()
        timeWindows.forEach { windowsArray.put(it.toJsonObject()) }
        json.put("timeWindows", windowsArray)

        val daysArray = JSONArray()
        activeDays.forEach { daysArray.put(it) }
        json.put("activeDays", daysArray)

        json.put("createdAt", createdAt)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AppRestriction {
            val windows = mutableListOf<TimeWindow>()
            val windowsArray = json.optJSONArray("timeWindows")
            if (windowsArray != null) {
                for (i in 0 until windowsArray.length()) {
                    windowsArray.optJSONObject(i)?.let { windows.add(TimeWindow.fromJsonObject(it)) }
                }
            }

            val days = mutableSetOf<Int>()
            val daysArray = json.optJSONArray("activeDays")
            if (daysArray != null) {
                for (i in 0 until daysArray.length()) {
                    days.add(daysArray.optInt(i))
                }
            } else {
                days.addAll(1..7)
            }

            val targetPkgs = mutableListOf<String>()
            val pkgsArray = json.optJSONArray("targetPackages")
            if (pkgsArray != null) {
                for (i in 0 until pkgsArray.length()) {
                    targetPkgs.add(pkgsArray.optString(i))
                }
            }

            val basePackage = json.optString("packageName", "")
            if (targetPkgs.isEmpty() && basePackage.isNotBlank()) {
                targetPkgs.add(basePackage)
            }

            return AppRestriction(
                id = json.optString("id", UUID.randomUUID().toString()),
                packageName = basePackage.ifBlank { targetPkgs.firstOrNull() ?: "" },
                appName = json.optString("appName", ""),
                targetPackages = targetPkgs,
                isEnabled = json.optBoolean("isEnabled", true),
                isTotalBlock = json.optBoolean("isTotalBlock", false),
                hasUsageLimit = json.optBoolean("hasUsageLimit", false),
                limitDurationMinutes = json.optInt("limitDurationMinutes", 30),
                limitPeriod = try {
                    LimitPeriod.valueOf(json.optString("limitPeriod", LimitPeriod.DAILY.name))
                } catch (e: Exception) {
                    LimitPeriod.DAILY
                },
                groupLimitType = try {
                    GroupLimitType.valueOf(json.optString("groupLimitType", GroupLimitType.EACH_APP.name))
                } catch (e: Exception) {
                    GroupLimitType.EACH_APP
                },
                hasSchedule = json.optBoolean("hasSchedule", false),
                timeWindows = windows,
                activeDays = days,
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }

        fun getDayName(calendarDay: Int): String {
            @StringRes val res = when (calendarDay) {
                Calendar.SUNDAY -> R.string.day_sunday
                Calendar.MONDAY -> R.string.day_monday
                Calendar.TUESDAY -> R.string.day_tuesday
                Calendar.WEDNESDAY -> R.string.day_wednesday
                Calendar.THURSDAY -> R.string.day_thursday
                Calendar.FRIDAY -> R.string.day_friday
                Calendar.SATURDAY -> R.string.day_saturday
                else -> 0
            }
            return if (res == 0) "" else Str.get(res)
        }

        /** Automatic category guess to assist quick selection. Returns a category key id. */
        fun guessAppCategory(packageName: String): AppCategory {
            val lower = packageName.lowercase(Locale.getDefault())
            return when {
                lower.contains("whatsapp") || lower.contains("instagram") ||
                        lower.contains("facebook") || lower.contains("twitter") ||
                        lower.contains("snapchat") || lower.contains("tiktok") ||
                        lower.contains("telegram") || lower.contains("messenger") ||
                        lower.contains("discord") || lower.contains("threads") -> AppCategory.SOCIAL

                lower.contains("youtube") || lower.contains("netflix") ||
                        lower.contains("spotify") || lower.contains("twitch") ||
                        lower.contains("primevideo") || lower.contains("shahid") ||
                        lower.contains("music") || lower.contains("video") -> AppCategory.MEDIA

                lower.contains("game") || lower.contains("pubg") ||
                        lower.contains("roblox") || lower.contains("clash") ||
                        lower.contains("candycrush") || lower.contains("subway") -> AppCategory.GAMES

                else -> AppCategory.OTHER
            }
        }
    }
}

/** App categories used by the app-selection picker. */
enum class AppCategory(@StringRes val titleRes: Int) {
    ALL(R.string.category_all),
    MOST_USED(R.string.category_most_used),
    SOCIAL(R.string.category_social),
    MEDIA(R.string.category_media),
    GAMES(R.string.category_games),
    OTHER(R.string.category_other)
}

/** Current evaluation result of a restriction at a moment in time. */
data class RestrictionEvaluation(
    val isBlocked: Boolean,
    val reason: BlockReason,
    val consumedMinutes: Int,
    val allowedMinutes: Int,
    val nextAvailableText: String?,
    val restriction: AppRestriction
) {
    val detailedReasonText: String
        get() = when (reason) {
            BlockReason.TOTAL_BLOCK -> Str.get(R.string.eval_total_block)
            BlockReason.LIMIT_EXCEEDED -> {
                if (restriction.limitPeriod == LimitPeriod.DAILY) {
                    Str.get(R.string.eval_limit_daily, consumedMinutes, allowedMinutes)
                } else {
                    Str.get(R.string.eval_limit_weekly, consumedMinutes, allowedMinutes)
                }
            }
            BlockReason.OUTSIDE_SCHEDULE -> Str.get(R.string.eval_outside_schedule)
            BlockReason.NONE -> Str.get(R.string.eval_allowed)
        }
}

/** Whether the package belongs to any vendor Settings app. */
fun isSettingsPackage(pkg: String?): Boolean {
    if (pkg.isNullOrBlank()) return false
    val lower = pkg.lowercase(Locale.getDefault())
    return lower == "com.android.settings" ||
            lower.startsWith("com.android.settings.") ||
            lower == "com.google.android.settings" ||
            lower.startsWith("com.google.android.settings.") ||
            lower == "com.samsung.android.settings" ||
            lower.startsWith("com.samsung.android.settings.") ||
            lower == "com.coloros.settings" ||
            lower.startsWith("com.coloros.settings.") ||
            lower == "com.oplus.settings" ||
            lower.startsWith("com.oplus.settings.") ||
            lower == "com.vivo.settings" ||
            lower.startsWith("com.vivo.settings.") ||
            lower == "com.huawei.settings" ||
            lower.startsWith("com.huawei.settings.") ||
            lower == "com.xiaomi.settings" ||
            lower.startsWith("com.xiaomi.settings.") ||
            lower == "com.motorola.android.settings" ||
            lower.startsWith("com.motorola.android.settings.") ||
            lower == "com.miui.securitycenter"
}

/**
 * Whether the object/event represents a Settings popup or dialog
 * (Wi-Fi panels, Bluetooth pairing, sound panels, permission prompts).
 */
fun isSettingsPopupOrDialog(
    className: String?,
    packageName: String? = null,
    windowWidth: Int = 0,
    windowHeight: Int = 0,
    screenWidth: Int = 0,
    screenHeight: Int = 0
): Boolean {
    val cls = className ?: ""
    val pkg = packageName ?: ""

    // 1. Class-name keywords explicitly indicating popups and dialogs
    val dialogKeywords = listOf(
        "Dialog",
        "AlertDialog",
        "Panel",
        "Popup",
        "BottomSheet",
        "Slice",
        "Prompt",
        "Pairing",
        "Chooser",
        "Toast",
        "Floating"
    )
    if (dialogKeywords.any { cls.contains(it, ignoreCase = true) }) {
        return true
    }

    // 2. Android system sub-packages dedicated to popups and quick-settings panels
    val popupPackages = listOf(
        "com.android.settings.panel",
        "com.android.settings.slices",
        "com.android.settings.bluetooth.BluetoothPairingDialog",
        "com.android.settings.wifi.WifiDialogActivity",
        "com.android.settings.wifi.slice",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )
    if (popupPackages.any { pkg.startsWith(it) || cls.startsWith(it) }) {
        return true
    }

    // 3. Window dimensions when available (dialogs occupy less than full screen)
    if (windowWidth > 0 && windowHeight > 0 && screenWidth > 0 && screenHeight > 0) {
        val isNotFullscreen = (windowWidth < screenWidth * 0.92f) || (windowHeight < screenHeight * 0.85f)
        if (isNotFullscreen) {
            return true
        }
    }

    return false
}
