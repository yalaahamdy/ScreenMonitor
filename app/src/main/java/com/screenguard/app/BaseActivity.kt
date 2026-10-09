package com.screenguard.app

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity

/**
 * Common base for every screen:
 *  - applies the user-selected luxury palette before inflation,
 *  - wraps the base context with the chosen in-app language,
 *  - recreates itself when theme or language generation changes,
 *    so switching from Settings refreshes the whole back stack
 *    as the user navigates back.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var uiVersionAtCreate = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (useRuntimeTheme()) {
            setTheme(ThemeHelper.themeRes(this))
        }
        super.onCreate(savedInstanceState)
        uiVersionAtCreate = Prefs.uiVersion(this)
    }

    /** Overridden by transparent/overlay-style activities that must keep their manifest theme. */
    protected open fun useRuntimeTheme(): Boolean = true

    override fun onResume() {
        super.onResume()
        if (Prefs.uiVersion(this) != uiVersionAtCreate) {
            recreate()
            return
        }
        if (this !is PinLockActivity && PinManager.hasPin(this) && !PinManager.isSessionUnlocked()) {
            val lockIntent = android.content.Intent(this, PinLockActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(lockIntent)
            finish()
            return
        }
    }

    /** Resolve a themed color attribute (accent, surfaces, text…) to ARGB. */
    @ColorInt
    protected fun themedColor(@AttrRes attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
