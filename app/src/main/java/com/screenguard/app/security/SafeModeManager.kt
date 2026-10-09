package com.screenguard.app.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.repository.AppRestrictionsRepository

/** Result of a safe-mode / boot audit check. */
data class SafeModeAuditResult(
    val isCurrentlyInSafeMode: Boolean,
    val hasUnauthorizedUsageDuringOffline: Boolean,
    val unauthorizedPackages: List<String> = emptyList(),
    val unauthorizedMinutes: Int = 0,
    val message: String? = null
)

/**
 * Manager detecting safe-mode boots and auditing restricted-app usage that
 * occurred while protection was down, including clock-rollback detection.
 */
object SafeModeManager {

    /** Direct check whether Android is currently running in Safe Mode. */
    fun isDeviceInSafeMode(context: Context): Boolean {
        return try {
            context.packageManager.isSafeMode
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Comprehensive audit on system boot: detects restricted-app usage during
     * the protection-downtime window and records violations accordingly.
     */
    fun performBootAudit(context: Context): SafeModeAuditResult {
        if (!PinManager.isSafeModeProtectionEnabled(context)) {
            return SafeModeAuditResult(
                isCurrentlyInSafeMode = false,
                hasUnauthorizedUsageDuringOffline = false
            )
        }

        val inSafeMode = isDeviceInSafeMode(context)
        val currentTime = System.currentTimeMillis()
        val currentBootTime = currentTime - SystemClock.elapsedRealtime()
        val lastHeartbeat = PinManager.getLastHeartbeatTimestamp(context)
        val lastBootTime = PinManager.getLastBootTime(context)

        // 1. Device currently running in Safe Mode
        if (inSafeMode) {
            val message = Str.get(R.string.audit_safe_mode_active)
            PinManager.recordSafeModeViolation(context, message)
            PinManager.recordBootTime(context, currentBootTime)
            PinManager.recordHeartbeat(context)
            return SafeModeAuditResult(
                isCurrentlyInSafeMode = true,
                hasUnauthorizedUsageDuringOffline = false,
                message = message
            )
        }

        // 2. First launch ever (no previous heartbeat)
        if (lastHeartbeat <= 0L) {
            PinManager.recordBootTime(context, currentBootTime)
            PinManager.recordHeartbeat(context)
            return SafeModeAuditResult(
                isCurrentlyInSafeMode = false,
                hasUnauthorizedUsageDuringOffline = false
            )
        }

        val restrictionsRepo = AppRestrictionsRepository.getInstance(context)
        val restrictedPackages = restrictionsRepo.getAllRestrictions()
            .filter { it.isEnabled }
            .flatMap { it.allPackages }
            .toSet()

        if (restrictedPackages.isEmpty()) {
            PinManager.recordBootTime(context, currentBootTime)
            PinManager.recordHeartbeat(context)
            return SafeModeAuditResult(
                isCurrentlyInSafeMode = false,
                hasUnauthorizedUsageDuringOffline = false
            )
        }

        // 3. Inspect usage events during the protection-downtime window
        val auditStartTime = lastHeartbeat.coerceAtMost(currentTime - 1000L)
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val unauthorizedFound = mutableSetOf<String>()
        var totalUnauthorizedDurationMs = 0L

        if (usageStatsManager != null && currentTime > auditStartTime) {
            try {
                val events = usageStatsManager.queryEvents(auditStartTime, currentTime)
                val event = UsageEvents.Event()
                val sessionStarts = mutableMapOf<String, Long>()

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val eventTime = event.timeStamp
                    val type = event.eventType

                    if (type == 16 || type == 26 || type == 27) { // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN, DEVICE_STARTUP
                        for ((_, start) in sessionStarts) {
                            if (eventTime > start) {
                                totalUnauthorizedDurationMs += (eventTime - start)
                            }
                        }
                        sessionStarts.clear()
                        continue
                    }

                    val pkg = event.packageName ?: continue
                    if (!restrictedPackages.contains(pkg)) continue

                    if (type == UsageEvents.Event.ACTIVITY_RESUMED ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == 29)
                    ) {
                        sessionStarts[pkg] = eventTime
                        unauthorizedFound.add(pkg)
                    } else if (type == UsageEvents.Event.ACTIVITY_PAUSED ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == 30)
                    ) {
                        val start = sessionStarts.remove(pkg)
                        if (start != null && eventTime > start) {
                            totalUnauthorizedDurationMs += (eventTime - start)
                        }
                    }
                }

                // Add still-open sessions at boot, clamped to boot time
                for ((_, start) in sessionStarts) {
                    if (start >= currentBootTime && currentTime > start) {
                        totalUnauthorizedDurationMs += (currentTime - start)
                    } else if (start < currentBootTime) {
                        val safeDuration = (currentBootTime - start).coerceIn(0L, 60_000L)
                        totalUnauthorizedDurationMs += safeDuration
                    }
                }
            } catch (e: Exception) {
                // Quietly skip when the query is unavailable
            }
        }

        // Clock-rollback detection across reboots
        val hasClockTampering = lastHeartbeat > 0L && currentTime < (lastHeartbeat - 60_000L)
        if (hasClockTampering) {
            PinManager.recordSafeModeViolation(context, Str.get(R.string.violation_clock_rollback))
        }

        val unauthorizedMinutes = (totalUnauthorizedDurationMs / 60_000L).toInt()

        // 4. Unregistered reboot with restricted activity
        val isNewReboot = lastBootTime > 0L && Math.abs(currentBootTime - lastBootTime) > 60_000L
        val hasViolation = unauthorizedFound.isNotEmpty()

        val violationMessage = when {
            hasViolation && isNewReboot -> {
                Str.get(R.string.audit_reboot_violation, unauthorizedFound.size)
            }
            hasViolation -> {
                Str.get(R.string.audit_downtime_violation, unauthorizedMinutes)
            }
            else -> null
        }

        if (violationMessage != null) {
            PinManager.recordSafeModeViolation(context, violationMessage)
        }

        // Refresh heartbeat and boot time
        PinManager.recordBootTime(context, currentBootTime)
        PinManager.recordHeartbeat(context)

        return SafeModeAuditResult(
            isCurrentlyInSafeMode = false,
            hasUnauthorizedUsageDuringOffline = hasViolation,
            unauthorizedPackages = unauthorizedFound.toList(),
            unauthorizedMinutes = unauthorizedMinutes,
            message = violationMessage
        )
    }
}
