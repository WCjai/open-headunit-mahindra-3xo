package com.andrerinas.openheadunit.main.bento

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A frequency ruler that scrolls under a fixed centre needle, like a tuning dial. Dragging it
 * previews a frequency; letting go tunes there. Ticks fade towards the edges.
 *
 * Frequencies are in the tuner's own units (FM 10 kHz, AM kHz), on [min]..[max] in [step]s.
 */
class TunerDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Told while dragging (preview) and on release (tune). */
    interface Listener {
        fun onPreview(frequency: Int)
        fun onTune(frequency: Int)
    }

    var listener: Listener? = null

    private var min = 8750
    private var max = 10800
    private var step = 10
    private var am = false
    private var shown = 9800f
    private var dragging = false
    private var downX = 0f
    private var downShown = 0f

    private val density = resources.displayMetrics.density
    private val spacing = 11f * density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** Shows [frequency] on a band; ignored while the user drags. */
    fun setTuning(min: Int, max: Int, step: Int, am: Boolean, frequency: Int) {
        this.min = min
        this.max = max.coerceAtLeast(min + step)
        this.step = step.coerceAtLeast(1)
        this.am = am
        if (!dragging && frequency > 0) shown = frequency.toFloat().coerceIn(min.toFloat(), max.toFloat())
        invalidate()
    }

    private fun snapped(): Int {
        val steps = ((shown - min) / step).roundToInt()
        return (min + steps * step).coerceIn(min, max)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val baseline = height * 0.62f
        val first = ((shown - min) / step - cx / spacing).toInt() - 1
        val last = ((shown - min) / step + cx / spacing).toInt() + 1
        for (i in first.coerceAtLeast(0)..last) {
            val value = min + i * step
            if (value > max) break
            val x = cx + (value - shown) / step * spacing
            // Fade out towards both edges, so the dial reads as a curved strip.
            val fade = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
            val major = if (am) (value - min) % (step * 10) == 0 else value % 100 == 0
            val half = !am && value % 50 == 0
            val length = when {
                major -> 22f
                half -> 14f
                else -> 8f
            } * density
            tickPaint.strokeWidth = (if (major) 2.2f else 1.4f) * density
            tickPaint.alpha = (fade * (if (major) 230 else 120)).toInt()
            canvas.drawLine(x, baseline - length, x, baseline, tickPaint)
            if (major) {
                labelPaint.alpha = (fade * 200).toInt()
                val label = if (am) value.toString() else (value / 100).toString()
                canvas.drawText(label, x, baseline + 18f * density, labelPaint)
            }
        }
        canvas.drawLine(cx, height * 0.08f, cx, baseline + 4f * density, needlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downX = event.x
                downShown = shown
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                shown = (downShown - (event.x - downX) / spacing * step).coerceIn(min.toFloat(), max.toFloat())
                invalidate()
                listener?.onPreview(snapped())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val target = snapped()
                shown = target.toFloat()
                invalidate()
                // A tap without a drag is not a tune.
                if (abs(event.x - downX) > 4f * density) listener?.onTune(target)
            }
        }
        return true
    }
}
