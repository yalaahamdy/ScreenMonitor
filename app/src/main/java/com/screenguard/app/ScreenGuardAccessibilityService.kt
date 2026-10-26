package com.screenguard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import android.widget.Toast
import com.screenguard.app.data.model.isSettingsPackage
import com.screenguard.app.data.model.isSettingsPopupOrDialog as isSettingsPopupOrDialogTop
import com.screenguard.app.data.model.AppRestriction
import com.screenguard.app.data.model.DownloadRecord
import com.screenguard.app.data.model.DownloadStatus
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.data.repository.DownloadLogRepository
import com.screenguard.app.security.BootResilienceManager
import com.screenguard.app.service.BlockOverlayManager

/**
 * The unified heart of ScreenGuard.
 *
 * One accessibility service powering BOTH engines:
 *  1. Capture engine — periodic silent screenshots, capture on app-open and
 *     catch-up capture on screen wake (original ScreenGuard behaviour).
 *  2. Restrictions engine — zero-delay app blocking, Settings-app policy,
 *     anti-tamper / anti-uninstall interception, PiP detection and
 *     split-screen shield (ported from the usage-controller app).
 *
 * Runs as a specialUse foreground service with a persistent monitoring
 * notification that can never be hidden.
 */
class ScreenGuardAccessibilityService : AccessibilityService() {

    /** Wrap the service context so notifications follow the in-app language. */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    companion object {
        private const val TAG = "ScreenGuardSvc"

        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val BYPASS_POLL_MS = 1500L

        @Volatile
        var instance: ScreenGuardAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** True when the user enabled the service in system settings. */
        fun isEnabledInSettings(ctx: Context): Boolean {
            val expected = ComponentName(ctx, ScreenGuardAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabled)

            val expectedFull = expected.flattenToString()
            val expectedShort = expected.flattenToShortString()

            while (colonSplitter.hasNext()) {
                val component = colonSplitter.next()
                if (component.equals(expectedFull, ignoreCase = true) ||
                    component.equals(expectedShort, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun refreshSchedule() {
            instance?.scheduleNextCapture()
        }

        // Packages skipped by the capture engine
        private val CAPTURE_IGNORED_PREFIXES = listOf(
            "com.android.systemui",
            "com.screenguard.app",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.permissioncontroller"
        )

        // Packages skipped by the blocking engine
        private val BLOCK_IGNORED_SYSTEM = listOf(
            "com.android.systemui",
            "com.google.android.inputmethod",
            "com.samsung.android.honeyboard"
        )

        /** Key used in AppRestrictionsRepository for temporary global store/install bypass */
        const val INSTALL_LOCK_BYPASS_KEY = "screenguard_install_lock_bypass"

        /** Known official & third-party app stores blocked 100% when Install Lock is enabled */
        val APP_STORE_PACKAGES = setOf(
            "com.android.vending",
            "com.sec.android.app.samsungapps",
            "com.xiaomi.mipicks",
            "com.huawei.appmarket",
            "com.amazon.venezia",
            "com.heytap.market",
            "com.oppo.market",
            "com.bbk.appstore",
            "com.vivo.appstore",
            "com.aurora.store",
            "org.fdroid.fdroid",
            "com.apkpure.aegon",
            "cm.aptoide.pt",
            "com.uptodown",
            "com.taptap"
        )

        @Volatile
        private var selfUpdateAllowedUntil: Long = 0L

        fun allowSelfUpdateWindow(durationMs: Long = 180_000L) {
            selfUpdateAllowedUntil = System.currentTimeMillis() + durationMs
        }

        fun isSelfUpdateAllowed(): Boolean = System.currentTimeMillis() < selfUpdateAllowedUntil
    }

    private val handler = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    // ---- Capture engine state ----
    var lastForegroundApp: String? = null
        private set
    private var captureInFlight = false
    private var lastCaptureAttemptTime = 0L
    private var screenReceiverRegistered = false

    // v3.1: usage-time accumulator — counts minutes of ACTIVE use
    // (screen on + app in foreground); a capture fires only when the
    // configured usage duration has elapsed. Wall-clock time is ignored.
    private var activeUsageMinutes = 0L

    // ---- Restrictions engine state ----
    private var lastBlockedPackage: String? = null
    private var lastBlockTimestamp: Long = 0L
    private var lastToastPackage: String? = null
    private var lastToastTimestamp: Long = 0L
    private var lastHeartbeatTime: Long = 0L

    private val powerManager: PowerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keyguardManager: KeyguardManager by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val usageStatsManager: UsageStatsManager by lazy {
        getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    // ---------------------------------------------------------------------
    // Broadcast receivers
    // ---------------------------------------------------------------------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                // v3.1: wake capture is now an OPTIONAL trigger (off by default)
                val intervalMs = Prefs.intervalMinutes(this@ScreenGuardAccessibilityService).coerceAtLeast(1) * 60_000L
                val last = Prefs.lastCapture(this@ScreenGuardAccessibilityService)
                val now = System.currentTimeMillis()
                if (Prefs.captureOnWake(this@ScreenGuardAccessibilityService) &&
                    (last == 0L || (now - last) >= intervalMs)
                ) {
                    handler.postDelayed({
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm?.isInteractive != false) {
                            capture("wake")
                            scheduleNextCapture()
                        }
                    }, 1000L)
                }
            } else if (action == Intent.ACTION_SCREEN_OFF) {
                // Usage time does not accumulate while the screen is off
                activeUsageMinutes = 0
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                activeUsageMinutes = 0
                if (BlockOverlayManager.isShowing || lastBlockedPackage != null) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    BlockOverlayManager.dismiss()
                    lastBlockedPackage = null
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    private val periodicTask = object : Runnable {
        override fun run() {
            // v3.1: wall-clock periodic capture is now an OPTIONAL trigger
            if (Prefs.capturePeriodic(this@ScreenGuardAccessibilityService)) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isInteractive = pm?.isInteractive ?: true
                if (isInteractive) {
                    capture("periodic")
                }
            }
            scheduleNextCapture()
        }
    }

    /**
     * v3.1 PRIMARY capture trigger: a one-minute heartbeat that accumulates
     * ACTIVE usage time. A screenshot is taken only after the configured
     * usage duration has actually elapsed — never by wall-clock alone.
     */
    private val usageTickTask = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isInteractive = pm?.isInteractive ?: true
                val locked = keyguardManager.isKeyguardLocked

                if (isInteractive && !locked &&
                    Prefs.captureOnUsage(this@ScreenGuardAccessibilityService)
                ) {
                    val fg = lastForegroundApp
                    val trackable = fg != null &&
                            !CAPTURE_IGNORED_PREFIXES.any { fg.startsWith(it) } &&
                            !fg.contains("launcher", ignoreCase = true)

                    if (trackable) {
                        activeUsageMinutes += 1
                        val threshold = Prefs.usageCaptureMinutes(this@ScreenGuardAccessibilityService)
                            .coerceAtLeast(1).toLong()
                        if (activeUsageMinutes >= threshold) {
                            activeUsageMinutes = 0
                            capture("usage")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "usage tick failed", e)
            }
            handler.postDelayed(this, 60_000L)
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

        configureServiceInfo()
        registerScreenReceiver()
        registerScreenOffReceiver()

        lastForegroundApp = null
        scheduleNextCapture()
        handler.removeCallbacks(usageTickTask)
        handler.postDelayed(usageTickTask, 60_000L)

        // v3.1: the unconditional "initial capture" was removed —
        // no screenshot is ever taken without meeting the configured
        // usage-duration threshold (or an explicitly enabled trigger).

        // Restore services and resync active bypass timers after reboot
        BootResilienceManager.restoreServicesOnBoot(this)
        restoreActiveBypassTimers()

        Log.i(TAG, "Unified service connected — capture + restrictions active")
    }

    private fun configureServiceInfo() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 20
        }
        serviceInfo = info
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

    private fun registerScreenOffReceiver() {
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
    }

    private fun unregisterScreenOffReceiver() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {}
    }

    fun scheduleNextCapture() {
        handler.removeCallbacks(periodicTask)
        val minutes = Prefs.intervalMinutes(this).coerceAtLeast(1)
        handler.postDelayed(periodicTask, minutes * 60_000L)
    }

    // ---------------------------------------------------------------------
    // Unified event pipeline
    // ---------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // v3.1.1: the unified service runs 24/7 — any unexpected exception here
        // would kill the whole app process. Log and survive instead.
        try {
            handleAccessibilityEvent(event)
        } catch (t: Throwable) {
            CrashLogger.log(t, "AccessibilityService.onAccessibilityEvent")
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        val pkgName = event.packageName?.toString()

        // 0. Battery/privacy guard: skip everything while screen is off or locked
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            if (BlockOverlayManager.isShowing) {
                BlockOverlayManager.dismiss()
            }
            return
        }

        // 1. Security heartbeat (throttled)
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatTime > HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatTime = now
            PinManager.recordHeartbeat(this)
        }

        val isWindowStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isViewClicked = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED

        if (isWindowStateChange && pkgName != null && !isIgnoredSystemPackage(pkgName) && !isLauncherPackage(pkgName)) {
            lastForegroundApp = pkgName
        }

        // 1.0 Pre-launch launcher icon click interception (Zero-Open before app starts)
        if (isViewClicked && pkgName != null && isLauncherPackage(pkgName)) {
            if (interceptLauncherClick(event)) {
                return
            }
        }

        // 1.1 Check download prompts interception
        if (checkDownloadInterception(event, pkgName)) {
            return
        }

        // 1.2 Check package installer & app store installation/update interception
        if (checkInstallOrUpdateInterception(event, pkgName, isViewClicked, isWindowStateChange)) {
            return
        }

        // 2. Capture engine — runs only on window state changes
        if (isWindowStateChange && pkgName != null) {
            runCapturePipeline(event, pkgName)
        }

        // 3. Blocking engine
        runBlockingPipeline(event, pkgName, isWindowStateChange)
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.getDefault())
        return lower.contains("launcher") ||
                lower.contains("trebuchet") ||
                lower.contains("miui.home") ||
                lower.contains("oneui.home") ||
                lower.contains("oppo.launcher") ||
                lower.contains("bbk.launcher")
    }

    /**
     * اعتراض نقرة أيقونة التطبيق على الشاشة الرئيسية (Launcher) قبل فتحه من الأساس
     */
    private fun interceptLauncherClick(event: AccessibilityEvent): Boolean {
        val textBuilder = StringBuilder()
        event.text?.forEach { textBuilder.append(it).append(" ") }
        event.contentDescription?.let { textBuilder.append(it).append(" ") }
        val clickedLabel = textBuilder.toString().trim()
        if (clickedLabel.isBlank()) return false

        val restrictionsRepo = AppRestrictionsRepository.getInstance(this)
        val appInfo = AppInfoManager.getInstance(this)

        // 1. فحص ما إذا كانت الأيقونة المنقورة هي متجر تطبيقات مقفل
        if (PinManager.hasPin(this) && PinManager.isInstallLockEnabled(this) &&
            !restrictionsRepo.isPackageBypassed(INSTALL_LOCK_BYPASS_KEY)
        ) {
            for (storePkg in APP_STORE_PACKAGES) {
                if (restrictionsRepo.isPackageBypassed(storePkg)) continue
                val storeName = appInfo.getAppName(storePkg)
                if (storeName.isNotBlank() &&
                    (clickedLabel.equals(storeName, ignoreCase = true) ||
                     clickedLabel.contains(storeName, ignoreCase = true))
                ) {
                    kickAndTerminateBlockedApp(
                        targetPackage = storePkg,
                        toastMessage = Str.get(R.string.toast_store_blocked_open_screenguard, storeName)
                    )
                    return true
                }
            }
        }

        // 2. فحص ما إذا كانت الأيقونة المنقورة لتطبيق مقيد ومحظور حالياً
        val allRestrictions = restrictionsRepo.getAllRestrictions().filter { it.isEnabled }
        val calendar = Calendar.getInstance()
        for (restriction in allRestrictions) {
            for (pkg in restriction.allPackages) {
                if (restrictionsRepo.isPackageBypassed(pkg)) continue
                val name = appInfo.getAppName(pkg)
                if (name.isNotBlank() &&
                    (clickedLabel.equals(name, ignoreCase = true) ||
                     clickedLabel.startsWith("$name ", ignoreCase = true))
                ) {
                    val consumed = calculateConsumedMinutes(restriction, pkg)
                    val eval = restrictionsRepo.evaluateRestriction(restriction, consumed, calendar, pkg)
                    if (eval.isBlocked) {
                        kickAndTerminateBlockedApp(
                            targetPackage = pkg,
                            toastMessage = Str.get(
                                R.string.toast_app_blocked_open_screenguard,
                                name,
                                eval.detailedReasonText
                            )
                        )
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * إغلاق التطبيق المحظور فوراً من الأساس وإرجاع المستخدم للشاشة الرئيسية وقتل عمليته في الخلفية
     * بدون إظهار أي نوافذ عائمة فوق التطبيق لتفادي أي تهنيج
     */
    fun kickAndTerminateBlockedApp(targetPackage: String, toastMessage: String? = null) {
        if (BlockOverlayManager.isShowing) {
            BlockOverlayManager.dismiss()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)

        if (targetPackage.isNotBlank() && targetPackage != packageName) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.killBackgroundProcesses(targetPackage)
            } catch (_: Exception) {}
        }

        if (!toastMessage.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            if (lastToastPackage != targetPackage || (now - lastToastTimestamp) > 2500L) {
                lastToastPackage = targetPackage
                lastToastTimestamp = now
                handler.post {
                    Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Capture engine (ScreenGuard core)
    // ---------------------------------------------------------------------

    private fun runCapturePipeline(event: AccessibilityEvent, pkg: String) {
        // Tamper detection while the session is locked
        checkTamperAttempt(event, pkg)

        if (CAPTURE_IGNORED_PREFIXES.any { pkg.startsWith(it) }) return
        if (pkg.contains("launcher", ignoreCase = true)) return

        // Track the foreground app for the usage-time accumulator (always)
        if (pkg != lastForegroundApp) {
            lastForegroundApp = pkg

            // v3.1: capture-on-app-open is now an OPTIONAL trigger (off by default)
            if (Prefs.captureOnAppOpen(this)) {
                capture("app-open")
            }
        }
    }

    private fun checkTamperAttempt(event: AccessibilityEvent, pkg: String) {
        if (!PinManager.hasPin(this) || !Prefs.isSetupComplete(this) || PinManager.isSessionUnlocked()) {
            return
        }
        if (pkg.contains("settings", ignoreCase = true) || pkg.contains("packageinstaller", ignoreCase = true)) {
            val textBuilder = StringBuilder()
            event.text?.forEach { textBuilder.append(it).append(" ") }
            event.contentDescription?.let { textBuilder.append(it).append(" ") }
            val fullText = textBuilder.toString()
            val appName = getString(R.string.app_name)
            if (fullText.contains("ScreenGuard", ignoreCase = true) || (appName.isNotBlank() && fullText.contains(appName, ignoreCase = true))) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                val lockIntent = Intent(this, PinLockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(lockIntent)
            }
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

    // ---------------------------------------------------------------------
    // Restrictions engine (ported blocking pipeline)
    // ---------------------------------------------------------------------

    private fun runBlockingPipeline(event: AccessibilityEvent, pkgName: String?, isWindowStateChange: Boolean) {
        val restrictionsRepo = AppRestrictionsRepository.getInstance(this)

        // 1. Immediate Settings-app block with popup/dialog exemption
        if (pkgName != null && isSettingsPackage(pkgName)) {
            if (isEventSettingsPopup(event)) {
                return
            }

            val settingsRestriction = restrictionsRepo.getRestrictionForPackage(pkgName)
            if (settingsRestriction != null && settingsRestriction.isEnabled && !restrictionsRepo.isPackageBypassed(pkgName)) {
                val consumed = calculateConsumedMinutes(settingsRestriction, pkgName)
                val eval = restrictionsRepo.evaluateRestriction(settingsRestriction, consumed, Calendar.getInstance(), pkgName)
                if (eval.isBlocked) {
                    val appName = AppInfoManager.getInstance(this).getAppName(pkgName)
                    kickAndTerminateBlockedApp(
                        targetPackage = pkgName,
                        toastMessage = Str.get(
                            R.string.toast_app_blocked_open_screenguard,
                            appName,
                            eval.detailedReasonText
                        )
                    )
                    return
                }
            }
        }

        // 2. Anti-tamper / anti-uninstall interception
        if (pkgName != null && isAttemptingToTamperWithApp(pkgName, event)) {
            kickAndTerminateBlockedApp(
                targetPackage = pkgName,
                toastMessage = Str.get(R.string.toast_tamper_blocked)
            )
            return
        }

        // 3. PiP & Split-Screen check on windows-changed events
        if (!isWindowStateChange) {
            try {
                val windowList = windows ?: return
                for (w in windowList) {
                    val nodePkg = w.root?.packageName?.toString() ?: continue
                    if (isIgnoredSystemPackage(nodePkg)) continue
                    if (isWindowInPip(w) || isPackageVisibleInSplitScreen(nodePkg)) {
                        val restriction = restrictionsRepo.getRestrictionForPackage(nodePkg)
                        if (restriction != null && restriction.isEnabled && !restrictionsRepo.isPackageBypassed(nodePkg)) {
                            val consumed = calculateConsumedMinutes(restriction, nodePkg)
                            val eval = restrictionsRepo.evaluateRestriction(restriction, consumed, Calendar.getInstance(), nodePkg)
                            if (eval.isBlocked) {
                                val appName = AppInfoManager.getInstance(this).getAppName(nodePkg)
                                kickAndTerminateBlockedApp(
                                    targetPackage = nodePkg,
                                    toastMessage = Str.get(
                                        R.string.toast_app_blocked_open_screenguard,
                                        appName,
                                        eval.detailedReasonText
                                    )
                                )
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            return
        }

        // 4. Skip events without package or from system surfaces / keyboards
        if (pkgName == null || isIgnoredSystemPackage(pkgName)) {
            return
        }

        // 5. Evaluate and instantly kick/terminate the active app when restricted
        checkAndBlockIfNeeded(pkgName, event)
    }

    /**
     * اعتراض محاولات التنزيل والتحميل في التطبيقات التي تم تفعيل حظر التحميل لها
     */
    private fun checkDownloadInterception(event: AccessibilityEvent, pkgName: String?): Boolean {
        if (pkgName.isNullOrBlank()) return false
        val restrictionsRepo = AppRestrictionsRepository.getInstance(this)
        val restriction = restrictionsRepo.getRestrictionForPackage(pkgName) ?: return false
        if (!restriction.isEnabled || !restriction.blockDownloads) return false

        // لا نقوم بالحظر إذا كان هناك تخطي مؤقت نشط للتطبيق
        if (restrictionsRepo.isPackageBypassed(pkgName)) return false

        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        return try {
            val textList = mutableListOf<String>()
            collectNodeTexts(rootNode, textList)
            val eventTexts = mutableListOf<String>()
            event.text?.forEach { eventTexts.add(it.toString()) }
            event.contentDescription?.let { eventTexts.add(it.toString()) }

            val combined = (textList + eventTexts).joinToString(" ").lowercase(Locale.getDefault())

            val isDownloadPrompt = combined.contains("هل تريد تنزيل") ||
                    combined.contains("تنزيل الملف") ||
                    combined.contains("تنزيل على أي حال") ||
                    combined.contains("قد يكون هذا الملف ضاراً") ||
                    combined.contains("file might be harmful") ||
                    combined.contains("download file") ||
                    combined.contains("download anyway") ||
                    combined.contains("save file") ||
                    (combined.contains("download") && (combined.contains("again") || combined.contains("file")))

            if (isDownloadPrompt) {
                // إغلاق نافذة التنزيل فوراً
                performGlobalAction(GLOBAL_ACTION_BACK)

                val appName = AppInfoManager.getInstance(this).getAppName(pkgName)
                handler.post {
                    Toast.makeText(
                        this,
                        Str.get(R.string.toast_download_blocked, appName),
                        Toast.LENGTH_LONG
                    ).show()
                }

                // تسجيل المحاولة المحظورة في السجل
                DownloadLogRepository.getInstance(this).logRecord(
                    DownloadRecord(
                        fileName = "Blocked Download Prompt",
                        sourcePackage = pkgName,
                        sourceAppName = appName,
                        status = DownloadStatus.INTERCEPTED
                    )
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * حظر تثبيت أو تحديث أي تطبيق من المتجر أو مثبت الحزم 100% إلا بعد السماح من داخل ScreenGuard
     */
    private fun checkInstallOrUpdateInterception(
        event: AccessibilityEvent,
        pkgName: String?,
        isViewClicked: Boolean,
        isWindowStateChange: Boolean
    ): Boolean {
        if (pkgName.isNullOrBlank()) return false
        if (!PinManager.hasPin(this) || !PinManager.isInstallLockEnabled(this)) return false

        val restrictionsRepo = AppRestrictionsRepository.getInstance(this)
        if (restrictionsRepo.isPackageBypassed(INSTALL_LOCK_BYPASS_KEY)) return false

        // 1. App Stores — قفل كامل 100% لجميع متاجر التطبيقات فور محاولة فتحها
        if (APP_STORE_PACKAGES.contains(pkgName)) {
            if (restrictionsRepo.isPackageBypassed(pkgName)) {
                return false
            }
            val storeName = AppInfoManager.getInstance(this).getAppName(pkgName)
            kickAndTerminateBlockedApp(
                targetPackage = pkgName,
                toastMessage = Str.get(R.string.toast_store_blocked_open_screenguard, storeName)
            )
            return true
        }

        // 2. Package Installer (APK install / sideload / session installer / system updater)
        val lowerPkg = pkgName.lowercase(Locale.getDefault())
        val isInstaller = !lowerPkg.contains("permissioncontroller") && (
                lowerPkg == "com.android.packageinstaller" ||
                lowerPkg == "com.google.android.packageinstaller" ||
                lowerPkg == "com.samsung.android.packageinstaller" ||
                lowerPkg == "com.miui.packageinstaller" ||
                lowerPkg == "com.oplus.appinstaller" ||
                lowerPkg == "com.coloros.appinstaller" ||
                lowerPkg.contains("packageinstaller") ||
                lowerPkg.contains("appinstaller") ||
                lowerPkg.contains("sessioninstaller")
        )

        if (isInstaller && (isWindowStateChange || isViewClicked)) {
            // التحقق مما إذا كان إلغاء تثبيت لتطبيقنا (يتم معالجته في anti-tamper)
            if (isAttemptingToTamperWithApp(pkgName, event)) {
                return false
            }

            // السماح بالتحديث الذاتي لتطبيق ScreenGuard نفسه من داخل التطبيق
            if (isSelfUpdateAllowed()) {
                return false
            }

            if (restrictionsRepo.isPackageBypassed(pkgName)) {
                return false
            }

            // فحص ما إذا كان التثبيت خاصاً بتحديث ScreenGuard أو أي تطبيق آخر
            val rootNode = try { rootInActiveWindow } catch (e: Exception) { null }
            val isSelfUpdateScreen = if (rootNode != null) {
                try {
                    val textList = mutableListOf<String>()
                    collectNodeTexts(rootNode, textList)
                    val combined = textList.joinToString(" ").lowercase(Locale.getDefault())
                    val ourAppName = getString(R.string.app_name).lowercase(Locale.getDefault())
                    val mentionsUs = combined.contains("screenguard") || (ourAppName.isNotBlank() && combined.contains(ourAppName))
                    val isUninstall = combined.contains("uninstall") || combined.contains("إلغاء التثبيت") || combined.contains("إلغاء تثبيت")
                    mentionsUs && !isUninstall && isSelfUpdateAllowed()
                } catch (e: Exception) {
                    false
                } finally {
                    try { rootNode.recycle() } catch (_: Exception) {}
                }
            } else false

            if (isSelfUpdateScreen) {
                return false
            }

            // طرد فوري وإغلاق لمثبت الحزم ومنع تثبيت أي تطبيق خارجي 100%
            val installerLabel = Str.get(R.string.protection_install_lock)
            kickAndTerminateBlockedApp(
                targetPackage = pkgName,
                toastMessage = Str.get(R.string.toast_store_blocked_open_screenguard, installerLabel)
            )
            return true
        }

        return false
    }

    /**
     * Detect attempts to force-stop, clear data or uninstall this app via the
     * system Settings detail page or the package installer.
     */
    private fun isAttemptingToTamperWithApp(pkgName: String, event: AccessibilityEvent? = null): Boolean {
        if (!PinManager.hasPin(this)) {
            return false
        }

        // Settings popups are never tamper attempts
        if (event != null && isEventSettingsPopup(event)) {
            return false
        }

        val isInstaller = pkgName == "com.android.packageinstaller" ||
                pkgName == "com.google.android.packageinstaller" ||
                pkgName.contains("packageinstaller")

        val isSettings = isSettingsPackage(pkgName)

        if (!isInstaller && !isSettings) return false

        if (isInstaller && !PinManager.isAntiUninstallEnabled(this)) return false
        if (isSettings && !PinManager.isAntiTamperEnabled(this)) return false

        val rootNode = rootInActiveWindow ?: return false
        return try {
            val textList = mutableListOf<String>()
            collectNodeTexts(rootNode, textList)
            val combinedText = textList.joinToString(" ").lowercase(Locale.getDefault())

            val ourPkg = packageName.lowercase(Locale.getDefault())
            val ourAppName = getString(R.string.app_name).lowercase(Locale.getDefault())

            val mentionsOurApp = combinedText.contains(ourPkg) || combinedText.contains(ourAppName) ||
                    combinedText.contains("screenguard")
            if (!mentionsOurApp) return false

            val className = event?.className?.toString() ?: ""

            if (isInstaller) {
                // In the installer, only the uninstall screen counts
                val isUninstallScreen = combinedText.contains("uninstall") ||
                        combinedText.contains("إلغاء التثبيت") ||
                        combinedText.contains("إلغاء تثبيت")
                return isUninstallScreen
            }

            // In Settings: only protect the specific app-details page
            val isSpecificAppDetailsScreen = className.contains("InstalledAppDetails", ignoreCase = true) ||
                    className.contains("AppInfo", ignoreCase = true) ||
                    className.contains("AppDetails", ignoreCase = true) ||
                    className.contains("AppButtons", ignoreCase = true)

            if (!isSpecificAppDetailsScreen && (className.contains("ManageApplications", ignoreCase = true) ||
                className.contains("AccessibilitySettings", ignoreCase = true) ||
                (className.contains("SubSettings", ignoreCase = true) && !combinedText.contains("force stop") && !combinedText.contains("إيقاف إجباري")))
            ) {
                return false
            }

            val hasExplicitTamperAction = combinedText.contains("force stop") ||
                    combinedText.contains("إيقاف إجباري") ||
                    combinedText.contains("clear data") ||
                    combinedText.contains("مسح البيانات") ||
                    combinedText.contains("clear storage") ||
                    combinedText.contains("مسح التخزين") ||
                    combinedText.contains("uninstall") ||
                    combinedText.contains("إلغاء التثبيت")

            isSpecificAppDetailsScreen && hasExplicitTamperAction
        } catch (e: Exception) {
            false
        } finally {
            rootNode.recycle()
        }
    }

    /** Whether the event represents a Settings popup or dialog. */
    private fun isEventSettingsPopup(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()
        val pkgName = event.packageName?.toString()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        var winWidth = 0
        var winHeight = 0
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val rect = Rect()
                root.getBoundsInScreen(rect)
                winWidth = rect.width()
                winHeight = rect.height()
                root.recycle()
            }
        } catch (e: Exception) {}

        return isSettingsPopupOrDialogTop(
            className = className,
            packageName = pkgName,
            windowWidth = winWidth,
            windowHeight = winHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodeTexts(child, list)
                child.recycle()
            }
        }
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        if (pkg == packageName || pkg == "android") return true
        if (pkg.contains("inputmethod") || pkg.contains("permissioncontroller")) return true
        return BLOCK_IGNORED_SYSTEM.any { pkg.startsWith(it) }
    }

    private fun isSameAppOrSubComponent(currentPkg: String, newPkg: String): Boolean {
        if (currentPkg == newPkg) return true
        if (isSettingsPackage(currentPkg) && isSettingsPackage(newPkg)) {
            return true
        }
        return false
    }

    private fun checkAndBlockIfNeeded(targetPackage: String, event: AccessibilityEvent? = null) {
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            if (BlockOverlayManager.isShowing) {
                BlockOverlayManager.dismiss()
            }
            return
        }

        val restrictionsRepo = AppRestrictionsRepository.getInstance(this)

        val isSettings = isSettingsPackage(targetPackage)
        if (isSettings && event != null && isEventSettingsPopup(event)) {
            return
        }

        val restriction = restrictionsRepo.getRestrictionForPackage(targetPackage)
        if (restriction == null || !restriction.isEnabled) {
            if (lastBlockedPackage == targetPackage) {
                lastBlockedPackage = null
            }
            return
        }

        // Active temporary bypass for this package
        if (restrictionsRepo.isPackageBypassed(targetPackage)) {
            if (lastBlockedPackage == targetPackage) {
                lastBlockedPackage = null
            }
            return
        }

        val consumedMinutes = calculateConsumedMinutes(restriction, targetPackage)
        val calendar = Calendar.getInstance()
        val evaluation = restrictionsRepo.evaluateRestriction(restriction, consumedMinutes, calendar, targetPackage)

        if (evaluation.isBlocked) {
            lastBlockedPackage = targetPackage
            lastBlockTimestamp = System.currentTimeMillis()

            val appName = AppInfoManager.getInstance(this).getAppName(targetPackage)
            kickAndTerminateBlockedApp(
                targetPackage = targetPackage,
                toastMessage = Str.get(
                    R.string.toast_app_blocked_open_screenguard,
                    appName,
                    evaluation.detailedReasonText
                )
            )
        } else {
            if (lastBlockedPackage == targetPackage) {
                lastBlockedPackage = null
            }
        }
    }

    /** Extract the window bounds of the target app for precise split-screen coverage. */
    private fun getAppWindowBounds(targetPackage: String): Rect? {
        try {
            val windowList = windows
            for (w in windowList) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION || w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    val node = w.root
                    val nodePkg = node?.packageName?.toString()
                    if (nodePkg != null && (nodePkg == targetPackage || isSameAppOrSubComponent(targetPackage, nodePkg))) {
                        val rect = Rect()
                        w.getBoundsInScreen(rect)
                        if (rect.width() > 0 && rect.height() > 0) {
                            return rect
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    /** Reflection + geometry based PiP detection for a window. */
    private fun isWindowInPip(w: AccessibilityWindowInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val method = w.javaClass.methods.firstOrNull {
                it.name == "isInPictureInPictureMode" || it.name == "isInPictureInPicture"
            }
            if (method != null) {
                (method.invoke(w) as? Boolean) ?: false
            } else {
                val rect = Rect()
                w.getBoundsInScreen(rect)
                val metrics = resources.displayMetrics
                rect.width() > 0 && rect.height() > 0 &&
                        rect.width() < (metrics.widthPixels * 0.65f) &&
                        rect.height() < (metrics.heightPixels * 0.65f)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isPackageInPipMode(targetPackage: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val windowList = windows
                for (w in windowList) {
                    if (isWindowInPip(w)) {
                        val nodePkg = w.root?.packageName?.toString()
                        if (nodePkg != null && (nodePkg == targetPackage || isSameAppOrSubComponent(targetPackage, nodePkg))) {
                            return true
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return false
    }

    /** Whether the target app is still visible in an active split-screen layout. */
    private fun isPackageVisibleInSplitScreen(targetPackage: String): Boolean {
        return try {
            val windowList = windows ?: return false
            val metrics = resources.displayMetrics
            val screenHeight = metrics.heightPixels
            val screenWidth = metrics.widthPixels

            var splitWindowCount = 0
            var hasTargetOrNullRoot = false

            for (w in windowList) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val rect = Rect()
                    w.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0 &&
                        (rect.height() < (screenHeight * 0.88f) || rect.width() < (screenWidth * 0.88f))
                    ) {
                        splitWindowCount++
                        val p = w.root?.packageName?.toString()
                        if (p == null || p == targetPackage || isSameAppOrSubComponent(targetPackage, p)) {
                            hasTargetOrNullRoot = true
                        }
                    }
                }
            }
            splitWindowCount >= 2 && hasTargetOrNullRoot
        } catch (e: Exception) {
            false
        }
    }

    /** Schedule re-blocking when a temporary bypass expires. */
    fun scheduleBypassExpiration(targetPackage: String, durationMinutes: Int) {
        handler.postDelayed({
            AppRestrictionsRepository.getInstance(this).clearTemporaryBypass(targetPackage)
            if (lastForegroundApp == targetPackage) {
                checkAndBlockIfNeeded(targetPackage)
            }
        }, durationMinutes * 60_000L)
    }

    /** Restore active bypass timers after a reboot. */
    private fun restoreActiveBypassTimers() {
        try {
            val restrictionsRepo = AppRestrictionsRepository.getInstance(this)
            val restrictions = restrictionsRepo.getAllRestrictions()
            val allPackages = restrictions.flatMap { it.allPackages }.toSet()
            for (pkg in allPackages) {
                if (restrictionsRepo.isPackageBypassed(pkg)) {
                    val remainingSec = restrictionsRepo.getTemporaryBypassRemainingSeconds(pkg)
                    if (remainingSec > 0) {
                        handler.postDelayed({
                            restrictionsRepo.clearTemporaryBypass(pkg)
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            checkAndBlockIfNeeded(pkg)
                        }, remainingSec * 1000L)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun calculateConsumedMinutes(restriction: com.screenguard.app.data.model.AppRestriction, targetPackage: String? = null): Int {
        return AppRestrictionsRepository.getInstance(this).calculateConsumedMinutes(this, restriction, targetPackage)
    }

    override fun onInterrupt() { /* nothing to interrupt */ }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        unregisterScreenReceiver()
        unregisterScreenOffReceiver()
        handler.removeCallbacks(periodicTask)
        handler.removeCallbacks(usageTickTask)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        unregisterScreenReceiver()
        unregisterScreenOffReceiver()
        handler.removeCallbacks(periodicTask)
        handler.removeCallbacks(usageTickTask)
        saveExecutor.shutdown()
        screenshotExecutor.shutdown()
        super.onDestroy()
    }
}
