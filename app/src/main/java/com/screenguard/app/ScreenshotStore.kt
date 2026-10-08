package com.screenguard.app

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the capture library on app-specific external storage (no permission needed). */
object ScreenshotStore {

    private const val DIR_NAME = "Captures"
    private const val PREFIX = "SG_"
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }

    /** Saves a capture and enforces the storage quota. Returns the written file. */
    fun save(ctx: Context, bitmap: Bitmap): File? {
        val name = PREFIX + fmt.format(Date()) + ".jpg"
        val out = File(dir(ctx), name)
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            out.delete()
            return null
        }
        enforceQuota(ctx)
        return out
    }

    /** All captures, newest first. File names sort chronologically. */
    fun all(ctx: Context): List<File> =
        dir(ctx).listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) &&
                    (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".png", ignoreCase = true))
        }?.sortedByDescending { it.name } ?: emptyList()

    fun count(ctx: Context): Int = all(ctx).size

    fun last(ctx: Context): File? = all(ctx).firstOrNull()

    fun delete(ctx: Context, file: File): Boolean = file.delete()

    fun deleteAll(ctx: Context): Int {
        val files = all(ctx)
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }

    /** Removes oldest captures beyond the configured maximum. */
    fun enforceQuota(ctx: Context) {
        val max = Prefs.maxScreenshots(ctx)
        val files = all(ctx)
        if (files.size <= max) return
        files.drop(max).forEach { it.delete() }
    }
}
