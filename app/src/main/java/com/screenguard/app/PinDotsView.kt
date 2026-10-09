package com.screenguard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/**
 * Four-dot PIN progress indicator with Midnight Prestige styling:
 * hollow rings that fill with champagne gold, turning jade on success
 * and ruby on failure.
 */
class PinDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STATE_NORMAL = 0
        const val STATE_ERROR = 1
        const val STATE_SUCCESS = 2
    }

    var dotCount = 4
        set(value) { field = value; invalidate() }

    var filled = 0
        set(value) { field = value.coerceIn(0, dotCount); invalidate() }

    var state = STATE_NORMAL
        set(value) { field = value; invalidate() }

    fun reset() {
        filled = 0
        state = STATE_NORMAL
    }

    /** Resolve a themed color so the dots follow the active palette. */
    @ColorInt
    private fun attrColor(@AttrRes attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = attrColor(R.attr.sgOutlineStrong)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = attrColor(R.attr.sgAccentText)
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = attrColor(R.attr.sgAccentText)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density
        val radius = dp * 7f
        val gap = dp * 26f
        val total = (dotCount - 1) * gap
        val startX = (width - total) / 2f
        val cy = height / 2f

        val activeColor = when (state) {
            STATE_ERROR -> ContextCompat.getColor(context, R.color.danger)
            STATE_SUCCESS -> ContextCompat.getColor(context, R.color.success_text)
            else -> attrColor(R.attr.sgAccentText)
        }

        for (i in 0 until dotCount) {
            val cx = startX + i * gap
            if (i < filled) {
                // filled dot + glowing gold ring
                fillPaint.color = activeColor
                activePaint.color = activeColor
                canvas.drawCircle(cx, cy, radius + dp * 2.5f, activePaint)
                canvas.drawCircle(cx, cy, radius, fillPaint)
            } else {
                canvas.drawCircle(cx, cy, radius, ringPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dp = resources.displayMetrics.density
        val h = (dp * 30).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(
            resolveSize((dp * 120).toInt(), widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }
}
