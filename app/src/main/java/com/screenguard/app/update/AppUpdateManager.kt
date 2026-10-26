package com.screenguard.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.screenguard.app.BuildConfig
import com.screenguard.app.ScreenGuardAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionNumber: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long
)

/**
 * محرك التحديث التلقائي الاحترافي عبر GitHub Releases API
 * يفحص آخر إصدار منشور على المستودع الرسمي ويقوم بتنزيل ملف الـ APK وتثبيته مباشرةً
 */
object AppUpdateManager {

    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/yalaahamdy/ScreenMonitor/releases/latest"
    private const val PREF_LAST_AUTO_CHECK = "last_auto_update_check_ms"
    private const val AUTO_CHECK_COOLDOWN_MS = 3 * 60 * 60 * 1000L // 3 hours

    fun shouldRunAutoCheck(context: Context): Boolean {
        val sp = context.getSharedPreferences("screenguard_update_prefs", Context.MODE_PRIVATE)
        val last = sp.getLong(PREF_LAST_AUTO_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now - last < AUTO_CHECK_COOLDOWN_MS) return false
        sp.edit().putLong(PREF_LAST_AUTO_CHECK, now).apply()
        return true
    }

    suspend fun checkForUpdate(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ScreenGuard-Android/${BuildConfig.VERSION_NAME}")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("HTTP $code"))
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isBlank()) {
                return@withContext Result.success(null)
            }

            val remoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()

            if (!isNewerVersion(remoteVersion, currentVersion)) {
                return@withContext Result.success(null)
            }

            val releaseName = json.optString("name", tagName).ifBlank { tagName }
            val releaseNotes = json.optString("body", "").trim()

            var apkUrl = ""
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        apkUrl = downloadUrl
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) {
                return@withContext Result.success(null)
            }

            Result.success(
                ReleaseInfo(
                    tagName = tagName,
                    versionNumber = remoteVersion,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * مقارنة رقمية دقيقة للإصدارات الدلالية (Semantic Versioning مثل 3.4.0 مقابل 3.3.0)
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".", "-").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".", "-").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    suspend fun downloadUpdateApk(
        context: Context,
        releaseInfo: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            ScreenGuardAccessibilityService.allowSelfUpdateWindow(300_000L)

            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val targetFile = File(updatesDir, "ScreenGuard-${releaseInfo.versionNumber}.apk")
            if (targetFile.exists()) targetFile.delete()

            var currentUrl = releaseInfo.apkDownloadUrl
            var redirects = 0
            var conn: HttpURLConnection

            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ScreenGuard-Android/${BuildConfig.VERSION_NAME}")
                }
                val status = conn.responseCode
                if (status in 300..399 && redirects < 5) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = location
                        redirects++
                        continue
                    }
                }
                if (status !in 200..299) {
                    return@withContext Result.failure(Exception("HTTP $status"))
                }
                break
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: releaseInfo.apkSizeBytes
            var downloadedBytes = 0L
            var lastReportedPercent = -1

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        // فتح نافذة السماح بالتحديث الذاتي لمدة 3 دقائق حتى لا يعترضه قفل التثبيت
        ScreenGuardAccessibilityService.allowSelfUpdateWindow(180_000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                } catch (_: Exception) {}
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }
}
