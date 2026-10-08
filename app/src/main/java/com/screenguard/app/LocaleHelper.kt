package com.screenguard.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Wraps any context with the user-chosen in-app language so
 * activities, dialogs and even services render localized text.
 * "system" keeps the platform default untouched.
 */
object LocaleHelper {

    const val LANG_SYSTEM = "system"
    const val LANG_ENGLISH = "en"
    const val LANG_ARABIC = "ar"

    fun wrap(ctx: Context): Context {
        val lang = Prefs.language(ctx)
        if (lang == LANG_SYSTEM) return ctx

        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ctx.createConfigurationContext(config)
    }
}
