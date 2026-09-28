package com.example.screenmonitor.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class ScreenMonitorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityService"
        @Volatile var instance: ScreenMonitorAccessibilityService? = null
            private set
        @Volatile var isServiceRunning: Boolean = false
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expectedServiceName = "${context.packageName}/${ScreenMonitorAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        Log.i(TAG, "ScreenMonitorAccessibilityService متصل وجاهز لالتقاط الشاشة المحصن 24/7")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // لا نحتاج لمعالجة أحداث الواجهة، الغرض هو التقاط الشاشة النظامي المستقر
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenMonitorAccessibilityService تم مقاطعته")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning = false
        Log.i(TAG, "ScreenMonitorAccessibilityService تم تدميره")
    }

    fun captureDisplayScreenshot(
        onSuccess: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val executor = ContextCompat.getMainExecutor(applicationContext)
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                hardwareBuffer.close()

                                if (hardwareBitmap != null) {
                                    val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hardwareBitmap.recycle()
                                    onSuccess(softwareBitmap)
                                } else {
                                    onError("تعذر تحويل الـ HardwareBuffer إلى صورة Bitmap")
                                }
                            } catch (e: Exception) {
                                onError("خطأ أثناء معالجة لقطة الشاشة: ${e.message}")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val errorDesc = when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "صلاحية إمكانية الوصول غير مفعلة"
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "التقاط متكرر سريع جداً"
                                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "شاشة غير صالحة"
                                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "خطأ داخلي في نظام التشغيل"
                                else -> "رمز خطأ غير معروف: $errorCode"
                            }
                            Log.w(TAG, "فشل التقاط الشاشة عبر Accessibility: $errorDesc")
                            onError(errorDesc)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "استثناء أثناء استدعاء takeScreenshot: ${e.message}")
                onError(e.message ?: "خطأ غير متوقع")
            }
        } else {
            onError("ميزة التقاط إمكانية الوصول تتطلب أندرويد 11 أو أحدث")
        }
    }
}
