package com.andrerinas.openheadunit.main.synthwave

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.hypot

/**
 * The panel inside a card (the mock's .scope): an octagon with cut corners, a faint cyan grid on a
 * dark purple glow, and a purple edge. It holds the album art, the phone logos, the tuner and the
 * caller.
 *
 * [glass] draws the Glass look instead: a plain rounded pane, darker than the card, with a faint edge.
 */
class ScopeDrawable(private val density: Float, private val glass: Boolean = false) : Drawable() {

    private val cut = 14f * density
    private val glassRadius = 16f * density
    private val border = 2f * density
    private val gridStep = 12f * density
    private val outline = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = if (glass) Color.TRANSPARENT else 0x142DE2E6; strokeWidth = 1f }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (glass) density else border
        color = if (glass) 0x1FFFFFFF else 0xFF6B3A85.toInt()
    }
    private var alphaValue = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val inset = border / 2f
        val l = bounds.left + inset
        val t = bounds.top + inset
        val r = bounds.right - inset
        val b = bounds.bottom - inset
        outline.reset()
        if (glass) {
            outline.addRoundRect(l, t, r, b, glassRadius, glassRadius, Path.Direction.CW)
            fill.shader = null
            fill.color = 0x40000000
            return
        }
        outline.moveTo(l + cut, t)
        outline.lineTo(r - cut, t)
        outline.lineTo(r, t + cut)
        outline.lineTo(r, b - cut)
        outline.lineTo(r - cut, b)
        outline.lineTo(l + cut, b)
        outline.lineTo(l, b - cut)
        outline.lineTo(l, t + cut)
        outline.close()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = (hypot(bounds.width().toFloat(), bounds.height().toFloat()) / 2f).coerceAtLeast(1f)
        fill.shader = RadialGradient(cx, cy, radius, 0x73241248, 0x990C0618.toInt(), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.clipPath(outline)
        canvas.drawPath(outline, fill)
        if (glass) {
            canvas.restore()
            canvas.drawPath(outline, edge)
            return
        }
        var x = b.left.toFloat()
        while (x < b.right) { canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), grid); x += gridStep }
        var y = b.top.toFloat()
        while (y < b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, grid); y += gridStep }
        canvas.restore()
        canvas.drawPath(outline, edge)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        fill.alpha = alpha
        edge.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
