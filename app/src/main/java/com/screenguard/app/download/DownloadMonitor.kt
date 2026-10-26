package com.screenguard.app.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.ScreenGuardAccessibilityService
import com.screenguard.app.Str
import com.screenguard.app.data.model.DownloadRecord
import com.screenguard.app.data.model.DownloadStatus
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.data.repository.DownloadLogRepository
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * نظام مراقبة التحميلات الشامل على مستوى الجهاز بالكامل (v3.4.0)
 *
 * يجمع بين 3 طبقات رصد متزامنة:
 *  1. ContentObserver + استعلامات MediaStore الشاملة (Downloads, Files, Images, Video, Audio)
 *     مع قراءة OWNER_PACKAGE_NAME لمعرفة التطبيق الذي قام بالتحميل بدقة.
 *  2. FileObserver متعدد المجلدات (Download ومجلداته الفرعية، Documents، Pictures، Movies، Music، DCIM).
 *  3. مستقبل إشعارات نظام التحميل (DownloadManager.ACTION_DOWNLOAD_COMPLETE).
 *
 * كما يقوم بحظر وحذف أي ملف حزمة تطبيق (.apk, .xapk, .apks, .apkm, .aab) فوراً بنسبة 100%
 * طالما أن قفل تثبيت التطبيقات مفعّل ولم يتم السماح به مؤقتاً من داخل ScreenGuard.
 */
object DownloadMonitor {

    private const val TAG = "DownloadMonitor"

    private val fileObservers = mutableListOf<FileObserver>()
    private var contentObserver: ContentObserver? = null
    private var downloadCompleteReceiverRegistered = false
    private var isStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    // تتبع الملفات المعالجة حديثاً لمنع التكرار (مفتاح الملف -> وقت الرصد)
    private val recentlyProcessed = ConcurrentHashMap<String, Long>()

    private val APK_EXTENSIONS = listOf(".apk", ".xapk", ".apks", ".apkm", ".aab")

    fun init(context: Context) {
        start(context.applicationContext)
    }

    @Synchronized
    fun start(appContext: Context) {
        if (isStarted) return
        isStarted = true

        startMultiDirectoryFileObservers(appContext)
        startMediaStoreObservers(appContext)
        registerDownloadManagerReceiver(appContext)

        Log.i(TAG, "Device-wide DownloadMonitor started successfully")
    }

    private fun startMultiDirectoryFileObservers(appContext: Context) {
        val publicDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        )

        val dirsToWatch = mutableSetOf<File>()
        for (dir in publicDirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                if (dir.exists() && dir.isDirectory) {
                    dirsToWatch.add(dir)
                    // مراقبة المجلدات الفرعية المباشرة داخل Download مثل Download/Telegram وغيرها
                    dir.listFiles()?.filter { it.isDirectory }?.take(15)?.forEach { sub ->
                        dirsToWatch.add(sub)
                    }
                }
            } catch (_: Exception) {}
        }

        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE

        for (dir in dirsToWatch) {
            try {
                val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            handleFileEvent(appContext, dir, event, path)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(dir.absolutePath, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            handleFileEvent(appContext, dir, event, path)
                        }
                    }
                }
                obs.startWatching()
                fileObservers.add(obs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to watch directory ${dir.absolutePath}", e)
            }
        }
    }

    private fun startMediaStoreObservers(appContext: Context) {
        try {
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    queryRecentMediaStoreDownloads(appContext, uri)
                    scanRecentPublicFiles(appContext)
                }
            }
            contentObserver = observer

            val resolver = appContext.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer)
            }
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MediaStore ContentObservers", e)
        }
    }

    private fun registerDownloadManagerReceiver(appContext: Context) {
        if (downloadCompleteReceiverRegistered) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != -1L) {
                        workerExecutor.execute {
                            inspectDownloadManagerEntry(appContext, id)
                        }
                    }
                    queryRecentMediaStoreDownloads(appContext, null)
                    scanRecentPublicFiles(appContext)
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            downloadCompleteReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register DownloadManager receiver", e)
        }
    }

    private fun inspectDownloadManagerEntry(appContext: Context, downloadId: Long) {
        try {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            val query = DownloadManager.Query().setFilterById(downloadId)
            dm.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                        val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                        val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val sizeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val mimeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)

                        val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "" else ""
                        val localUriStr = if (uriIdx >= 0) cursor.getString(uriIdx) ?: "" else ""
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""

                        val filePath = if (localUriStr.startsWith("file://")) {
                            Uri.parse(localUriStr).path ?: localUriStr
                        } else localUriStr

                        val fileName = title.ifBlank { File(filePath).name.ifBlank { "download_$downloadId" } }
                        evaluateAndRecordDownload(
                            appContext = appContext,
                            fileName = fileName,
                            filePath = filePath,
                            fileSize = size,
                            mimeType = mime,
                            ownerPackage = null,
                            contentUri = dm.getUriForDownloadedFile(downloadId),
                            fileObj = if (filePath.startsWith("/")) File(filePath) else null,
                            onDeleteExtra = {
                                try { dm.remove(downloadId) } catch (_: Exception) {}
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "inspectDownloadManagerEntry failed", e)
        }
    }

    private fun queryRecentMediaStoreDownloads(appContext: Context, triggeredUri: Uri?) {
        workerExecutor.execute {
            val urisToQuery = mutableListOf<Uri>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                urisToQuery.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            urisToQuery.add(MediaStore.Files.getContentUri("external"))

            val nowSec = System.currentTimeMillis() / 1000L
            val minTimestampSec = nowSec - 45L

            for (baseUri in urisToQuery) {
                try {
                    val projection = mutableListOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.DATA
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
                        projection.add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                        projection.add(MediaStore.MediaColumns.IS_PENDING)
                    }

                    val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
                    val selectionArgs = arrayOf(minTimestampSec.toString(), minTimestampSec.toString())
                    val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

                    appContext.contentResolver.query(
                        baseUri,
                        projection.toTypedArray(),
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        var count = 0
                        while (cursor.moveToNext() && count < 20) {
                            count++
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: continue
                            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)).coerceAtLeast(0L)
                            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""
                            val dataPath = try {
                                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) ?: ""
                            } catch (_: Exception) { "" }

                            var relativePath = ""
                            var ownerPkg: String? = null
                            var isPending = 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                if (relIdx >= 0) relativePath = cursor.getString(relIdx) ?: ""
                                val ownerIdx = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                                if (ownerIdx >= 0) ownerPkg = cursor.getString(ownerIdx)
                                val pendIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                                if (pendIdx >= 0) isPending = cursor.getInt(pendIdx)
                            }

                            if (isPending == 1) continue
                            if (ownerPkg == appContext.packageName) continue

                            // نتجاهل ملفات النظام المؤقتة غير المهمة
                            if (name.startsWith(".pending-") || name.endsWith(".tmp") || name.endsWith(".crdownload") || name.endsWith(".part")) {
                                continue
                            }

                            val resolvedPath = dataPath.ifBlank {
                                if (relativePath.isNotBlank()) "$relativePath$name" else name
                            }
                            val itemUri = ContentUris.withAppendedId(baseUri, id)
                            val fileObj = if (dataPath.isNotBlank()) File(dataPath) else null

                            evaluateAndRecordDownload(
                                appContext = appContext,
                                fileName = name,
                                filePath = resolvedPath,
                                fileSize = size,
                                mimeType = mime,
                                ownerPackage = ownerPkg,
                                contentUri = itemUri,
                                fileObj = fileObj
                            )
                        }
                    }
                } catch (e: Exception) {
                    // قد يفشل الاستعلام لبعض الجداول حسب صلاحيات النظام؛ نكمل بدون توقف
                }
            }
        }
    }

    private fun handleFileEvent(appContext: Context, parentDir: File, event: Int, path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(parentDir, path)
        workerExecutor.execute {
            if (file.isDirectory && (event and FileObserver.CREATE) != 0) {
                // إذا تم إنشاء مجلد فرعي جديد داخل Download، نراقبه أيضاً
                watchSingleDirectory(appContext, file)
            } else {
                processFileObject(appContext, file)
            }
        }
    }

    private fun watchSingleDirectory(appContext: Context, dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        try {
            val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        handleFileEvent(appContext, dir, event, path)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        handleFileEvent(appContext, dir, event, path)
                    }
                }
            }
            obs.startWatching()
            synchronized(fileObservers) {
                fileObservers.add(obs)
            }
        } catch (_: Exception) {}
    }

    private fun processFileObject(appContext: Context, file: File) {
        if (!file.exists() || file.isDirectory) return
        val fileName = file.name
        val lower = fileName.lowercase(Locale.getDefault())
        if (lower.startsWith(".pending-") || lower.endsWith(".crdownload") || lower.endsWith(".tmp") || lower.endsWith(".part")) {
            return
        }

        val size = try { file.length() } catch (_: Exception) { 0L }
        evaluateAndRecordDownload(
            appContext = appContext,
            fileName = fileName,
            filePath = file.absolutePath,
            fileSize = size,
            mimeType = "",
            ownerPackage = null,
            contentUri = null,
            fileObj = file
        )
    }

    private fun evaluateAndRecordDownload(
        appContext: Context,
        fileName: String,
        filePath: String,
        fileSize: Long,
        mimeType: String,
        ownerPackage: String?,
        contentUri: Uri?,
        fileObj: File?,
        onDeleteExtra: (() -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        val dedupKey = "${fileName.lowercase(Locale.getDefault())}|$fileSize"
        val lastSeen = recentlyProcessed[dedupKey] ?: 0L
        if (now - lastSeen < 3500L) return
        recentlyProcessed[dedupKey] = now

        if (recentlyProcessed.size > 200) {
            recentlyProcessed.clear()
        }

        val activePkg = ownerPackage?.takeIf { it.isNotBlank() }
            ?: ScreenGuardAccessibilityService.instance?.lastForegroundApp
            ?: ""

        if (activePkg == appContext.packageName) return

        val repo = AppRestrictionsRepository.getInstance(appContext)
        val appInfo = AppInfoManager.getInstance(appContext)
        val appName = if (activePkg.isNotBlank()) appInfo.getAppName(activePkg) else ""

        // 1. فحص ما إذا كان الملف المحمل هو حزمة تثبيت تطبيقات (.apk / .xapk / .apks / .apkm / .aab)
        val lowerName = fileName.lowercase(Locale.getDefault())
        val isApkPackage = APK_EXTENSIONS.any { lowerName.endsWith(it) } ||
                mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)

        val installLockActive = PinManager.hasPin(appContext) &&
                PinManager.isInstallLockEnabled(appContext) &&
                !ScreenGuardAccessibilityService.isSelfUpdateAllowed() &&
                !repo.isPackageBypassed(ScreenGuardAccessibilityService.INSTALL_LOCK_BYPASS_KEY) &&
                (activePkg.isBlank() || !repo.isPackageBypassed(activePkg))

        if (isApkPackage && installLockActive) {
            deleteDownloadedFile(appContext, contentUri, fileObj, onDeleteExtra)
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    Str.get(R.string.toast_apk_blocked_deleted, fileName),
                    Toast.LENGTH_LONG
                ).show()
            }
            DownloadLogRepository.getInstance(appContext).logRecord(
                DownloadRecord(
                    fileName = fileName,
                    filePath = filePath,
                    fileSize = fileSize,
                    sourcePackage = activePkg,
                    sourceAppName = appName,
                    status = DownloadStatus.BLOCKED_DELETED
                )
            )
            return
        }

        // 2. فحص ما إذا كان التطبيق المصدر محظوراً من التنزيل في إعدادات قيود التطبيقات
        val restriction = if (activePkg.isNotBlank()) repo.getRestrictionForPackage(activePkg) else null
        val isAppDownloadBlocked = restriction != null &&
                restriction.isEnabled &&
                restriction.blockDownloads &&
                !repo.isPackageBypassed(activePkg)

        if (isAppDownloadBlocked) {
            deleteDownloadedFile(appContext, contentUri, fileObj, onDeleteExtra)
            mainHandler.post {
                val msg = Str.get(R.string.toast_download_blocked, appName.ifBlank { activePkg })
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
            DownloadLogRepository.getInstance(appContext).logRecord(
                DownloadRecord(
                    fileName = fileName,
                    filePath = filePath,
                    fileSize = fileSize,
                    sourcePackage = activePkg,
                    sourceAppName = appName,
                    status = DownloadStatus.BLOCKED_DELETED
                )
            )
            return
        }

        // 3. تسجيل التحميل المسموح في السجل الشامل
        DownloadLogRepository.getInstance(appContext).logRecord(
            DownloadRecord(
                fileName = fileName,
                filePath = filePath,
                fileSize = fileSize,
                sourcePackage = activePkg,
                sourceAppName = appName,
                status = DownloadStatus.ALLOWED
            )
        )
    }

    private fun deleteDownloadedFile(
        appContext: Context,
        contentUri: Uri?,
        fileObj: File?,
        onDeleteExtra: (() -> Unit)?
    ) {
        try {
            onDeleteExtra?.invoke()
        } catch (_: Exception) {}
        if (contentUri != null) {
            try {
                appContext.contentResolver.delete(contentUri, null, null)
            } catch (_: Exception) {}
        }
        if (fileObj != null && fileObj.exists()) {
            try {
                if (fileObj.isDirectory) fileObj.deleteRecursively() else fileObj.delete()
            } catch (_: Exception) {}
        }
    }

    private fun scanRecentPublicFiles(appContext: Context) {
        workerExecutor.execute {
            val dirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            )
            val now = System.currentTimeMillis()
            for (dir in dirs) {
                try {
                    if (!dir.exists()) continue
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        if (f.isFile && (now - f.lastModified() < 20_000L)) {
                            processFileObject(appContext, f)
                        } else if (f.isDirectory) {
                            f.listFiles()?.forEach { subFile ->
                                if (subFile.isFile && (now - subFile.lastModified() < 20_000L)) {
                                    processFileObject(appContext, subFile)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
