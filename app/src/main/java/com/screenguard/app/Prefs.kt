package com.screenguard.app

import android.content.Context
import android.content.SharedPreferences

/** Central key/value store for monitoring behaviour. */
object Prefs {
    private const val NAME = "screenguard_prefs"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        if (::sp.isInitialized) sp else ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun intervalMinutes(ctx: Context): Int = prefs(ctx).getInt("interval_minutes", 10)
    fun setIntervalMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("interval_minutes", v).apply()

    fun captureOnAppOpen(ctx: Context): Boolean = prefs(ctx).getBoolean("capture_on_app_open", true)
    fun setCaptureOnAppOpen(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("capture_on_app_open", v).apply()

    fun showToast(ctx: Context): Boolean = prefs(ctx).getBoolean("show_toast", true)
    fun setShowToast(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("show_toast", v).apply()

    fun maxScreenshots(ctx: Context): Int = prefs(ctx).getInt("max_screenshots", 200)
    fun setMaxScreenshots(ctx: Context, v: Int) = prefs(ctx).edit().putInt("max_screenshots", v).apply()

    fun lastCapture(ctx: Context): Long = prefs(ctx).getLong("last_capture", 0L)
    fun setLastCapture(ctx: Context, v: Long) = prefs(ctx).edit().putLong("last_capture", v).apply()

    fun lastCapturedApp(ctx: Context): String? = prefs(ctx).getString("last_captured_app", null)
    fun setLastCapturedApp(ctx: Context, v: String?) = prefs(ctx).edit().putString("last_captured_app", v).apply()

    // ---- Appearance ----
    fun theme(ctx: Context): String = prefs(ctx).getString("theme", ThemeHelper.THEME_MIDNIGHT) ?: ThemeHelper.THEME_MIDNIGHT
    fun setTheme(ctx: Context, v: String) = prefs(ctx).edit().putString("theme", v).apply()

    // ---- Language ("system" | "en" | "ar") ----
    fun language(ctx: Context): String = prefs(ctx).getString("language", LocaleHelper.LANG_SYSTEM) ?: LocaleHelper.LANG_SYSTEM
    fun setLanguage(ctx: Context, v: String) = prefs(ctx).edit().putString("language", v).apply()

    // ---- UI generation: bumped on theme/language change ----
    fun uiVersion(ctx: Context): Int = prefs(ctx).getInt("ui_version", 0)
    fun bumpUiVersion(ctx: Context) = prefs(ctx).edit().putInt("ui_version", uiVersion(ctx) + 1).apply()

    // ---- Setup wizard completion status ----
    fun isSetupComplete(ctx: Context): Boolean = prefs(ctx).getBoolean("setup_complete", false)
    fun setSetupComplete(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("setup_complete", v).apply()
}
