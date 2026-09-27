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
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.data.SecurityEventType
import com.example.screenmonitor.data.SecurityLogManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val preferencesManager = PreferencesManager(context)
            if (preferencesManager.isMonitoringActive) {
                val securityLogManager = SecurityLogManager(context)
                securityLogManager.logEvent(
                    SecurityEventType.UNEXPECTED_SERVICE_STOP,
                    "توقف المراقبة بعد إعادة تشغيل الهاتف",
                    "تمت إعادة تشغيل الجهاز أثناء فترة المراقبة النشطة. يتطلب نظام أندرويد إعادة فتح التطبيق لتأكيد بدء المراقبة."
                )
                preferencesManager.isMonitoringActive = false

                showRebootNotification(context)
            }
        }
    }

    private fun showRebootNotification(context: Context) {
        val channelId = "screen_monitor_alerts_channel"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "تنبيهات حالة المراقبة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيهات عند توقف خدمة المراقبة أو إعادة تشغيل الجهاز"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("توقفت مراقبة الشاشة")
            .setContentText("أُعيد تشغيل الهاتف. انقر هنا لاستئناف المراقبة فوراً.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_REBOOT, notification)
    }

    companion object {
        private const val NOTIFICATION_ID_REBOOT = 2002
    }
}
