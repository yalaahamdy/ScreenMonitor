package com.screenguard.app.data.model

import androidx.annotation.StringRes
import com.screenguard.app.R
import com.screenguard.app.Str
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Status of a detected download operation. */
enum class DownloadStatus(@StringRes val titleRes: Int) {
    ALLOWED(R.string.download_status_allowed),
    BLOCKED_DELETED(R.string.download_status_blocked),
    INTERCEPTED(R.string.download_status_intercepted)
}

/** Record of a downloaded or blocked file. */
data class DownloadRecord(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val filePath: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "",
    val sourcePackage: String = "",
    val sourceAppName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: DownloadStatus = DownloadStatus.ALLOWED
) {

    fun getFormattedSize(): String {
        if (fileSize <= 0) return "0 B"
        val kb = fileSize / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$fileSize B"
        }
    }

    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("fileName", fileName)
            put("filePath", filePath)
            put("fileSize", fileSize)
            put("mimeType", mimeType)
            put("sourcePackage", sourcePackage)
            put("sourceAppName", sourceAppName)
            put("timestamp", timestamp)
            put("status", status.name)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): DownloadRecord {
            return DownloadRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                fileName = json.optString("fileName", ""),
                filePath = json.optString("filePath", ""),
                fileSize = json.optLong("fileSize", 0L),
                mimeType = json.optString("mimeType", ""),
                sourcePackage = json.optString("sourcePackage", ""),
                sourceAppName = json.optString("sourceAppName", ""),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                status = try {
                    DownloadStatus.valueOf(json.optString("status", DownloadStatus.ALLOWED.name))
                } catch (e: Exception) {
                    DownloadStatus.ALLOWED
                }
            )
        }
    }
}
