package com.screenguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.AppRestriction
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.AppRestrictionsRepository

/**
 * مستقبل إعلانات تثبيت التطبيقات الجديدة على الجهاز
 * يقوم فوراً بقفل أي تطبيق جديد يتم تثبيته وحظره بالكامل برمز مرور الوالدين
 * حتى يراجعه ولي الأمر ويوافق عليه أو يحدد قيود استخدامه
 */
class PackageAddedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageAddedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_PACKAGE_ADDED) return

        val uri = intent.data ?: return
        val packageName = uri.schemeSpecificPart ?: return

        // تجاهل تطبيق ScreenGuard نفسه
        if (packageName == context.packageName) return

        // التحقق من تفعيل ميزة القفل التلقائي للتطبيقات الجديدة
        if (!PinManager.isAutoBlockNewApps(context)) {
            Log.d(TAG, "Auto-block for new apps is disabled; skipping package $packageName")
            return
        }

        try {
            val repo = AppRestrictionsRepository.getInstance(context)
            val existing = repo.getRestrictionForPackage(packageName)

            if (existing == null) {
                val appName = AppInfoManager.getInstance(context).getAppName(packageName).ifBlank {
                    try {
                        val pm = context.packageManager
                        val info = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(info).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                }

                // إنشاء قيد حظر كامل فوري للتطبيق الجديد مع حظر التحميلات أيضاً
                val newRestriction = AppRestriction(
                    packageName = packageName,
                    appName = appName,
                    targetPackages = listOf(packageName),
                    isEnabled = true,
                    isTotalBlock = true, // مقفل بالكامل لحين موافقة ولي الأمر
                    blockDownloads = true
                )

                repo.saveRestriction(newRestriction)
                Log.i(TAG, "Automatically locked new app: $appName ($packageName)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to auto-lock package $packageName", t)
        }
    }
}
