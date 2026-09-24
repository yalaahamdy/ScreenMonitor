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
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.data.ScreenshotRepository
import com.example.screenmonitor.data.SecurityEventType
import com.example.screenmonitor.data.SecurityLogManager
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

class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var screenshotRepository: ScreenshotRepository
    private lateinit var securityLogManager: SecurityLogManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    onScreenTurnedOff()
                }
                Intent.ACTION_SCREEN_ON -> {
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    startMonitoring(resultCode, resultData)
                } else {
                    stopMonitoring()
                }
            }
            ACTION_STOP -> {
                stopMonitoring()
            }
            else -> {
                if (mediaProjection == null) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent) {
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

        try {
            val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                stopMonitoring()
                return
            }
            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        if (preferencesManager.isMonitoringActive) {
                            securityLogManager.logEvent(
                                SecurityEventType.PERMISSION_REVOKED,
                                "تم إيقاف أو سحب إذن التقاط الشاشة",
                                "تم رصد إيقاف جلسة مشاركة الشاشة أو سحب الإذن خارجياً أثناء فترة المراقبة النشطة (محاولة تعطيل أو تجاوز)."
                            )
                        }
                        stopMonitoring()
                    }
                }
            }, mainHandler)

            setupVirtualDisplay()
            registerScreenReceiver()

            preferencesManager.isMonitoringActive = true
            _isMonitoringFlow.value = true

            if (isScreenOn()) {
                startCaptureLoop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopMonitoring()
        }
    }

    private fun registerScreenReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
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
        captureJob?.cancel()
        captureJob = null
        drainImageReader()
    }

    private fun onScreenTurnedOn() {
        _isScreenOnFlow.value = true
        if (preferencesManager.isMonitoringActive && mediaProjection != null && imageReader != null) {
            startCaptureLoop()
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
            // Give the virtual display a moment to populate initial surface frames
            delay(1200L)
            while (isActive) {
                if (mediaProjection == null || imageReader == null) {
                    if (preferencesManager.isMonitoringActive) {
                        securityLogManager.logEvent(
                            SecurityEventType.PERMISSION_REVOKED,
                            "فشل التقاط اللقطة الدورية",
                            "تعذر التقاط الصورة الدورية لعدم توفر جلسة الشاشة أو إبطال إذن الالتقاط وقت موعد الالتقاط."
                        )
                    }
                    stopMonitoring()
                    break
                }

                if (!isScreenOn()) {
                    drainImageReader()
                    break
                }

                captureCurrentScreen()
                val intervalSeconds = preferencesManager.captureIntervalSeconds.coerceAtLeast(5)
                delay(intervalSeconds * 1000L)
            }
        }
    }

    private suspend fun captureCurrentScreen(): Boolean {
        if (!isScreenOn()) {
            drainImageReader()
            return false
        }

        val reader = imageReader ?: return false
        var image = reader.acquireLatestImage()
        var retries = 0
        while (image == null && retries < 3 && isScreenOn()) {
            delay(500L)
            image = reader.acquireLatestImage()
            retries++
        }

        if (image == null || !isScreenOn()) {
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

            if (!isScreenOn()) {
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
        return false
    }

    private fun stopMonitoring() {
        unregisterScreenReceiver()

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
        _isMonitoringFlow.value = false

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة مراقبة الشاشة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مستمر يوضح تشغيل التقاط الشاشة الدوري"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مراقبة الشاشة قيد التشغيل")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(android.R.drawable.ic_delete, "إيقاف المراقبة", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.screenmonitor.action.START"
        const val ACTION_STOP = "com.example.screenmonitor.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_monitor_service_channel"

        private val _isMonitoringFlow = MutableStateFlow(false)
        val isMonitoringFlow = _isMonitoringFlow.asStateFlow()

        private val _lastCaptureTimeFlow = MutableStateFlow(0L)
        val lastCaptureTimeFlow = _lastCaptureTimeFlow.asStateFlow()

        private val _isScreenOnFlow = MutableStateFlow(true)
        val isScreenOnFlow = _isScreenOnFlow.asStateFlow()
    }
}
