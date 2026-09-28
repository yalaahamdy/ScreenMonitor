package com.example.screenmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.screenmonitor.MainActivity
import com.example.screenmonitor.R
import com.example.screenmonitor.data.InsufficientStorageException
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.data.ScreenshotRepository
import com.example.screenmonitor.data.SecurityEventType
import com.example.screenmonitor.data.SecurityLogManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var screenshotRepository: ScreenshotRepository
    private lateinit var securityLogManager: SecurityLogManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private var isDisplayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handleDisplayChanged()
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    onScreenTurnedOff()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    onScreenTurnedOn()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        screenshotRepository = ScreenshotRepository(this)
        securityLogManager = SecurityLogManager(this)
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ScreenMonitor::CaptureServiceCpuWakeLock"
            )
            try {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24h safe timeout
            } catch (e: Exception) {
                Log.w(TAG, "Error acquiring WakeLock: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, savedResultCode)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) ?: savedResultData
                } else {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: savedResultData)
                }

                if (resultCode != 0 && resultData != null) {
                    savedResultCode = resultCode
                    savedResultData = resultData
                    startMonitoring(resultCode, resultData)
                } else {
                    val accessibilityInstance = ScreenMonitorAccessibilityService.instance
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityInstance != null) {
                        startMonitoring(0, null)
                    } else {
                        stopMonitoring()
                    }
                }
            }
            ACTION_STOP -> {
                stopMonitoring()
            }
            ACTION_UPDATE_INTERVAL -> {
                if (preferencesManager.isMonitoringActive) {
                    startCaptureLoop()
                }
            }
            ACTION_UPDATE_NOTIFICATION -> {
                val notification = buildNotification("مراقبة الشاشة نشطة")
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
            else -> {
                if (preferencesManager.isMonitoringActive) {
                    if (savedResultCode != 0 && savedResultData != null) {
                        startMonitoring(savedResultCode, savedResultData)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ScreenMonitorAccessibilityService.instance != null) {
                        startMonitoring(0, null)
                    }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent?) {
        val notification = buildNotification("مراقبة الشاشة نشطة")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        if (resultCode != 0 && resultData != null) {
            savedResultCode = resultCode
            savedResultData = resultData
            initOrResumeMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
        }

        registerScreenReceiver()
        registerDisplayListener()

        preferencesManager.isMonitoringActive = true
        _isMonitoringFlow.value = true

        startCaptureLoop()
    }

    private fun initOrResumeMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            if (mediaProjection == null) {
                val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData) ?: return
                mediaProjection = projection
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post {
                            Log.w(TAG, "MediaProjection onStop called (screen lock or system pause)")
                            mediaProjection = null
                            virtualDisplay?.release()
                            virtualDisplay = null
                        }
                    }
                }, mainHandler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "initOrResumeMediaProjection error: ${e.message}")
        }
    }

    private fun registerDisplayListener() {
        if (!isDisplayListenerRegistered) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager?.registerDisplayListener(displayListener, mainHandler)
            isDisplayListenerRegistered = true
        }
    }

    private fun unregisterDisplayListener() {
        if (isDisplayListenerRegistered) {
            try {
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                displayManager?.unregisterDisplayListener(displayListener)
            } catch (_: Exception) {
            } finally {
                isDisplayListenerRegistered = false
            }
        }
    }

    private fun handleDisplayChanged() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val bounds = windowManager.maximumWindowMetrics.bounds
        val newWidth = bounds.width()
        val newHeight = bounds.height()
        val newDensityDpi = resources.displayMetrics.densityDpi

        val currentReader = imageReader ?: return
        if (currentReader.width != newWidth || currentReader.height != newHeight) {
            try {
                val oldReader = currentReader
                val newReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
                imageReader = newReader
                virtualDisplay?.resize(newWidth, newHeight, newDensityDpi)
                virtualDisplay?.surface = newReader.surface
                oldReader.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun registerScreenReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    this,
                    screenStateReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = powerManager?.isInteractive ?: true

            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val isDisplayOn = defaultDisplay == null || defaultDisplay.state == Display.STATE_ON

            isInteractive && isDisplayOn
        } catch (_: Exception) {
            false
        }
    }

    private fun onScreenTurnedOff() {
        _isScreenOnFlow.value = false
        drainImageReader()
    }

    private fun onScreenTurnedOn() {
        _isScreenOnFlow.value = true
        if (preferencesManager.isMonitoringActive) {
            if (captureJob == null || captureJob?.isActive == false) {
                startCaptureLoop()
            }
        }
    }

    private fun drainImageReader() {
        try {
            var image = imageReader?.acquireLatestImage()
            while (image != null) {
                image.close()
                image = imageReader?.acquireLatestImage()
            }
        } catch (_: Exception) {
        }
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val densityDpi = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenMonitorVirtualDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            delay(1000L)
            while (isActive) {
                if (!preferencesManager.isMonitoringActive) {
                    break
                }

                if (!isScreenOn()) {
                    drainImageReader()
                    delay(2500L)
                    continue
                }

                // Check Priority 1: Native Accessibility Service (Lock-Proof Engine)
                val accessibilityInstance = ScreenMonitorAccessibilityService.instance
                val isAccessibilityActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityInstance != null

                if (!isAccessibilityActive) {
                    // Priority 2: MediaProjection Engine
                    val code = savedResultCode
                    val data = savedResultData
                    if (mediaProjection == null && data != null && code != 0) {
                        try {
                            val newProj = mediaProjectionManager.getMediaProjection(code, data)
                            if (newProj != null) {
                                mediaProjection = newProj
                                newProj.registerCallback(object : MediaProjection.Callback() {
                                    override fun onStop() {
                                        mainHandler.post {
                                            Log.w(TAG, "MediaProjection onStop called (screen lock or system pause)")
                                            mediaProjection = null
                                            virtualDisplay?.release()
                                            virtualDisplay = null
                                        }
                                    }
                                }, mainHandler)
                                setupVirtualDisplay()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "MediaProjection renewal attempt: ${e.message}")
                        }
                    } else if (virtualDisplay == null || imageReader == null) {
                        if (mediaProjection != null) {
                            setupVirtualDisplay()
                        }
                    }
                }

                captureCurrentScreen()

                val intervalSeconds = preferencesManager.captureIntervalSeconds.coerceAtLeast(5)
                delay(intervalSeconds * 1000L)
            }
        }
    }

    private suspend fun captureCurrentScreen(): Boolean {
        if (!isScreenOn() || isAppInForeground) {
            drainImageReader()
            return false
        }

        // Priority 1: If ScreenMonitorAccessibilityService is active
        val accessibilityService = ScreenMonitorAccessibilityService.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityService != null) {
            val captured = captureViaAccessibility(accessibilityService)
            if (captured) return true
        }

        // Priority 2: Fallback to MediaProjection
        return captureViaMediaProjection()
    }

    private suspend fun captureViaAccessibility(accessibilityService: ScreenMonitorAccessibilityService): Boolean {
        return suspendCancellableCoroutine { continuation ->
            accessibilityService.captureDisplayScreenshot(
                onSuccess = { bitmap ->
                    serviceScope.launch {
                        try {
                            if (!isScreenOn() || isAppInForeground) {
                                bitmap.recycle()
                                if (continuation.isActive) continuation.resume(false)
                                return@launch
                            }
                            val retentionHours = preferencesManager.retentionHours
                            val autoClean = preferencesManager.isAutoCleanEnabled
                            val result = screenshotRepository.saveScreenshot(bitmap, retentionHours, autoClean)
                            bitmap.recycle()

                            if (result.isSuccess) {
                                val now = System.currentTimeMillis()
                                preferencesManager.lastCaptureTimeMillis = now
                                _lastCaptureTimeFlow.value = now
                                if (continuation.isActive) continuation.resume(true)
                            } else {
                                val exception = result.exceptionOrNull()
                                if (exception is InsufficientStorageException) {
                                    handleStorageFull()
                                }
                                if (continuation.isActive) continuation.resume(false)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                },
                onError = { error ->
                    Log.w(TAG, "Accessibility capture error: $error")
                    if (continuation.isActive) continuation.resume(false)
                }
            )
        }
    }

    private suspend fun captureViaMediaProjection(): Boolean {
        val reader = imageReader ?: return false
        var image = reader.acquireLatestImage()
        var retries = 0
        while (image == null && retries < 3 && isScreenOn()) {
            delay(500L)
            image = reader.acquireLatestImage()
            retries++
        }

        if (image == null || !isScreenOn() || isAppInForeground) {
            image?.close()
            return false
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width

            val rawBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            val cleanBitmap = if (rowPadding == 0) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                rawBitmap.recycle()
                cropped
            }

            if (!isScreenOn() || isAppInForeground) {
                cleanBitmap.recycle()
                return false
            }

            val retentionHours = preferencesManager.retentionHours
            val autoClean = preferencesManager.isAutoCleanEnabled
            val result = screenshotRepository.saveScreenshot(cleanBitmap, retentionHours, autoClean)
            cleanBitmap.recycle()

            if (result.isSuccess) {
                val now = System.currentTimeMillis()
                preferencesManager.lastCaptureTimeMillis = now
                _lastCaptureTimeFlow.value = now
                return true
            } else {
                val exception = result.exceptionOrNull()
                if (exception is InsufficientStorageException) {
                    handleStorageFull()
                    return false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
        return false
    }

    private fun handleStorageFull() {
        securityLogManager.logEvent(
            SecurityEventType.STORAGE_FULL,
            "توقف المراقبة لامتلاء مساحة التخزين",
            "تم إيقاف المراقبة تلقائياً لأن المساحة المتبقية على وحدة التخزين أقل من 50 ميجابايت."
        )
        showStorageAlertNotification()
        stopMonitoring()
    }

    private fun showStorageAlertNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("توقفت مراقبة الشاشة")
            .setContentText("المساحة المتبقية على التخزين غير كافية (أقل من 50MB). يرجى تحرير مساحة.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingOpenApp)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_STORAGE_ALERT, notification)
    }

    private fun stopMonitoring() {
        releaseWakeLock()
        savedResultCode = 0
        savedResultData = null

        unregisterScreenReceiver()
        unregisterDisplayListener()

        captureJob?.cancel()
        captureJob = null

        drainImageReader()

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        preferencesManager.isMonitoringActive = false
        preferencesManager.wasMonitoringBeforeReboot = false
        _isMonitoringFlow.value = false

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمات حماية وأمان النظام",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة خلفية مستمرة لأمان وحماية النظام"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val isDiscreet = preferencesManager.isDiscreetNotificationEnabled
        val title = if (isDiscreet) "خدمة حماية النظام" else "مراقبة الشاشة قيد التشغيل"
        val text = if (isDiscreet) "خدمة الأمان تعمل بشكل طبيعي في الخلفية" else contentText

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield_service)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"

        @Volatile
        var savedResultCode: Int = 0
            private set

        @Volatile
        var savedResultData: Intent? = null
            private set

        const val ACTION_START = "com.example.screenmonitor.action.START"
        const val ACTION_STOP = "com.example.screenmonitor.action.STOP"
        const val ACTION_UPDATE_INTERVAL = "com.example.screenmonitor.action.UPDATE_INTERVAL"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.screenmonitor.action.UPDATE_NOTIFICATION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_STORAGE_ALERT = 1002
        private const val CHANNEL_ID = "screen_monitor_service_channel"

        private val _isMonitoringFlow = MutableStateFlow(false)
        val isMonitoringFlow = _isMonitoringFlow.asStateFlow()

        private val _lastCaptureTimeFlow = MutableStateFlow(0L)
        val lastCaptureTimeFlow = _lastCaptureTimeFlow.asStateFlow()

        private val _isScreenOnFlow = MutableStateFlow(true)
        val isScreenOnFlow = _isScreenOnFlow.asStateFlow()

        @Volatile
        private var isAppInForeground = false

        fun setAppInForeground(inForeground: Boolean) {
            isAppInForeground = inForeground
        }
    }
}
