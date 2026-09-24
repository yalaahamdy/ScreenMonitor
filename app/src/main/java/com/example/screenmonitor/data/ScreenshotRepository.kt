package com.example.screenmonitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ScreenshotItem(
    val file: File,
    val timestamp: Long,
    val sizeBytes: Long,
    val formattedDate: String,
    val formattedTime: String,
    val relativeTime: String,
    val groupHeader: String,
    val width: Int,
    val height: Int
)

data class StorageSummary(
    val totalCount: Int,
    val totalBytes: Long
)

class ScreenshotRepository(private val context: Context) {

    private val screenshotsDir: File by lazy {
        File(context.filesDir, "screenshots").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun hasSufficientStorage(): Boolean {
        return try {
            val stat = StatFs(screenshotsDir.absolutePath)
            val availableBytes = stat.availableBytes
            availableBytes >= MIN_REQUIRED_STORAGE_BYTES
        } catch (e: Exception) {
            true
        }
    }

    fun saveScreenshot(
        bitmap: Bitmap,
        retentionHours: Int = -1,
        autoClean: Boolean = true
    ): Result<File> {
        if (!hasSufficientStorage()) {
            return Result.failure(IOException("مساحة التخزين غير كافية لحفظ لقطة الشاشة"))
        }

        val now = System.currentTimeMillis()
        val filename = "screenshot_${fileDateFormat.format(Date(now))}.jpg"
        val targetFile = File(screenshotsDir, filename)

        return try {
            FileOutputStream(targetFile).use { out ->
                val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.flush()
                if (!success) {
                    targetFile.delete()
                    return Result.failure(IOException("فشل ضغط لقطة الشاشة"))
                }
            }

            if (autoClean && retentionHours > 0) {
                cleanOldScreenshots(retentionHours)
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            Result.failure(e)
        }
    }

    fun getScreenshots(): List<ScreenshotItem> {
        val files = screenshotsDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true))
        } ?: return emptyList()

        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val lastModified = file.lastModified()
                val dimensions = getImageDimensions(file)
                ScreenshotItem(
                    file = file,
                    timestamp = lastModified,
                    sizeBytes = file.length(),
                    formattedDate = dateFormat.format(Date(lastModified)),
                    formattedTime = timeFormat.format(Date(lastModified)),
                    relativeTime = getRelativeTimeSpan(lastModified),
                    groupHeader = getDateGroupHeader(lastModified),
                    width = dimensions.first,
                    height = dimensions.second
                )
            }
    }

    fun getImageDimensions(file: File): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    fun deleteScreenshot(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllScreenshots(): Int {
        val files = screenshotsDir.listFiles() ?: return 0
        var deletedCount = 0
        for (file in files) {
            if (file.isFile && file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    fun cleanOldScreenshots(retentionHours: Int): Int {
        if (retentionHours <= 0) return 0

        val threshold = System.currentTimeMillis() - (retentionHours * 3600 * 1000L)
        val files = screenshotsDir.listFiles() ?: return 0
        var deletedCount = 0

        for (file in files) {
            if (file.isFile && file.lastModified() < threshold) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    fun getStorageSummary(): StorageSummary {
        val files = screenshotsDir.listFiles { file -> file.isFile } ?: return StorageSummary(0, 0L)
        var totalBytes = 0L
        for (file in files) {
            totalBytes += file.length()
        }
        return StorageSummary(totalCount = files.size, totalBytes = totalBytes)
    }

    companion object {
        private const val MIN_REQUIRED_STORAGE_BYTES = 50 * 1024 * 1024L // 50 MB

        fun getRelativeTimeSpan(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                seconds < 30 -> "الآن"
                seconds < 60 -> "منذ $seconds ثانية"
                minutes == 1L -> "منذ دقيقة"
                minutes == 2L -> "منذ دقيقتين"
                minutes in 3..10 -> "منذ $minutes دقائق"
                minutes < 60 -> "منذ $minutes دقيقة"
                hours == 1L -> "منذ ساعة"
                hours == 2L -> "منذ ساعتين"
                hours in 3..10 -> "منذ $hours ساعات"
                hours < 24 -> "منذ $hours ساعة"
                days == 1L -> "أمس"
                days == 2L -> "منذ يومين"
                days in 3..10 -> "منذ $days أيام"
                else -> "منذ $days يوم"
            }
        }

        fun getDateGroupHeader(timestamp: Long): String {
            val nowCalendar = Calendar.getInstance()
            val itemCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }

            val nowYear = nowCalendar.get(Calendar.YEAR)
            val nowDayOfYear = nowCalendar.get(Calendar.DAY_OF_YEAR)

            val itemYear = itemCalendar.get(Calendar.YEAR)
            val itemDayOfYear = itemCalendar.get(Calendar.DAY_OF_YEAR)

            return if (nowYear == itemYear) {
                when (nowDayOfYear - itemDayOfYear) {
                    0 -> "اليوم"
                    1 -> "أمس"
                    in 2..7 -> "هذا الأسبوع"
                    else -> SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ar")).format(Date(timestamp))
                }
            } else {
                SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ar")).format(Date(timestamp))
            }
        }
    }
}
