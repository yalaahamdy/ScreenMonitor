package com.example.screenmonitor.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(
    context: Context? = null,
    private val prefs: SharedPreferences = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {

    var captureIntervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_SECONDS, value).apply()

    var retentionHours: Int
        get() = prefs.getInt(KEY_RETENTION_HOURS, DEFAULT_RETENTION_HOURS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_HOURS, value).apply()

    var isAutoCleanEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAN_ENABLED, value).apply()

    var isMonitoringActive: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ACTIVE, value).apply()

    var wasMonitoringBeforeReboot: Boolean
        get() = prefs.getBoolean(KEY_WAS_MONITORING_BEFORE_REBOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_WAS_MONITORING_BEFORE_REBOOT, value).apply()

    var isDiscreetNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_DISCREET_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_DISCREET_NOTIFICATION, value).apply()

    var lastCaptureTimeMillis: Long
        get() = prefs.getLong(KEY_LAST_CAPTURE_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CAPTURE_TIME, value).apply()

    companion object {
        private const val PREFS_NAME = "screen_monitor_settings"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val KEY_RETENTION_HOURS = "retention_hours"
        private const val KEY_AUTO_CLEAN_ENABLED = "auto_clean_enabled"
        private const val KEY_MONITORING_ACTIVE = "monitoring_active"
        private const val KEY_WAS_MONITORING_BEFORE_REBOOT = "was_monitoring_before_reboot"
        private const val KEY_DISCREET_NOTIFICATION = "discreet_notification"
        private const val KEY_LAST_CAPTURE_TIME = "last_capture_time"

        const val DEFAULT_INTERVAL_SECONDS = 60 // 1 minute
        const val DEFAULT_RETENTION_HOURS = 72 // 3 days
    }
}
