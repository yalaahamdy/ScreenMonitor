package com.screenguard.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import android.widget.Toast

/**
 * The heart of ScreenGuard.
 *
 * Runs as a specialUse foreground service so the persistent monitoring
 * notification can never be hidden, takes periodic screenshots and captures
 * the screen whenever a newly opened app comes to the foreground.
 */
class ScreenGuardAccessibilityService : AccessibilityService() {

    /** Wrap the service context so notifications follow the in-app language. */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    companion object {
        private const val TAG = "ScreenGuardSvc"

        @Volatile
        var instance: ScreenGuardAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** True when the user enabled the service in system settings. */
        fun isEnabledInSettings(ctx: android.content.Context): Boolean {
            val expected = "${ctx.packageName}/${ScreenGuardAccessibilityService::class.java.canonicalName}"
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun refreshSchedule() {
            instance?.scheduleNextCapture()
        }

        private val IGNORED_PREFIXES = listOf(
            "com.android.systemui",
            "com.screenguard.app",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private var lastForegroundApp: String? = null
    private var captureInFlight = false
    private var lastCaptureAttemptTime = 0L
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                val intervalMs = Prefs.intervalMinutes(this@ScreenGuardAccessibilityService).coerceAtLeast(1) * 60_000L
                val last = Prefs.lastCapture(this@ScreenGuardAccessibilityService)
                val now = System.currentTimeMillis()
                if (last == 0L || (now - last) >= intervalMs) {
                    handler.postDelayed({
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm?.isInteractive != false) {
                            capture("wake")
                            scheduleNextCapture()
                        }
                    }, 1000L)
                }
            }
        }
    }

    private val periodicTask = object : Runnable {
        override fun run() {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = pm?.isInteractive ?: true
            if (isInteractive) {
                capture("periodic")
            }
            scheduleNextCapture()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.monitoringNotification(this)
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }

        registerScreenReceiver()
        lastForegroundApp = null
        scheduleNextCapture()

        // Immediate initial capture after 2 seconds to confirm functionality
        handler.postDelayed({
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive != false) {
                capture("initial")
            }
        }, 2000L)

        Log.i(TAG, "Service connected — monitoring active")
    }

    private fun registerScreenReceiver() {
        if (!screenReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                registerReceiver(screenReceiver, filter)
                screenReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register screen receiver", e)
            }
        }
    }

    private fun unregisterScreenReceiver() {
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {}
            screenReceiverRegistered = false
        }
    }

    fun scheduleNextCapture() {
        handler.removeCallbacks(periodicTask)
        val minutes = Prefs.intervalMinutes(this).coerceAtLeast(1)
        val intervalMs = minutes * 60_000L
        handler.postDelayed(periodicTask, intervalMs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank()) return
        if (IGNORED_PREFIXES.any { pkg.startsWith(it) }) return
        if (pkg.contains("launcher", ignoreCase = true)) return

        if (Prefs.captureOnAppOpen(this) && pkg != lastForegroundApp) {
            lastForegroundApp = pkg
            capture("app-open")
        }
    }

    @SuppressLint("WrongConstant")
    fun capture(reason: String) {
        val svc = instance ?: return
        val now = System.currentTimeMillis()
        if (captureInFlight && (now - lastCaptureAttemptTime < 10_000L)) {
            Log.d(TAG, "Capture in flight ($reason), skipping")
            return
        }
        captureInFlight = true
        lastCaptureAttemptTime = now

        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val buffer: HardwareBuffer = screenshot.hardwareBuffer
                            val hardwareBitmap =
                                Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val softBitmap = hardwareBitmap?.copy(
                                Bitmap.Config.ARGB_8888, false
                            )
                            buffer.close()
                            if (softBitmap != null) {
                                saveCapture(softBitmap, reason)
                            } else {
                                Log.w(TAG, "Screenshot bitmap was null ($reason)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process screenshot ($reason)", e)
                        } finally {
                            captureInFlight = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed ($reason): errorCode=$errorCode")
                        captureInFlight = false
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot threw exception ($reason)", e)
            captureInFlight = false
        }
    }

    private fun saveCapture(bitmap: Bitmap, reason: String) {
        saveExecutor.execute {
            try {
                val file = ScreenshotStore.save(this, bitmap)
                bitmap.recycle()
                if (file != null) {
                    Prefs.setLastCapture(this, System.currentTimeMillis())
                    Prefs.setLastCapturedApp(this, reason)
                    if (Prefs.showToast(this)) {
                        handler.post {
                            Toast.makeText(
                                this, R.string.toast_captured, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    Log.i(TAG, "Capture saved ($reason): ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Saving capture failed", e)
            }
        }
    }

    override fun onInterrupt() { /* nothing to interrupt */ }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        unregisterScreenReceiver()
        handler.removeCallbacks(periodicTask)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        unregisterScreenReceiver()
        handler.removeCallbacks(periodicTask)
        saveExecutor.shutdown()
        screenshotExecutor.shutdown()
        super.onDestroy()
    }
}
