package com.screenguard.app

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate

/**
 * Maps the persisted theme id to its resource. The base theme
 * (Theme.ScreenGuard) exists in TWO modes:
 *   values-night/ -> the five luxury DARK palettes (classic look)
 *   values/       -> daybreak LIGHT counterparts      (v3.2.0)
 * The active mode is applied app-wide through AppCompatDelegate
 * night mode, so every activity, dialog and chart flips together.
 */
object ThemeHelper {

    const val THEME_MIDNIGHT = "midnight"
    const val THEME_EMERALD = "emerald"
    const val THEME_ROSE = "rose"
    const val THEME_ONYX = "onyx"
    const val THEME_SAPPHIRE = "sapphire"

    val ALL = listOf(
        THEME_MIDNIGHT, THEME_EMERALD, THEME_ROSE, THEME_ONYX, THEME_SAPPHIRE
    )

    // ---- Day/night mode ----
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    const val MODE_SYSTEM = "system"

    @StyleRes
    fun themeRes(ctx: Context): Int = when (Prefs.theme(ctx)) {
        THEME_EMERALD -> R.style.Theme_ScreenGuard_EmeraldVault
        THEME_ROSE -> R.style.Theme_ScreenGuard_RoseAristocrat
        THEME_ONYX -> R.style.Theme_ScreenGuard_OnyxPlatinum
        THEME_SAPPHIRE -> R.style.Theme_ScreenGuard_SapphireCrown
        else -> R.style.Theme_ScreenGuard_MidnightPrestige
    }

    /**
     * Apply the persisted day/night mode to the whole process.
     * Called on app start and whenever the user switches mode in
     * Settings; AppCompatActivity recreates live screens itself.
     */
    fun applyDayNight(ctx: Context) {
        val mode = when (Prefs.themeMode(ctx)) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Bump the UI generation counter; every live activity recreates itself. */
    fun requestUiRefresh(ctx: Context) = Prefs.bumpUiVersion(ctx)
}
