package com.screenguard.app.service

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.screenguard.app.MainActivity
import com.screenguard.app.ScreenGuardAccessibilityService
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.AppRestriction
import com.screenguard.app.data.model.isSettingsPackage
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.ui.block.BlockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Lightweight background foreground-service that polls the current
 * foreground app and enforces usage restrictions as the fallback
 * enforcement layer beneath the accessibility service.
 */
class AppBlockerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var restrictionsRepo: AppRestrictionsRepository
    private lateinit var appInfoManager: AppInfoManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager

    private var lastBlockedPackage: String? = null
    private var lastBlockTimestamp: Long = 0L
    private var lastHeartbeatTime: Long = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (BlockOverlayManager.isShowing || lastBlockedPackage != null) {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        startActivity(homeIntent)
                    } catch (e: Exception) {}
                    BlockOverlayManager.dismiss()
                    lastBlockedPackage = null
                }
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "screenguard_blocker_channel"
        private const val BLOCK_ALERT_CHANNEL_ID = "screenguard_block_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BLOCK_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, AppBlockerService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Handle background-start restrictions on some devices
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppBlockerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        restrictionsRepo = AppRestrictionsRepository.getInstance(this)
        appInfoManager = AppInfoManager.getInstance(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, filter)
            }
        } catch (e: Exception) {
            try {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            } catch (ignored: Exception) {}
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        PinManager.recordHeartbeat(this)
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val hasActive = restrictionsRepo.getAllRestrictions().any { it.isEnabled }
            if (hasActive) {
                // Auto-restart when swiped away from recents
                val restartServiceIntent = Intent(applicationContext, AppBlockerService::class.java).also {
                    it.setPackage(packageName)
                }
                val restartPendingIntent = PendingIntent.getService(
                    this,
                    101,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmService?.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L,
                    restartPendingIntent
                )
            }
        } catch (e: Exception) {
            // Never crash the service
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {}
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatTime > 10_000L) {
                        lastHeartbeatTime = now
                        PinManager.recordHeartbeat(this@AppBlockerService)
                    }

                    // Pause while the screen is off or keyguard locked (battery + privacy)
                    if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                        if (BlockOverlayManager.isShowing) {
                            BlockOverlayManager.dismiss()
                        }
                        delay(3000)
                        continue
                    }

                    checkForegroundApp()
                } catch (e: Exception) {
                    // Never crash the loop
                }

                delay(1200) // Poll every 1.2 s
            }
        }
    }

    private fun checkForegroundApp() {
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            if (BlockOverlayManager.isShowing) {
                BlockOverlayManager.dismiss()
            }
            return
        }

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 45_000, now)
        val event = UsageEvents.Event()

        var currentForegroundPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.eventType == 29)
            ) {
                currentForegroundPackage = event.packageName
            }
        }

        // Fallback to recent stats when no events are returned
        if (currentForegroundPackage == null) {
            val recentStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000, now)
            currentForegroundPackage = recentStats?.filter {
                it.packageName != packageName &&
                !it.packageName.startsWith("com.android.systemui") &&
                !it.packageName.startsWith("com.google.android.inputmethod")
            }?.maxByOrNull { it.lastTimeUsed }?.packageName
        }

        val topPackage = currentForegroundPackage ?: return

        // Ignore our own app and system surfaces
        if (topPackage == packageName ||
            topPackage == "android" ||
            topPackage.startsWith("com.google.android.inputmethod") ||
            topPackage.startsWith("com.android.systemui")
        ) {
            return
        }

        val restriction = restrictionsRepo.getRestrictionForPackage(topPackage)
        if (restriction == null || !restriction.isEnabled) {
            if (lastBlockedPackage == topPackage) {
                lastBlockedPackage = null
            }
            if (BlockOverlayManager.currentShowingPackage == topPackage) {
                BlockOverlayManager.dismiss()
            }
            return
        }

        // Active temporary bypass for this specific package
        if (restrictionsRepo.isPackageBypassed(topPackage)) {
            if (lastBlockedPackage == topPackage) {
                lastBlockedPackage = null
            }
            if (BlockOverlayManager.currentShowingPackage == topPackage) {
                BlockOverlayManager.dismiss()
            }
            return
        }

        val consumedMinutes = calculateConsumedMinutes(restriction, topPackage)

        val calendar = Calendar.getInstance()
        val evaluation = restrictionsRepo.evaluateRestriction(restriction, consumedMinutes, calendar, topPackage)

        if (evaluation.isBlocked) {
            // De-duplicate repeated block launches within 2 seconds
            if (lastBlockedPackage == topPackage && (now - lastBlockTimestamp) < 2000L) {
                return
            }

            lastBlockedPackage = topPackage
            lastBlockTimestamp = now

            val appName = appInfoManager.getAppName(topPackage)
            val canDrawOverlay = Settings.canDrawOverlays(this)
            val isSettingsApp = isSettingsPackage(topPackage)

            if (isSettingsApp && !ScreenGuardAccessibilityService.isRunning()) {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(homeIntent)
                } catch (e: Exception) {}
            }

            val blockIntent = BlockActivity.createIntent(
                context = this,
                packageName = topPackage,
                appName = appName,
                reason = evaluation.detailedReasonText,
                nextAvailable = evaluation.nextAvailableText,
                consumedMinutes = evaluation.consumedMinutes,
                allowedMinutes = evaluation.allowedMinutes
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            try {
                startActivity(blockIntent)
            } catch (e: Exception) {}

            showBlockNotification(blockIntent, appName, evaluation.detailedReasonText)

            if (canDrawOverlay) {
                // Show the floating shield directly above the blocked app
                BlockOverlayManager.show(
                    context = this,
                    packageName = topPackage,
                    appName = appName,
                    reason = evaluation.detailedReasonText,
                    nextAvailable = evaluation.nextAvailableText,
                    onBypassAction = { durationMinutes ->
                        serviceScope.launch {
                            delay(durationMinutes * 60_000L)
                            restrictionsRepo.clearTemporaryBypass(topPackage)
                            checkForegroundApp()
                        }
                    }
                )
            }
        } else {
            if (lastBlockedPackage == topPackage) {
                lastBlockedPackage = null
            }
            if (BlockOverlayManager.currentShowingPackage == topPackage) {
                BlockOverlayManager.dismiss()
            }
        }
    }

    private fun showBlockNotification(intent: Intent, appName: String, reason: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BLOCK_ALERT_CHANNEL_ID,
                Str.get(R.string.notif_channel_block_alerts),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = Str.get(R.string.notif_channel_block_alerts_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, BLOCK_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(Str.get(R.string.notif_block_title))
            .setContentText(Str.get(R.string.notif_block_text, appName, reason))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(BLOCK_NOTIFICATION_ID, notification)
    }

    private fun calculateConsumedMinutes(restriction: AppRestriction, targetPackage: String? = null): Int {
        return restrictionsRepo.calculateConsumedMinutes(this, restriction, targetPackage)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                Str.get(R.string.notif_channel_restriction_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = Str.get(R.string.notif_channel_restriction_service_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(Str.get(R.string.app_name))
            .setContentText(Str.get(R.string.notif_blocker_running))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
