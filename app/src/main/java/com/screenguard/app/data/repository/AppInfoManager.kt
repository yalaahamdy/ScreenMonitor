package com.screenguard.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * App info provider (labels, icons) with in-memory caches for
 * super-fast UI lookups. Use [getInstance] for the shared singleton.
 */
class AppInfoManager(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AppInfoManager? = null

        fun getInstance(context: Context): AppInfoManager {
            return instance ?: synchronized(this) {
                instance ?: AppInfoManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val packageManager: PackageManager = context.packageManager

    // Thread-safe caches: entries are written from IO coroutines AND the main
    // thread simultaneously (tab loading + RecyclerView binding). Plain HashMap
    // here caused ConcurrentModificationException crashes in v3.0.
    private val appLabelCache = ConcurrentHashMap<String, String>()
    private val appIconCache = ConcurrentHashMap<String, Drawable?>()
    private val cacheLock = Any()
    private val launcherPackages = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        loadLauncherPackages()
    }

    /**
     * جمع حزم التطبيقات التي يملكها المستخدم ولها واجهة تشغيل
     */
    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            for (info in resolveInfos) {
                launcherPackages.add(info.activityInfo.packageName)
            }
        } catch (e: Exception) {
            // التعامل في حال حدوث أي خطأ أمني أو نظامي
        }
    }

    /**
     * استرجاع الاسم المفهوم للتطبيق (مثلاً: واتساب بدل com.whatsapp)
     */
    fun getAppName(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val resolved = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // استخراج اسم معبر من الحزمة إن لم يكن التطبيق مثبتًا حاليًا
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            packageName
        }
        synchronized(cacheLock) {
            appLabelCache[packageName] = resolved
        }
        return resolved
    }

    /**
     * استرجاع أيقونة التطبيق مع تخزين مؤقت
     */
    fun getAppIcon(packageName: String): Drawable? {
        appIconCache[packageName]?.let { return it }
        val resolved = try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            try {
                ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            } catch (ex: Exception) {
                null
            }
        }
        synchronized(cacheLock) {
            appIconCache[packageName] = resolved
        }
        return resolved
    }

    /**
     * التحقق مما إذا كان التطبيق تطبيق واجهة مستخدم (Launcher app) أو نظام تفاعلي
     */
    fun isInteractiveApp(packageName: String): Boolean {
        // إذا كان يملك Launcher Activity فهو مؤكد تطبيق تفاعلي
        if (launcherPackages.contains(packageName)) return true
        
        // استبعاد حزم نظام أندرويد الخلفية الخالصة التي لا يتفاعل معها المستخدم
        val ignoredSystemPrefixes = listOf(
            "com.android.systemui",
            "android",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers",
            "com.android.settings.intelligence",
            "com.android.inputmethod",
            "com.google.android.inputmethod.latin"
        )
        return !ignoredSystemPrefixes.contains(packageName)
    }

    /**
     * جلب قائمة تطبيقات النظام الأساسية والتطبيقات المثبتة للمستخدم فقط، واستبعاد مئات الحزم الخلفية
     */
    fun getInstalledAppsList(): List<com.screenguard.app.data.model.AppUsageInfo> {
        val resultList = mutableListOf<com.screenguard.app.data.model.AppUsageInfo>()
        val seenPackages = mutableSetOf<String>()

        try {
            // 1. التطبيقات التي تمتلك أيقونة تشغيل في الهاتف (Launcher Apps)
            // يشمل كافة تطبيقات المستخدم وتطبيقات النظام التفاعلية الأساسية (الكاميرا، المعرض، الهاتف، الرسائل، الإعدادات، المتصفح...)
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherActivities = packageManager.queryIntentActivities(launcherIntent, 0)
            for (resolveInfo in launcherActivities) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName || seenPackages.contains(pkg)) continue
                seenPackages.add(pkg)

                val name = try {
                    resolveInfo.loadLabel(packageManager).toString()
                } catch (e: Exception) {
                    getAppName(pkg)
                }
                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (e: Exception) {
                    getAppIcon(pkg)
                }

                resultList.add(
                    com.screenguard.app.data.model.AppUsageInfo(
                        packageName = pkg,
                        appName = name,
                        icon = icon,
                        totalTimeForegroundMs = 0L,
                        launchCount = 0,
                        lastTimeUsed = 0L,
                        percentageOfTotal = 0f
                    )
                )
            }

            // 2. التحقق من تطبيقات المستخدم المثبتة فقط (Non-System User Apps) التي تملك واجهة تشغيل
            val installedApps = packageManager.getInstalledApplications(0)
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName
                if (pkg == context.packageName || seenPackages.contains(pkg)) continue

                val isUserInstalled = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0

                // فقط إذا كان تطبيقاً ثبته المستخدم ويملك واجهة تشغيل
                if (isUserInstalled) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        seenPackages.add(pkg)
                        val name = try {
                            packageManager.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            getAppName(pkg)
                        }
                        val icon = try {
                            packageManager.getApplicationIcon(appInfo)
                        } catch (e: Exception) {
                            getAppIcon(pkg)
                        }

                        resultList.add(
                            com.screenguard.app.data.model.AppUsageInfo(
                                packageName = pkg,
                                appName = name,
                                icon = icon,
                                totalTimeForegroundMs = 0L,
                                launchCount = 0,
                                lastTimeUsed = 0L,
                                percentageOfTotal = 0f
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // التعامل بأمان مع أي استثناء
        }

        return resultList.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }
}
