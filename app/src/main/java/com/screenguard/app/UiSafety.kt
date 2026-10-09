package com.screenguard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v3.1.1 runtime safety net.
 *
 * Every screen wraps its fragile sections in [guard]: if an unexpected
 * exception occurs it is written to the crash log and the screen keeps
 * working (or shows a graceful in-place error card) instead of killing the
 * whole process. The error card carries a one-tap "copy report" action so
 * the exact stack trace can reach the developer from inside the app.
 */
object UiSafety {

    /** Tag set on the root of [errorCard] so callers can detect it. */
    const val ERROR_TAG = "sg_error_card"

    fun isErrorCard(v: View?): Boolean = v?.tag == ERROR_TAG

    /** Run [block]; on any throwable: log it and return false. */
    inline fun guard(where: String, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            CrashLogger.log(t, where)
            false
        }
    }

    /** Run [block]; on failure returns [fallback]. */
    inline fun <T> guardElse(where: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            CrashLogger.log(t, where)
            fallback
        }
    }

    /** Build the elegant in-place error card used by fragments and activities. */
    fun errorCard(context: Context, where: String, t: Throwable?): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val trace = t?.let { android.util.Log.getStackTraceString(it) } ?: ""

        val title = TextView(context).apply {
            text = Str.get(R.string.error_screen_title).ifEmpty { "Unexpected error" }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themedColor(context, android.R.attr.textColorPrimary))
        }

        val body = TextView(context).apply {
            text = Str.get(R.string.error_screen_sub).ifEmpty {
                "The rest of the app keeps working. Copy the report and send it to the developer."
            }
            textSize = 13f
            setTextColor(themedColor(context, android.R.attr.textColorSecondary))
        }

        val whereTag = TextView(context).apply {
            text = "$where · v${BuildConfig.VERSION_NAME}"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(themedColor(context, android.R.attr.textColorSecondary))
        }

        val copyBtn = TextView(context).apply {
            text = Str.get(R.string.error_copy_report).ifEmpty { "Copy report" }
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundResource(R.drawable.bg_chip_selected)
            setOnClickListener {
                if (copyToClipboard(context, "$where\n$trace")) {
                    Toast.makeText(context, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(copyBtn)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }

        return ScrollView(context).apply {
            tag = ERROR_TAG
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(28), dp(24), dp(24))
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(title)
                    addView(
                        body,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(10) }
                    )
                    addView(whereTag, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) })
                    addView(buttonsRow, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(18) })
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER })
        }
    }

    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ScreenGuard", text))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun themedColor(context: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
