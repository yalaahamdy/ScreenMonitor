package com.screenguard.app.security

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.screenguard.app.PinManager
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.service.AppBlockerService
import java.util.Locale

/**
 * مدير ضمان استمرارية عمل التطبيق وخدماته بعد إعادة تشغيل الهاتف
 * يتولى فحص تحسينات البطارية، صلاحيات البدء التلقائي الخاصة بالشركات المصنعة (OEM)،
 * واستعادة المزامنة للمؤقتات والخدمات الخلفية.
 */
object BootResilienceManager {

    /**
     * التحقق مما إذا كان التطبيق مستثنى من قيود تحسين البطارية (Doze Mode)
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * طلب استثناء التطبيق من تحسينات البطارية لضمان عدم إيقافه أو تأخير عمله بعد الإقلاع
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {}
        }
    }

    /**
     * التحقق مما إذا كان الجهاز من تصنيع شركة تطبق إدارة خلفية صارمة (مثل شاومي، سامسونج، هواوي، أوبو)
     */
    fun isOemWithAggressiveBatteryManagement(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        return manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco") ||
                manufacturer.contains("huawei") ||
                manufacturer.contains("honor") ||
                manufacturer.contains("samsung") ||
                manufacturer.contains("oppo") ||
                manufacturer.contains("realme") ||
                manufacturer.contains("vivo") ||
                manufacturer.contains("oneplus") ||
                manufacturer.contains("meizu")
    }

    /**
     * استخراج نية (Intent) الانتقال لصفحة بدء التشغيل التلقائي (Autostart) الخاصة بالشركة المصنعة
     */
    fun getOemAutostartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        val intents = mutableListOf<Intent>()

        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                intents.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                intents.add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")))
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")))
            }
            manufacturer.contains("samsung") -> {
                intents.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")))
                intents.add(Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")))
                intents.add(Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")))
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
            }
            manufacturer.contains("vivo") -> {
                intents.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
                intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
            }
            manufacturer.contains("oneplus") -> {
                intents.add(Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")))
            }
        }

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }
        return null
    }

    /**
     * فتح صفحة إعدادات بدء التشغيل التلقائي الخاصة بالجهاز إن وجدت
     */
    fun openOemAutostartSettings(context: Context): Boolean {
        val intent = getOemAutostartIntent(context) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * التحقق من الخدمات واستعادتها فور إقلاع الهاتف أو استعادة العملية
     */
    fun restoreServicesOnBoot(context: Context) {
        try {
            val restrictionsRepo = AppRestrictionsRepository.getInstance(context)

            val hasActiveRestrictions = restrictionsRepo.getAllRestrictions().any { it.isEnabled }
            val hasActiveSecurity = PinManager.isAppLockEnabled(context) ||
                    PinManager.isAntiTamperEnabled(context) ||
                    PinManager.isAntiUninstallEnabled(context) ||
                    PinManager.isSafeModeProtectionEnabled(context)

            if (hasActiveRestrictions || hasActiveSecurity) {
                AppBlockerService.start(context)
            }
        } catch (e: Exception) {
            // Never throw
        }
    }
}
