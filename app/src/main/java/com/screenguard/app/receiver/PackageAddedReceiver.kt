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

        // تجاهل تحديثات التطبيقات المثبتة مسبقاً
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val uri = intent.data ?: return
        val packageName = uri.schemeSpecificPart ?: return

        // تجاهل تطبيق ScreenGuard نفسه
        if (packageName == context.packageName) return

        // التحقق من تفعيل ميزة القفل التلقائي للتطبيقات الجديدة
        if (!PinManager.hasPin(context) || !PinManager.isAutoBlockNewApps(context)) {
            Log.d(TAG, "Auto-block for new apps is disabled; skipping package $packageName")
            return
        }

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem) return

            val pkgInfo = pm.getPackageInfo(packageName, 0)
            val guardInfo = pm.getPackageInfo(context.packageName, 0)
            if (pkgInfo.firstInstallTime < guardInfo.firstInstallTime) {
                return
            }

            val repo = AppRestrictionsRepository.getInstance(context)
            val existing = repo.getRestrictionForPackage(packageName)

            if (existing == null) {
                val appName = AppInfoManager.getInstance(context).getAppName(packageName).ifBlank {
                    try {
                        pm.getApplicationLabel(appInfo).toString()
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
