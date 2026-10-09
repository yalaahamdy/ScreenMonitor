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

    /**
     * PRIMARY capture trigger (v3.1): capture only after this many minutes of
     * ACTIVE usage (screen on + app in foreground) have elapsed.
     */
    fun captureOnUsage(ctx: Context): Boolean = prefs(ctx).getBoolean("capture_on_usage", true)
    fun setCaptureOnUsage(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("capture_on_usage", v).apply()

    fun usageCaptureMinutes(ctx: Context): Int = prefs(ctx).getInt("usage_capture_minutes", 10)
    fun setUsageCaptureMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("usage_capture_minutes", v).apply()

    // ---- OPTIONAL capture triggers (all OFF by default since v3.1) ----
    fun capturePeriodic(ctx: Context): Boolean = prefs(ctx).getBoolean("capture_periodic", false)
    fun setCapturePeriodic(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("capture_periodic", v).apply()

    fun captureOnAppOpen(ctx: Context): Boolean = prefs(ctx).getBoolean("capture_on_app_open", false)
    fun setCaptureOnAppOpen(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("capture_on_app_open", v).apply()

    fun captureOnWake(ctx: Context): Boolean = prefs(ctx).getBoolean("capture_on_wake", false)
    fun setCaptureOnWake(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("capture_on_wake", v).apply()

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

    // Day/night mode ("dark" | "light" | "system") — dark keeps the
    // classic look for existing users; light resolves the values/ palettes.
    fun themeMode(ctx: Context): String = prefs(ctx).getString("theme_mode", ThemeHelper.MODE_DARK) ?: ThemeHelper.MODE_DARK
    fun setThemeMode(ctx: Context, v: String) = prefs(ctx).edit().putString("theme_mode", v).apply()

    // ---- Language ("system" | "en" | "ar") ----
    fun language(ctx: Context): String = prefs(ctx).getString("language", LocaleHelper.LANG_SYSTEM) ?: LocaleHelper.LANG_SYSTEM
    fun setLanguage(ctx: Context, v: String) = prefs(ctx).edit().putString("language", v).apply()

    // ---- UI generation: bumped on theme/language change ----
    fun uiVersion(ctx: Context): Int = prefs(ctx).getInt("ui_version", 0)
    fun bumpUiVersion(ctx: Context) = prefs(ctx).edit().putInt("ui_version", uiVersion(ctx) + 1).apply()

    // ---- Setup wizard completion status ----
    fun isSetupComplete(ctx: Context): Boolean = prefs(ctx).getBoolean("setup_complete", false)
    fun setSetupComplete(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("setup_complete", v).apply()

    // ---- Permissions hub dialog (shown once per app version) ----
    fun permissionsDialogShownFor(ctx: Context): String? =
        prefs(ctx).getString("permissions_dialog_shown_for", null)
    fun setPermissionsDialogShownFor(ctx: Context, v: String) =
        prefs(ctx).edit().putString("permissions_dialog_shown_for", v).apply()

    // ---- App-lock relock timeout (seconds; 0 = immediately) ----
    fun getLockTimeoutSeconds(): Int =
        if (::sp.isInitialized) sp.getInt("lock_timeout_seconds", PinManager.DEFAULT_LOCK_TIMEOUT_SECONDS)
        else PinManager.DEFAULT_LOCK_TIMEOUT_SECONDS

    fun setLockTimeoutSeconds(v: Int) {
        if (::sp.isInitialized) sp.edit().putInt("lock_timeout_seconds", v).apply()
    }
}
