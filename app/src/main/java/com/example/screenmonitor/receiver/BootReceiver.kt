package com.example.screenmonitor.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.screenmonitor.MainActivity
import com.example.screenmonitor.R
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.data.SecurityEventType
import com.example.screenmonitor.data.SecurityLogManager

import android.util.Log
import androidx.core.content.ContextCompat
import com.example.screenmonitor.service.ScreenCaptureService
import com.example.screenmonitor.service.ScreenMonitorAccessibilityService
import com.example.screenmonitor.service.WatchdogJobService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "استقبال إشارة إقلاع الجهاز: $action - تنشيط الحارس وبدء التشغيل التلقائي")

            // 1. جدولة حارس المراقبة فوراً
            WatchdogJobService.schedulePeriodicWatchdog(context)
            WatchdogAlarmReceiver.scheduleNextAlarm(context)

            val preferencesManager = PreferencesManager(context)
            if (preferencesManager.isMonitoringActive) {
                val isAccessEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        ScreenMonitorAccessibilityService.isServiceEnabled(context)

                // 2. إطلاق الخدمة تلقائياً وبشكل فوري
                val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    this.action = ScreenCaptureService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.i("BootReceiver", "تم إطلاق ScreenCaptureService بنجاح عند إقلاع الهاتف")
                } catch (t: Throwable) {
                    Log.e("BootReceiver", "تعذر تشغيل الخدمة الأمامية عند الإقلاع: ${t.message}")
                }

                // إذا لم تكن خدمة إمكانية الوصول مفعلة، نعرض إشعار الاستئناف لطلب إذن الشاشة
                if (!isAccessEnabled && ScreenCaptureService.savedResultData == null) {
                    preferencesManager.wasMonitoringBeforeReboot = true
                    showRebootNotification(context)
                }
            }
        }
    }

    private fun showRebootNotification(context: Context) {
        val preferencesManager = PreferencesManager(context)
        val isDiscreet = preferencesManager.isDiscreetNotificationEnabled

        val channelId = "screen_monitor_alerts_channel"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = if (isDiscreet) "تنبيهات حماية النظام" else "تنبيهات حالة المراقبة"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيهات عند توقف خدمة الحماية أو إعادة تشغيل الجهاز"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_RESUME_AFTER_REBOOT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isDiscreet) "تحديث حماية النظام" else "استئناف مراقبة الشاشة"
        val text = if (isDiscreet)
            "أُعيد تشغيل الهاتف. انقر لتأكيد استمرار حماية النظام."
        else
            "أُعيد تشغيل الهاتف وتوقفت المراقبة مؤقتاً. انقر لاستئناف المراقبة فوراً."

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shield_service)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_REBOOT, notification)
    }

    companion object {
        const val NOTIFICATION_ID_REBOOT = 2002
    }
}
