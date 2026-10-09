package com.screenguard.app.data.repository

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Build
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.DataUsageInfo
import com.screenguard.app.data.model.DataUsageOverview
import com.screenguard.app.data.model.UsagePeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * مستودع جلب وتحليل بيانات استهلاك الإنترنت (الواي فاي وبيانات الجوال)
 * يعتمد على NetworkStatsManager الرسمي لنظام أندرويد
 */
class DataUsageRepository(
    private val context: Context,
    private val appInfoManager: AppInfoManager
) {

    private val networkStatsManager: NetworkStatsManager? =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

    private val packageManager: PackageManager = context.packageManager

    /**
     * جلب ملخص استهلاك الإنترنت الشامل وقائمة التطبيقات المرتبة لفترة زمنية محددة
     */
    suspend fun getDataUsageOverview(period: UsagePeriod): DataUsageOverview = withContext(Dispatchers.IO) {
        if (networkStatsManager == null) {
            return@withContext DataUsageOverview(period = period)
        }

        val (startTime, endTime) = period.getTimeRange()

        // 1. جلب إجمالي استهلاك الجهاز عبر شبكة الواي فاي وشبكة الجوال
        val deviceWifi = queryDeviceSummary(NetworkCapabilities.TRANSPORT_WIFI, startTime, endTime)
        val deviceMobile = queryDeviceSummary(NetworkCapabilities.TRANSPORT_CELLULAR, startTime, endTime)

        // 2. تجميع استهلاك التطبيقات لكل UID
        val uidWifiMap = queryUidStats(NetworkCapabilities.TRANSPORT_WIFI, startTime, endTime)
        val uidMobileMap = queryUidStats(NetworkCapabilities.TRANSPORT_CELLULAR, startTime, endTime)

        // دمج جميع الـ UIDs النشطة
        val allUids = uidWifiMap.keys + uidMobileMap.keys

        // 3. تحويل الـ UIDs إلى تطبيقات وحزم
        val appUsageMap = mutableMapOf<String, TempAppUsage>()

        for (uid in allUids) {
            val wifiPair = uidWifiMap[uid] ?: Pair(0L, 0L)
            val mobilePair = uidMobileMap[uid] ?: Pair(0L, 0L)

            val totalBytes = wifiPair.first + wifiPair.second + mobilePair.first + mobilePair.second
            if (totalBytes <= 0L) continue

            val (pkgName, appName) = resolvePackageForUid(uid) ?: continue

            val existing = appUsageMap[pkgName]
            if (existing != null) {
                existing.wifiRx += wifiPair.first
                existing.wifiTx += wifiPair.second
                existing.mobileRx += mobilePair.first
                existing.mobileTx += mobilePair.second
            } else {
                appUsageMap[pkgName] = TempAppUsage(
                    packageName = pkgName,
                    appName = appName,
                    wifiRx = wifiPair.first,
                    wifiTx = wifiPair.second,
                    mobileRx = mobilePair.first,
                    mobileTx = mobilePair.second
                )
            }
        }

        val totalDeviceWifiRx = maxOf(deviceWifi.first, appUsageMap.values.sumOf { it.wifiRx })
        val totalDeviceWifiTx = maxOf(deviceWifi.second, appUsageMap.values.sumOf { it.wifiTx })
        val totalDeviceMobileRx = maxOf(deviceMobile.first, appUsageMap.values.sumOf { it.mobileRx })
        val totalDeviceMobileTx = maxOf(deviceMobile.second, appUsageMap.values.sumOf { it.mobileTx })

        val grandTotal = (totalDeviceWifiRx + totalDeviceWifiTx + totalDeviceMobileRx + totalDeviceMobileTx)

        val appList = appUsageMap.values.mapNotNull { temp ->
            val appTotal = temp.wifiRx + temp.wifiTx + temp.mobileRx + temp.mobileTx
            if (appTotal < 1024L) return@mapNotNull null // تجاهل الاستهلاك الضئيل جداً (< 1 KB)

            val percentage = if (grandTotal > 0) {
                ((appTotal.toDouble() / grandTotal.toDouble()) * 100).toFloat()
            } else 0f

            DataUsageInfo(
                packageName = temp.packageName,
                appName = temp.appName,
                icon = appInfoManager.getAppIcon(temp.packageName),
                wifiRxBytes = temp.wifiRx,
                wifiTxBytes = temp.wifiTx,
                mobileRxBytes = temp.mobileRx,
                mobileTxBytes = temp.mobileTx,
                percentageOfTotal = percentage
            )
        }.sortedByDescending { it.totalBytes }

        DataUsageOverview(
            period = period,
            totalWifiRxBytes = totalDeviceWifiRx,
            totalWifiTxBytes = totalDeviceWifiTx,
            totalMobileRxBytes = totalDeviceMobileRx,
            totalMobileTxBytes = totalDeviceMobileTx,
            appList = appList
        )
    }

    /**
     * جلب استهلاك الإنترنت لتطبيق معين بالتفصيل لفترة محددة
     */
    suspend fun getAppDataUsage(packageName: String, period: UsagePeriod): DataUsageInfo? = withContext(Dispatchers.IO) {
        if (networkStatsManager == null) return@withContext null

        val uid = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                packageManager.getPackageUid(packageName, 0)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0).uid
            }
        } catch (e: Exception) {
            -1
        }

        if (uid == -1) return@withContext null

        val (startTime, endTime) = period.getTimeRange()

        val (wifiRx, wifiTx) = queryUidDirectStats(NetworkCapabilities.TRANSPORT_WIFI, uid, startTime, endTime)
        val (mobileRx, mobileTx) = queryUidDirectStats(NetworkCapabilities.TRANSPORT_CELLULAR, uid, startTime, endTime)

        DataUsageInfo(
            packageName = packageName,
            appName = appInfoManager.getAppName(packageName),
            icon = appInfoManager.getAppIcon(packageName),
            wifiRxBytes = wifiRx,
            wifiTxBytes = wifiTx,
            mobileRxBytes = mobileRx,
            mobileTxBytes = mobileTx
        )
    }

    private fun queryDeviceSummary(networkType: Int, startTime: Long, endTime: Long): Pair<Long, Long> {
        if (networkStatsManager == null) return Pair(0L, 0L)
        return try {
            val bucket = networkStatsManager.querySummaryForDevice(networkType, null, startTime, endTime)
            Pair(bucket.rxBytes, bucket.txBytes)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    private fun queryUidStats(networkType: Int, startTime: Long, endTime: Long): Map<Int, Pair<Long, Long>> {
        val result = mutableMapOf<Int, TempBytes>()
        if (networkStatsManager == null) return emptyMap()

        try {
            val stats = networkStatsManager.queryDetails(networkType, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val uid = bucket.uid
                val existing = result[uid]
                if (existing != null) {
                    existing.rx += bucket.rxBytes
                    existing.tx += bucket.txBytes
                } else {
                    result[uid] = TempBytes(bucket.rxBytes, bucket.txBytes)
                }
            }
            stats.close()
        } catch (e: Exception) {
            // تجنب أي انهيار على أجهزة لا تدعم الاستعلام الكامل
        }

        return result.mapValues { (_, bytes) -> Pair(bytes.rx, bytes.tx) }
    }

    private fun queryUidDirectStats(networkType: Int, uid: Int, startTime: Long, endTime: Long): Pair<Long, Long> {
        if (networkStatsManager == null) return Pair(0L, 0L)
        var rx = 0L
        var tx = 0L
        try {
            val stats = networkStatsManager.queryDetailsForUid(networkType, null, startTime, endTime, uid)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                rx += bucket.rxBytes
                tx += bucket.txBytes
            }
            stats.close()
        } catch (e: Exception) {
            // صامت
        }
        return Pair(rx, tx)
    }

    private fun resolvePackageForUid(uid: Int): Pair<String, String>? {
        if (uid == 1000) {
            return Pair("android", Str.get(R.string.app_name_android_system))
        }

        val packages = try {
            packageManager.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        } ?: return null

        // نفضل التطبيقات التفاعلية للمستخدم إن وجدت بين الحزم المشتركة
        val selectedPkg = packages.firstOrNull { appInfoManager.isInteractiveApp(it) } ?: packages.firstOrNull() ?: return null
        val appName = appInfoManager.getAppName(selectedPkg)
        return Pair(selectedPkg, appName)
    }

    private data class TempBytes(var rx: Long, var tx: Long)

    private data class TempAppUsage(
        val packageName: String,
        val appName: String,
        var wifiRx: Long,
        var wifiTx: Long,
        var mobileRx: Long,
        var mobileTx: Long
    )
}
