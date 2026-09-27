package com.example.screenmonitor.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class SecurityEventType {
    PERMISSION_REVOKED,      // سحب أو إيقاف إذن التقاط الشاشة أثناء فترة المراقبة
    UNEXPECTED_SERVICE_STOP, // توقف الخدمة بشكل مفاجئ دون أمر إيقاف من داخل التطبيق
    FAILED_ATTEMPTS_LOCKOUT, // قفل مؤقت بسبب محاولات دخول خاطئة متكررة
    STORAGE_FULL             // توقف المراقبة تلقائياً بسبب انخفاض مساحة التخزين الحرج
}

data class SecurityEvent(
    val id: String,
    val timestamp: Long,
    val type: SecurityEventType,
    val title: String,
    val details: String,
    val formattedDate: String,
    val formattedTime: String
)

class SecurityLogManager(
    context: Context? = null,
    private val prefs: SharedPreferences = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun logEvent(type: SecurityEventType, title: String, details: String) {
        val now = System.currentTimeMillis()
        val eventId = UUID.randomUUID().toString()
        val serialized = "$eventId|$now|${type.name}|$title|$details"

        val currentList = getRawEvents().toMutableList()
        currentList.add(0, serialized) // Add newest first

        // Keep maximum 50 events to avoid storage bloat
        val trimmed = if (currentList.size > 50) currentList.take(50) else currentList

        prefs.edit()
            .putString(KEY_EVENTS, trimmed.joinToString(SEPARATOR))
            .putBoolean(KEY_HAS_UNREAD, true)
            .apply()
    }

    fun getEvents(): List<SecurityEvent> {
        val raw = prefs.getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()

        return raw.split(SEPARATOR).mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size >= 5) {
                val timestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
                val type = try {
                    SecurityEventType.valueOf(parts[2])
                } catch (e: Exception) {
                    SecurityEventType.UNEXPECTED_SERVICE_STOP
                }
                SecurityEvent(
                    id = parts[0],
                    timestamp = timestamp,
                    type = type,
                    title = parts[3],
                    details = parts[4],
                    formattedDate = dateFormat.format(Date(timestamp)),
                    formattedTime = timeFormat.format(Date(timestamp))
                )
            } else {
                null
            }
        }
    }

    fun hasUnreadAlert(): Boolean {
        return prefs.getBoolean(KEY_HAS_UNREAD, false)
    }

    fun markAlertsAsRead() {
        prefs.edit().putBoolean(KEY_HAS_UNREAD, false).apply()
    }

    fun clearLogs() {
        prefs.edit()
            .remove(KEY_EVENTS)
            .putBoolean(KEY_HAS_UNREAD, false)
            .apply()
    }

    private fun getRawEvents(): List<String> {
        val raw = prefs.getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR)
    }

    companion object {
        private const val PREFS_NAME = "screen_monitor_security_logs"
        private const val KEY_EVENTS = "security_events"
        private const val KEY_HAS_UNREAD = "has_unread_alert"
        private const val SEPARATOR = "###EVENT_SEP###"
    }
}
