package com.andrerinas.openheadunit.main.synthwave

import com.andrerinas.openheadunit.main.theme.UiTheme
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.andrerinas.openheadunit.R
import kotlin.math.roundToInt

/**
 * The radio's printed scale (the mock's .scale): FM figures over a tick rule over AM figures, with a
 * pink needle at the tuned frequency. The needle follows the printed figures of the band in use, so
 * 98.3 sits between 96 and 100 wherever those land.
 *
 * Frequencies are the tuner's own units (TopwayRadio): FM in 10 kHz (9830 is 98.3 MHz), AM in kHz.
 * With [interactive] set, dragging along it tunes: [Listener.onPreview] while moving,
 * [Listener.onTune] on release.
 */
class TunerScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPreview(frequency: Int)
        fun onTune(frequency: Int)
    }

    var listener: Listener? = null
    var interactive = false

    private val density = resources.displayMetrics.density
    private val fmMarks = intArrayOf(88, 92, 96, 100, 104, 108)
    private val amMarks = intArrayOf(530, 700, 900, 1100, 1300, 1600)

    private var min = 8750
    private var max = 10800
    private var step = 10
    private var am = false
    private var shown = 9830f
    private var dragging = false

    private val glass = UiTheme.isGlass(context)
    private val box = RectF()
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0x33000000 else 0x7312081C }
    private val boxEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x40FFFFFF
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (glass) 0xB3FFFFFF.toInt() else 0xFFF3DCEF.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = UiTheme.font(context, R.attr.uiFontDigitsMedium) ?: ResourcesCompat.getFont(context, R.font.sw_oxanium_semibold)
    }
    private val tick = Paint().apply { color = if (glass) 0x99FFFFFF.toInt() else 0xCCF3DCEF.toInt(); strokeWidth = 1f }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.color(context, R.attr.uiHighlight); strokeWidth = 2f * density
    }
    private val needleHead = Path()

    /** Centre x of each printed figure, worked out in onDraw and used to place the needle. */
    private val fmX = FloatArray(fmMarks.size)
    private val amX = FloatArray(amMarks.size)

    fun setTuning(min: Int, max: Int, step: Int, am: Boolean, frequency: Int) {
        this.min = min
        this.max = max.coerceAtLeast(min + 1)
        this.step = step.coerceAtLeast(1)
        this.am = am
        if (!dragging && frequency > 0) shown = frequency.toFloat().coerceIn(min.toFloat(), max.toFloat())
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (64 * density).roundToInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 6f * density
        box.set(0.75f * density, 0.75f * density, w - 0.75f * density, h - 0.75f * density)
        canvas.drawRoundRect(box, radius, radius, boxFill)
        canvas.drawRoundRect(box, radius, radius, boxEdge)

        val padX = 10f * density
        val padY = 7f * density
        val gap = 4f * density
        val ascent = -text.ascent()
        val textH = ascent + text.descent()
        val inner = w - 2 * padX
        // Two rows of figures with the tick rule between them, centred in the box.
        val ruleH = 14f * density
        val total = textH * 2 + ruleH + gap * 2
        val top = ((h - total) / 2f).coerceAtLeast(padY)
        val fmBase = top + ascent
        val ruleTop = top + textH + gap
        val amBase = ruleTop + ruleH + gap + ascent

        drawRow(canvas, "FM", fmMarks, fmX, padX, inner, fmBase)
        drawRow(canvas, "AM", amMarks, amX, padX, inner, amBase)

        var x = padX
        while (x <= w - padX) { canvas.drawLine(x, ruleTop, x, ruleTop + ruleH, tick); x += 6f * density }
        val mid = ruleTop + ruleH / 2f
        tick.strokeWidth = 2f * density
        canvas.drawLine(padX, mid, w - padX, mid, tick)
        tick.strokeWidth = 1f

        val nx = xOf(shown)
        canvas.drawLine(nx, 2f * density, nx, h - 2f * density, needle)
        needleHead.reset()
        needleHead.moveTo(nx - 7f * density, 0f)
        needleHead.lineTo(nx + 7f * density, 0f)
        needleHead.lineTo(nx, 9f * density)
        needleHead.close()
        canvas.drawPath(needleHead, needle)
    }

    /** The band name in an 18dp slot, then the figures spread evenly to the right edge (CSS space-between). */
    private fun drawRow(canvas: Canvas, band: String, marks: IntArray, centres: FloatArray, left: Float, inner: Float, base: Float) {
        val slot = 18f * density
        canvas.drawText(band, left, base, text)
        val widths = marks.map { text.measureText(it.toString()) }
        val spare = inner - slot - widths.sum()
        val gap = spare / marks.size
        var x = left + slot + gap
        marks.forEachIndexed { i, mark ->
            canvas.drawText(mark.toString(), x, base, text)
            centres[i] = x + widths[i] / 2f
            x += widths[i] + gap
        }
    }

    /** The figures in the tuner's units for the band in use. */
    private fun unitsOf(i: Int): Float = if (am) amMarks[i].toFloat() else fmMarks[i] * 100f

    private fun centres(): FloatArray = if (am) amX else fmX

    private fun xOf(frequency: Float): Float {
        val c = centres()
        if (c.last() <= c.first()) return width / 2f
        val n = c.size
        // Piecewise between the printed figures, carried on past either end at the nearest pace.
        var i = 0
        while (i < n - 2 && frequency > unitsOf(i + 1)) i++
        val f0 = unitsOf(i)
        val f1 = unitsOf(i + 1)
        val x = c[i] + (frequency - f0) / (f1 - f0) * (c[i + 1] - c[i])
        return x.coerceIn(10f * density, width - 10f * density)
    }

    private fun frequencyAt(x: Float): Int {
        val c = centres()
        val n = c.size
        var i = 0
        while (i < n - 2 && x > c[i + 1]) i++
        val f = unitsOf(i) + (x - c[i]) / (c[i + 1] - c[i]) * (unitsOf(i + 1) - unitsOf(i))
        val snapped = min + ((f - min) / step).roundToInt() * step
        return snapped.coerceIn(min, max)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = frequencyAt(event.x)
                if (f.toFloat() != shown) {
                    shown = f.toFloat()
                    listener?.onPreview(f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                listener?.onTune(shown.roundToInt())
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}
