package com.screenguard.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom Application class tracking process lifecycle and maintaining
 * secure parental session state across foreground / background transitions.
 *
 * v3.1: also installs a global crash logger — every uncaught exception is
 * appended to files/crash_log.txt (capped) so recurring device-specific
 * crashes can be diagnosed from Settings > About instead of dying silently.
 */
class ScreenGuardApp : Application(), Application.ActivityLifecycleCallbacks {

    private var activityReferences = 0
    private var isActivityChangingConfigurations = false

    override fun onCreate() {
        super.onCreate()
        Str.init(this)
        Prefs.init(this)
        ThemeHelper.applyDayNight(this)
        CrashLogger.install(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (++activityReferences == 1 && !isActivityChangingConfigurations) {
            // App entered foreground from background
            PinManager.onAppEnteredForeground()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        isActivityChangingConfigurations = activity.isChangingConfigurations
        if (--activityReferences <= 0 && !isActivityChangingConfigurations) {
            activityReferences = 0
            // App entered background
            PinManager.onAppEnteredBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

/** App-scoped uncaught-exception logger with a size-capped history file. */
object CrashLogger {

    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 256 * 1024L
    private var appContext: Context? = null

    fun install(app: Application) {
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append(throwable)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasLogs(context: Context): Boolean = logFile(context).let { it.exists() && it.length() > 0 }

    fun readLogs(context: Context, tailBytes: Long = 8 * 1024L): String {
        return try {
            val f = logFile(context)
            if (!f.exists()) return ""
            if (f.length() <= tailBytes) return f.readText()
            val start = (f.length() - tailBytes).coerceAtLeast(0)
            val raf = java.io.RandomAccessFile(f, "r")
            raf.seek(start)
            val bytes = ByteArray((f.length() - start).toInt())
            raf.readFully(bytes)
            raf.close()
            // skip the first (possibly partial) line
            bytes.toString(Charsets.UTF_8).substringAfter('\n', "")
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        try {
            logFile(context).delete()
        } catch (_: Exception) {}
    }

    /** v3.1.1: public, tag-annotated logging for guarded (non-fatal) errors. */
    fun log(throwable: Throwable, where: String = "runtime") {
        try {
            append(throwable, where)
        } catch (_: Exception) {}
    }

    private fun append(throwable: Throwable, where: String = "uncaught") {
        val ctx = appContext ?: return
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val version = try {
            val pm = ctx.packageManager
            pm.getPackageInfo(ctx.packageName, 0).let { pi ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.versionName ?: "?"
                else pi.versionName
            }
        } catch (_: PackageManager.NameNotFoundException) { "?" }

        val entry = buildString {
            append("\n==== CRASH (")
            append(where)
            append(") ====\n")
            append("time: ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append("\nversion: ").append(version)
            append(" (api ").append(Build.VERSION.SDK_INT).append(", ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(")\n")
            append("thread: ").append(Thread.currentThread().name).append('\n')
            append(sw.toString())
        }

        val f = logFile(ctx)
        if (f.length() > MAX_BYTES) f.delete()
        File(ctx.filesDir, FILE_NAME).appendText(entry)
    }
}
