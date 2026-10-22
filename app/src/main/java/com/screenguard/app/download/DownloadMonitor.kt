package com.screenguard.app.download

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.screenguard.app.R
import com.screenguard.app.ScreenGuardAccessibilityService
import com.screenguard.app.Str
import com.screenguard.app.data.model.DownloadRecord
import com.screenguard.app.data.model.DownloadStatus
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.data.repository.DownloadLogRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * نظام مراقبة التحميلات الاحترافي والشامل
 * يرصد كافة عمليات تنزيل وحفظ الملفات في مجلد التنزيلات العام
 * ويقوم تلقائياً بتدمير وحذف الملفات فوراً إذا كان التطبيق المصدر محظوراً من التحميل
 */
object DownloadMonitor {

    private const val TAG = "DownloadMonitor"

    private var fileObserver: FileObserver? = null
    private var contentObserver: ContentObserver? = null
    private var isStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    // تتبع الملفات المعالجة حديثاً لمنع التكرار (مسار -> وقت)
    private val recentlyProcessed = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        start(context.applicationContext)
    }

    @Synchronized
    fun start(appContext: Context) {
        if (isStarted) return
        isStarted = true

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null && !downloadDir.exists()) {
            try {
                downloadDir.mkdirs()
            } catch (e: Exception) {}
        }

        startFileObserver(appContext, downloadDir)
        startContentObserver(appContext)
        Log.i(TAG, "DownloadMonitor started successfully on path: ${downloadDir?.absolutePath}")
    }

    private fun startFileObserver(appContext: Context, downloadDir: File?) {
        if (downloadDir == null || !downloadDir.exists()) return

        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE

        try {
            fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(downloadDir, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        handleFileEvent(appContext, downloadDir, event, path)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(downloadDir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        handleFileEvent(appContext, downloadDir, event, path)
                    }
                }
            }
            fileObserver?.startWatching()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FileObserver", e)
        }
    }

    private fun startContentObserver(appContext: Context) {
        try {
            val contentUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }

            contentObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    scanForRecentDownloads(appContext)
                }
            }
            appContext.contentResolver.registerContentObserver(contentUri, true, contentObserver!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ContentObserver", e)
        }
    }

    private fun handleFileEvent(appContext: Context, parentDir: File, event: Int, path: String?) {
        if (path.isNullOrBlank()) return

        val file = File(parentDir, path)
        workerExecutor.execute {
            processFileChange(appContext, file, event)
        }
    }

    private fun processFileChange(appContext: Context, file: File, event: Int) {
        if (!file.exists()) return

        val fileName = file.name
        val now = System.currentTimeMillis()

        // استخراج التطبيق النشط حالياً في الواجهة
        val activePkg = ScreenGuardAccessibilityService.instance?.lastForegroundApp ?: ""
        val repo = AppRestrictionsRepository.getInstance(appContext)
        val restriction = if (activePkg.isNotBlank()) repo.getRestrictionForPackage(activePkg) else null

        val isDownloadBlocked = restriction != null && restriction.isEnabled && restriction.blockDownloads

        // إذا كان التطبيق محظوراً من التنزيل: نحذف الملف فوراً!
        if (isDownloadBlocked) {
            val appName = AppInfoManager.getInstance(appContext).getAppName(activePkg)
            val fileSize = try { file.length() } catch (e: Exception) { 0L }

            try {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                Log.w(TAG, "Blocked and deleted download file: ${file.name} from app: $activePkg")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete blocked download file: ${file.name}", e)
            }

            // إشعار فوري وتنبيه للمستخدم بحظر التحميل
            mainHandler.post {
                val msg = Str.get(R.string.toast_download_blocked, appName.ifBlank { activePkg })
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }

            // تسجيل التحميل المحظور في السجل
            DownloadLogRepository.getInstance(appContext).logRecord(
                DownloadRecord(
                    fileName = fileName,
                    filePath = file.absolutePath,
                    fileSize = fileSize,
                    sourcePackage = activePkg,
                    sourceAppName = appName,
                    status = DownloadStatus.BLOCKED_DELETED
                )
            )
            return
        }

        // إذا لم يكن محظوراً: نتأكد من عدم تسجيل الملفات المؤقتة حتى يكتمل التحميل
        val lower = fileName.lowercase()
        val isTempFile = lower.endsWith(".crdownload") || lower.endsWith(".tmp") || lower.endsWith(".part")
        if (isTempFile) return

        // فحص منع التكرار خلال 3 ثوانٍ
        val lastSeen = recentlyProcessed[file.absolutePath] ?: 0L
        if (now - lastSeen < 3000L) return
        recentlyProcessed[file.absolutePath] = now

        // تنظيف القائمة المؤقتة دورياً
        if (recentlyProcessed.size > 100) {
            recentlyProcessed.clear()
        }

        val appName = if (activePkg.isNotBlank()) AppInfoManager.getInstance(appContext).getAppName(activePkg) else ""
        val fileSize = try { file.length() } catch (e: Exception) { 0L }

        DownloadLogRepository.getInstance(appContext).logRecord(
            DownloadRecord(
                fileName = fileName,
                filePath = file.absolutePath,
                fileSize = fileSize,
                sourcePackage = activePkg,
                sourceAppName = appName,
                status = DownloadStatus.ALLOWED
            )
        )
    }

    private fun scanForRecentDownloads(appContext: Context) {
        workerExecutor.execute {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir != null && downloadDir.exists()) {
                    val files = downloadDir.listFiles() ?: return@execute
                    val now = System.currentTimeMillis()
                    // فحص الملفات المعدلة خلال آخر 15 ثانية
                    for (f in files) {
                        if (f.isFile && (now - f.lastModified() < 15_000L)) {
                            processFileChange(appContext, f, FileObserver.CLOSE_WRITE)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning recent downloads", e)
            }
        }
    }
}
