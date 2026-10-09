package com.screenguard.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.annotation.StringRes
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.AppRestriction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Import mode for backup files. */
enum class ImportMode(@StringRes val titleRes: Int, @StringRes val descriptionRes: Int) {
    MERGE(R.string.import_mode_merge_title, R.string.import_mode_merge_desc),
    REPLACE_ALL(R.string.import_mode_replace_title, R.string.import_mode_replace_desc)
}

/**
 * بيانات النسخة الاحتياطية الوصفية
 */
data class BackupMetadata(
    val app: String,
    val version: Int,
    val exportedAt: Long,
    val exportedAtFormatted: String,
    val restrictionsCount: Int,
    val hasSecuritySettings: Boolean
)

/**
 * نتيجة فحص والتحقق من صحة ملف النسخة الاحتياطية
 */
data class BackupValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val metadata: BackupMetadata? = null,
    val restrictions: List<AppRestriction> = emptyList(),
    val rawJson: JSONObject? = null
)

/**
 * نتيجة تنفيذ عملية الاستيراد
 */
data class ImportResult(
    val success: Boolean,
    val importedRestrictionsCount: Int = 0,
    val securitySettingsImported: Boolean = false,
    val message: String
)

/**
 * مستودع إدارة وتصدير واستيراد النسخ الاحتياطية لبيانات التطبيق وقيوده وإعداداته
 */
class BackupRepository(
    private val context: Context,
    private val restrictionsRepo: AppRestrictionsRepository,
    private val pinManager: PinManager
) {

    companion object {
        private const val BACKUP_APP_IDENTIFIER = "ScreenGuard"
        /** Legacy identifier accepted for backward compatibility with controller-app exports. */
        private const val LEGACY_BACKUP_APP_IDENTIFIER = "Muraqib"
        private const val CURRENT_SCHEMA_VERSION = 1
    }

    /**
     * تصدير بيانات التطبيق وقيوده وإعداداته إلى نص بصيغة JSON مهيأة ومنظمة
     */
    fun exportBackupJson(includeSecuritySettings: Boolean = true): String {
        val root = JSONObject()
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(now))

        root.put("app", BACKUP_APP_IDENTIFIER)
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("exportedAt", now)
        root.put("exportedAtFormatted", formattedDate)

        // 1. تصدير قائمة القيود والمجموعات
        val restrictions = restrictionsRepo.getAllRestrictions()
        val restrictionsArray = JSONArray()
        restrictions.forEach { restriction ->
            restrictionsArray.put(restriction.toJsonObject())
        }
        root.put("restrictionsCount", restrictions.size)
        root.put("restrictions", restrictionsArray)

        // 2. Export security settings when requested
        if (includeSecuritySettings) {
            val securityJson = JSONObject().apply {
                put("appLockEnabled", PinManager.isAppLockEnabled(context))
                put("antiTamperEnabled", PinManager.isAntiTamperEnabled(context))
                put("antiUninstallEnabled", PinManager.isAntiUninstallEnabled(context))
                put("securityQuestion", PinManager.getSecurityQuestion(context))
            }
            root.put("securitySettings", securityJson)
        }

        // 3. إضافة بصمة التجزئة SHA-256 لسلامة البيانات
        val contentForHash = restrictionsArray.toString()
        root.put("checksum", calculateSha256(contentForHash))

        return root.toString(2)
    }

    /**
     * إنشاء ملف نسخة احتياطية محلي في مجلد الكاش لمشاركته
     */
    fun createShareableBackupFile(includeSecuritySettings: Boolean = true): File {
        val backupsDir = File(context.cacheDir, "backups").apply {
            if (!exists()) mkdirs()
        }
        val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        val fileName = "screenguard_backup_${fileDateFormat.format(Date())}.json"
        val file = File(backupsDir, fileName)

        val jsonContent = exportBackupJson(includeSecuritySettings)
        FileOutputStream(file).use { out ->
            out.write(jsonContent.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        return file
    }

    /**
     * استخراج رابط URI آمن للمشاركة عبر FileProvider
     */
    fun getShareableBackupUri(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * فحص والتحقق من صحة ملف النسخة الاحتياطية قبل الاستيراد
     */
    fun validateBackupJson(jsonString: String): BackupValidationResult {
        if (jsonString.isBlank()) {
            return BackupValidationResult(isValid = false, errorMessage = Str.get(R.string.backup_error_empty))
        }

        try {
            val root = JSONObject(jsonString)

            val app = root.optString("app", "")
            if (app != BACKUP_APP_IDENTIFIER && app != LEGACY_BACKUP_APP_IDENTIFIER) {
                return BackupValidationResult(
                    isValid = false,
                    errorMessage = Str.get(R.string.backup_error_wrong_app)
                )
            }

            val schemaVersion = root.optInt("schemaVersion", 1)
            val exportedAt = root.optLong("exportedAt", 0L)
            val exportedAtFormatted = root.optString(
                "exportedAtFormatted",
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(exportedAt))
            )

            val restrictionsArray = root.optJSONArray("restrictions")
                ?: return BackupValidationResult(isValid = false, errorMessage = Str.get(R.string.backup_error_no_restrictions))

            val parsedRestrictions = mutableListOf<AppRestriction>()
            for (i in 0 until restrictionsArray.length()) {
                val item = restrictionsArray.optJSONObject(i)
                if (item != null) {
                    parsedRestrictions.add(AppRestriction.fromJsonObject(item))
                }
            }

            val hasSecuritySettings = root.has("securitySettings")

            val metadata = BackupMetadata(
                app = app,
                version = schemaVersion,
                exportedAt = exportedAt,
                exportedAtFormatted = exportedAtFormatted,
                restrictionsCount = parsedRestrictions.size,
                hasSecuritySettings = hasSecuritySettings
            )

            return BackupValidationResult(
                isValid = true,
                metadata = metadata,
                restrictions = parsedRestrictions,
                rawJson = root
            )
        } catch (e: Exception) {
            return BackupValidationResult(
                isValid = false,
                errorMessage = Str.get(R.string.backup_error_corrupt, e.localizedMessage ?: Str.get(R.string.error_unknown))
            )
        }
    }

    /**
     * استيراد البيانات والإعدادات بعد التحقق منها
     */
    fun importBackup(
        validationResult: BackupValidationResult,
        mode: ImportMode,
        importSecuritySettings: Boolean = true
    ): ImportResult {
        if (!validationResult.isValid || validationResult.metadata == null) {
            return ImportResult(
                success = false,
                message = validationResult.errorMessage ?: Str.get(R.string.backup_error_invalid)
            )
        }

        val restrictionsToImport = validationResult.restrictions
        var importedCount = 0

        when (mode) {
            ImportMode.REPLACE_ALL -> {
                restrictionsRepo.replaceAllRestrictions(restrictionsToImport)
                importedCount = restrictionsToImport.size
            }
            ImportMode.MERGE -> {
                importedCount = restrictionsRepo.mergeRestrictions(restrictionsToImport)
            }
        }

        // Import security settings when requested and present in the file
        var securityImported = false
        if (importSecuritySettings && validationResult.rawJson?.has("securitySettings") == true) {
            val secJson = validationResult.rawJson.optJSONObject("securitySettings")
            if (secJson != null) {
                if (secJson.has("appLockEnabled")) {
                    PinManager.setAppLockEnabled(context, secJson.optBoolean("appLockEnabled", true))
                }
                if (secJson.has("antiTamperEnabled")) {
                    PinManager.setAntiTamperEnabled(context, secJson.optBoolean("antiTamperEnabled", true))
                }
                if (secJson.has("antiUninstallEnabled")) {
                    PinManager.setAntiUninstallEnabled(context, secJson.optBoolean("antiUninstallEnabled", true))
                }
                securityImported = true
            }
        }

        return ImportResult(
            success = true,
            importedRestrictionsCount = importedCount,
            securitySettingsImported = securityImported,
            message = if (securityImported) {
                Str.get(R.string.backup_import_success_sec, importedCount)
            } else {
                Str.get(R.string.backup_import_success, importedCount)
            }
        )
    }

    private fun calculateSha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
