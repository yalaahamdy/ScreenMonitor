package com.screenguard.app.ui.charts

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.screenguard.app.R
import com.screenguard.app.data.model.HourlyUsage

/**
 * Animated 24-hour bar chart with tap-to-select hour and peak highlighting.
 * Ported from the Compose canvas implementation of the usage-controller app.
 */
class HourlyUsageBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<HourlyUsage> = emptyList()
    private var maxDurationMs: Long = 0L
    private var progress = 0f
    private var selectedIndex = -1

    private val accentColor = themedColor(R.attr.sgAccentText)
    private val dimColor = ContextCompat.getColor(context, R.color.chart_bar_dim)
    private val peakColor = ContextCompat.getColor(context, R.color.success)
    private val selectedColor = ContextCompat.getColor(context, R.color.accent_blue)
    private val labelColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val gridColor = ContextCompat.getColor(context, R.color.outline)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var animator: ValueAnimator? = null

    fun setData(hours: List<HourlyUsage>) {
        data = hours
        maxDurationMs = hours.maxOfOrNull { it.durationMs } ?: 0L
        selectedIndex = -1
        startAnimation()
    }

    fun clearSelection() {
        selectedIndex = -1
        invalidate()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val dp = resources.displayMetrics.density
        val paddingBottom = dp * 20
        val paddingTop = dp * 18
        val chartHeight = height - paddingBottom - paddingTop

        val peakIndex = data.indices.maxByOrNull { data[it].durationMs }?.takeIf { maxDurationMs > 0 }
        val slotWidth = width.toFloat() / data.size
        val barWidth = slotWidth * 0.62f

        // Baseline
        val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            strokeWidth = dp
        }
        canvas.drawLine(0f, height - paddingBottom, width.toFloat(), height - paddingBottom, baselinePaint)

        for ((index, hour) in data.withIndex()) {
            val ratio = if (maxDurationMs > 0) (hour.durationMs.toFloat() / maxDurationMs) else 0f
            val animatedRatio = ratio * progress
            val barHeight = chartHeight * animatedRatio
            val left = index * slotWidth + (slotWidth - barWidth) / 2f
            val top = height - paddingBottom - barHeight
            val rect = RectF(left, top, left + barWidth, height - paddingBottom - dp)

            barPaint.color = when {
                index == selectedIndex -> selectedColor
                index == peakIndex -> peakColor
                animatedRatio <= 0f -> dimColor
                else -> accentColor
            }
            barPaint.alpha = if (animatedRatio <= 0f) 90 else 255
            canvas.drawRoundRect(rect, dp * 3, dp * 3, barPaint)
            barPaint.alpha = 255

            // Labels every 3 hours
            if (index % 3 == 0) {
                val label = if (hour.hour == 0) "12"
                else if (hour.hour < 12) hour.hour.toString()
                else if (hour.hour == 12) "12"
                else (hour.hour - 12).toString()
                canvas.drawText(label, index * slotWidth + slotWidth / 2f, height - dp * 6, labelPaint)
            }

            // Selected hour duration bubble
            if (index == selectedIndex && hour.durationMs > 0) {
                val text = formatShortDuration(hour.durationMs)
                canvas.drawText(text, index * slotWidth + slotWidth / 2f, (top - dp * 6).coerceAtLeast(sp(11f)), valuePaint)
            }
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val slot = (e.x / width.toFloat() * data.size).toInt().coerceIn(0, data.size - 1)
            selectedIndex = if (selectedIndex == slot) -1 else slot
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun formatShortDuration(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    private fun themedColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
