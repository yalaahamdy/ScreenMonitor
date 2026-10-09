package com.screenguard.app.ui.charts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.screenguard.app.R

/**
 * Quadrant summary view splitting the day into morning / afternoon /
 * evening / night usage portions, drawn as a two-column distribution
 * with proportional bars. Ported from the Compose implementation.
 */
class TimeOfDayDistributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Portion(
        val label: String,
        val durationMs: Long,
        val percentage: Float
    )

    private var portions: List<Portion> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_alt)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = Typeface.DEFAULT_BOLD
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private val portionColors = intArrayOf(
        ContextCompat.getColor(context, R.color.accent_blue),
        ContextCompat.getColor(context, R.color.success),
        ContextCompat.getColor(context, R.color.warning),
        ContextCompat.getColor(context, R.color.accent_purple)
    )

    fun setData(items: List<Portion>) {
        portions = items
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = portions.size
        val dp = resources.displayMetrics.density
        val rowHeight = (dp * 34).toInt()
        val h = if (rows == 0) (dp * 40).toInt() else rows * rowHeight + (dp * 8).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (portions.isEmpty()) {
            canvas.drawText("—", width / 2f, height / 2f, valuePaint)
            return
        }

        val dp = resources.displayMetrics.density
        val rowHeight = dp * 34
        val labelWidth = dp * 74
        val barHeight = dp * 10

        for ((index, portion) in portions.withIndex()) {
            val top = index * rowHeight + dp * 4
            val cy = top + rowHeight / 2f

            canvas.drawText(portion.label, dp * 4, cy + sp(4f), labelPaint)

            val trackLeft = labelWidth
            val trackRight = width - dp * 60
            val trackRect = RectF(trackLeft, cy - barHeight / 2, trackRight, cy + barHeight / 2)
            canvas.drawRoundRect(trackRect, barHeight / 2, barHeight / 2, trackPaint)

            val ratio = portion.percentage.coerceIn(0f, 1f)
            if (ratio > 0f) {
                barPaint.color = portionColors[index % portionColors.size]
                val barRect = RectF(trackLeft, cy - barHeight / 2,
                    trackLeft + (trackRight - trackLeft) * ratio, cy + barHeight / 2)
                canvas.drawRoundRect(barRect, barHeight / 2, barHeight / 2, barPaint)
            }

            val valueText = formatShort(portion.durationMs)
            val oldAlign = valuePaint.textAlign
            valuePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(valueText, width - dp * 4, cy + sp(4f), valuePaint)
            valuePaint.textAlign = oldAlign
        }
    }

    private fun formatShort(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }

    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}
