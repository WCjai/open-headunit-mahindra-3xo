package com.andrerinas.openheadunit.main.synthwave

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.theme.UiTheme
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The home's Radio tile knob (the mock's .tring and .knob2): pink ticks round 244 degrees, a dark
 * well, and a brushed-metal knob with its pointer and a pink mark at the top. Drawn only; the tile
 * around it takes the tap.
 */
class TuningKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Glass: white ticks on a neutral dark well instead of pink on purple. */
    private val glass = UiTheme.isGlass(context)
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (glass) 0x80FFFFFF.toInt() else 0xFFFF5FA2.toInt(); strokeCap = Paint.Cap.BUTT
    }
    private val well = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0xFF121218.toInt() else 0xFF160A22.toInt() }
    private val wellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = if (glass) 0x33FFFFFF else 0xFF3A1C4F.toInt()
    }
    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0xFF2C2C34.toInt() else 0xFF6B3A85.toInt() }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0xFF101016.toInt() else 0xFF1A0C26.toInt() }
    private val metal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2D30.toInt() }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.color(context, R.attr.uiHighlight) }
    private val markPath = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f
        // The mock's knob is 124 units across; everything scales from that.
        val u = size / 124f

        ticks.strokeWidth = 1.6f * u
        var angle = -122f
        while (angle <= 122f) {
            val rad = Math.toRadians((angle - 90f).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(cx + c * r * 0.80f, cy + s * r * 0.80f, cx + c * r * 0.97f, cy + s * r * 0.97f, ticks)
            angle += 8f
        }

        canvas.drawCircle(cx, cy, r * 0.78f, well)
        wellEdge.strokeWidth = 3f * u
        canvas.drawCircle(cx, cy, r * 0.76f, wellEdge)

        val knob = 38f * u
        canvas.drawCircle(cx, cy, knob + 7f * u, ringOuter)
        canvas.drawCircle(cx, cy, knob + 5f * u, ringInner)
        metal.shader = RadialGradient(cx - knob * 0.2f, cy - knob * 0.36f, knob * 1.3f,
            intArrayOf(0xFFF4F4F4.toInt(), 0xFFC2C6CA.toInt(), 0xFF7C8288.toInt(), 0xFF3F4448.toInt()),
            floatArrayOf(0f, 0.3f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, knob, metal)

        rect.set(cx - 1.5f * u, cy - knob + 7f * u, cx + 1.5f * u, cy - knob + 21f * u)
        canvas.drawRoundRect(rect, 2f * u, 2f * u, pointer)

        markPath.reset()
        markPath.moveTo(cx - 6f * u, cy - r + 2f * u)
        markPath.lineTo(cx + 6f * u, cy - r + 2f * u)
        markPath.lineTo(cx, cy - r + 11f * u)
        markPath.close()
        canvas.drawPath(markPath, mark)
    }
}
