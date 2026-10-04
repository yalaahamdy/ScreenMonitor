package com.example.screenmonitor.service

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.screenmonitor.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * خدمة حارس الإحياء الذاتي المستمر المدمجة بالنظام (Self-Healing JobService Watchdog)
 * تستخدم JobScheduler الأصلي لضمان فحص نبض المراقبة وإعادة إحيائها تلقائياً
 * ومقاومة قتل العمليات من أنظمة توفير الطاقة (OEM Task Killers) بدون أي تدخل يدوي.
 */
class WatchdogJobService : JobService() {

    companion object {
        private const val TAG = "WatchdogJobService"
        private const val JOB_ID = 8801
        private const val JOB_INTERVAL_MS = 15 * 60 * 1000L // كل 15 دقيقة

        fun schedulePeriodicWatchdog(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val component = ComponentName(context, WatchdogJobService::class.java)
                val builder = JobInfo.Builder(JOB_ID, component)
                    .setPeriodic(JOB_INTERVAL_MS)
                    .setPersisted(true)

                scheduler.schedule(builder.build())
                Log.i(TAG, "تمت جدولة حارس المراقبة الدوري بنجاح عبر JobScheduler")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ أثناء جدولة JobScheduler: ${e.message}")
            }
        }

        fun scheduleImmediateWatchdog(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val component = ComponentName(context, WatchdogJobService::class.java)
                val builder = JobInfo.Builder(JOB_ID + 1, component)
                    .setMinimumLatency(500L)
                    .setOverrideDeadline(2000L)

                scheduler.schedule(builder.build())
                Log.i(TAG, "تمت جدولة الفحص الفوري للحارس بنجاح")
            } catch (e: Exception) {
                Log.e(TAG, "فشل جدولة الفحص الفوري: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        serviceScope.launch {
            try {
                val prefs = PreferencesManager(applicationContext)
                val isMonitoringActive = prefs.isMonitoringActive
                val isFlowActive = ScreenCaptureService.isMonitoringFlow.value
                val isServiceAlive = isServiceRunning(ScreenCaptureService::class.java)

                Log.d(TAG, "فحص نبض خدمة المراقبة: active=$isMonitoringActive, flow=$isFlowActive, alive=$isServiceAlive")

                if (isMonitoringActive && (!isFlowActive || !isServiceAlive)) {
                    Log.w(TAG, "رصد توقف خدمة المراقبة - جاري الإحياء التلقائي الفوري...")
                    val intent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_START
                    }
                    try {
                        ContextCompat.startForegroundService(applicationContext, intent)
                    } catch (t: Throwable) {
                        Log.e(TAG, "فشل إعادة إطلاق الخدمة: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "خطأ أثناء فحص الحارس: ${t.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true // إعادة المحاولة إذا قوطعت المهمة
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
