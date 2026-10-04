package com.example.screenmonitor.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.screenmonitor.data.PreferencesManager
import com.example.screenmonitor.service.ScreenCaptureService
import com.example.screenmonitor.service.WatchdogJobService

class WatchdogAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchdogAlarmReceiver"
        const val ACTION_WATCHDOG_HEARTBEAT = "com.example.screenmonitor.action.WATCHDOG_HEARTBEAT"
        const val ACTION_WAKEUP_WATCHDOG = "com.example.screenmonitor.action.WAKEUP_WATCHDOG"
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // كل 15 دقيقة
        private const val REQUEST_CODE = 9921

        fun scheduleNextAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, WatchdogAlarmReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_HEARTBEAT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ أثناء جدولة منبه Watchdog: ${e.message}")
            }
        }

        fun scheduleImmediateWakeup(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, WatchdogAlarmReceiver::class.java).apply {
                    action = ACTION_WAKEUP_WATCHDOG
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE + 1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = SystemClock.elapsedRealtime() + 1000L // ثانية واحدة
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "فشل جدولة الإحياء الفوري: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action
        Log.d(TAG, "استقبال إشارة منبه Watchdog: $action")

        // 1. إعادة جدولة النبضة التالية لضمان الديمومة للأبد
        scheduleNextAlarm(context)

        // 2. فحص خدمة المراقبة وإعادة إحيائها إن كانت متوقفة والمراقبة مفعلة
        val prefs = PreferencesManager(context)
        if (prefs.isMonitoringActive && !ScreenCaptureService.isMonitoringFlow.value) {
            Log.w(TAG, "منبه Watchdog: رصد توقف الخدمة أثناء التشغيل المفعل - إعادة التشغيل فوراً...")
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                this.action = ScreenCaptureService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (t: Throwable) {
                Log.e(TAG, "تعذر بدء الخدمة من AlarmReceiver: ${t.message}")
            }
        }

        // 3. تنشيط فحص JobService الاحتياطي
        WatchdogJobService.scheduleImmediateWatchdog(context)
    }
}
