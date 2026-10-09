package com.screenguard.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Central localization helper for the merged feature set (usage analytics,
 * data monitoring, restrictions). Allows data-layer classes and singletons
 * to fetch user-facing strings in the active locale (English / Arabic)
 * without needing direct Context references.
 *
 * Initialized once from [ScreenGuardApp.onCreate].
 */
object Str {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Resolve a string resource, optionally formatted with arguments. */
    fun get(@StringRes resId: Int, vararg args: Any?): String {
        val ctx = appContext ?: return ""
        return try {
            ctx.getString(resId, *args)
        } catch (e: Exception) {
            ""
        }
    }

    /** Resolve a string resource safely; returns [fallback] when unavailable. */
    fun getOr(@StringRes resId: Int, fallback: String, vararg args: Any?): String {
        val s = get(resId, *args)
        return s.ifEmpty { fallback }
    }
}
