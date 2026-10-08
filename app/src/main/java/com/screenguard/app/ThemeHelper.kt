package com.screenguard.app

import android.content.Context
import androidx.annotation.StyleRes

/**
 * Maps the persisted theme id to its resource. The base theme
 * (Theme.ScreenGuard) is Midnight Prestige; each variant remaps
 * the sg_* tokens and the Material 3 color roles.
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

    @StyleRes
    fun themeRes(ctx: Context): Int = when (Prefs.theme(ctx)) {
        THEME_EMERALD -> R.style.Theme_ScreenGuard_EmeraldVault
        THEME_ROSE -> R.style.Theme_ScreenGuard_RoseAristocrat
        THEME_ONYX -> R.style.Theme_ScreenGuard_OnyxPlatinum
        THEME_SAPPHIRE -> R.style.Theme_ScreenGuard_SapphireCrown
        else -> R.style.Theme_ScreenGuard_MidnightPrestige
    }

    /** Bump the UI generation counter; every live activity recreates itself. */
    fun requestUiRefresh(ctx: Context) = Prefs.bumpUiVersion(ctx)
}
