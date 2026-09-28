package com.example.screenmonitor

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.data.ScreenshotRepository
import com.example.screenmonitor.data.SecurityEventType
import com.example.screenmonitor.data.SecurityLogManager
import com.example.screenmonitor.data.SecurityManager
import com.example.screenmonitor.receiver.BootReceiver
import com.example.screenmonitor.service.ScreenCaptureService
import com.example.screenmonitor.theme.ScreenMonitorTheme
import com.example.screenmonitor.ui.screens.GalleryScreen
import com.example.screenmonitor.ui.screens.LockScreen
import com.example.screenmonitor.ui.screens.MainDashboardScreen

enum class ScreenState {
    DASHBOARD,
    GALLERY
}

class MainActivity : ComponentActivity() {

    private lateinit var securityManager: SecurityManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var screenshotRepository: ScreenshotRepository
    private lateinit var securityLogManager: SecurityLogManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isUnlocked by mutableStateOf(false)
    private var currentScreen by mutableStateOf(ScreenState.DASHBOARD)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "تم بدء المراقبة بنجاح", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "تم إلغاء الإذن من قبل المستخدم", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        launchScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securityManager = SecurityManager(this)
        preferencesManager = PreferencesManager(this)
        screenshotRepository = ScreenshotRepository(this)
        securityLogManager = SecurityLogManager(this)
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (preferencesManager.isAutoCleanEnabled && preferencesManager.retentionHours > 0) {
            screenshotRepository.cleanOldScreenshots(preferencesManager.retentionHours)
        }

        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            ScreenMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!isUnlocked) {
                        LockScreen(
                            securityManager = securityManager,
                            onUnlocked = {
                                isUnlocked = true
                                checkAndAutoResumeMonitoring()
                            }
                        )
                    } else {
                        when (currentScreen) {
                            ScreenState.DASHBOARD -> {
                                MainDashboardScreen(
                                    preferencesManager = preferencesManager,
                                    screenshotRepository = screenshotRepository,
                                    securityManager = securityManager,
                                    securityLogManager = securityLogManager,
                                    onStartMonitoringRequested = { requestStartMonitoring() },
                                    onStopMonitoringRequested = { stopMonitoring() },
                                    onOpenGallery = { currentScreen = ScreenState.GALLERY },
                                    onLockApp = { isUnlocked = false }
                                )
                            }
                            ScreenState.GALLERY -> {
                                BackHandler {
                                    currentScreen = ScreenState.DASHBOARD
                                }
                                GalleryScreen(
                                    screenshotRepository = screenshotRepository,
                                    onBack = { currentScreen = ScreenState.DASHBOARD }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isUnlocked) {
            checkAndAutoResumeMonitoring()
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenCaptureService.setAppInForeground(true)
        if (preferencesManager.isMonitoringActive && !ScreenCaptureService.isMonitoringFlow.value) {
            preferencesManager.wasMonitoringBeforeReboot = true
            securityLogManager.logEvent(
                SecurityEventType.UNEXPECTED_SERVICE_STOP,
                "توقف غير متوقع لخدمة المراقبة",
                "تم رصد إيقاف الخدمة بواسطة نظام التشغيل (بسبب إعادة تشغيل الهاتف أو توفير الطاقة) أثناء وقت المراقبة النشطة."
            )
            preferencesManager.isMonitoringActive = false
        }
    }

    override fun onPause() {
        super.onPause()
        ScreenCaptureService.setAppInForeground(false)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            isUnlocked = false
        }
    }

    private fun checkAndAutoResumeMonitoring() {
        val shouldResume = preferencesManager.wasMonitoringBeforeReboot ||
                intent.getBooleanExtra(EXTRA_RESUME_AFTER_REBOOT, false)
        if (shouldResume && !ScreenCaptureService.isMonitoringFlow.value) {
            preferencesManager.wasMonitoringBeforeReboot = false
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(BootReceiver.NOTIFICATION_ID_REBOOT)
            requestStartMonitoring()
        }
    }

    private fun requestStartMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "تعذر طلب التقاط الشاشة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoring() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        Toast.makeText(this, "تم إيقاف المراقبة", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_RESUME_AFTER_REBOOT = "extra_resume_after_reboot"
    }
}
