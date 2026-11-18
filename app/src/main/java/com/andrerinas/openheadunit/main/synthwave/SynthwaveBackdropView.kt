package com.andrerinas.openheadunit.main.synthwave

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.main.theme.UiTheme

/**
 * The synthwave screens' background: the sunset coast of [SynthwaveScene].
 *
 * The mock's cards blur what is behind them (CSS backdrop-filter), which Android 8 cannot do. This
 * view does it for them: [frost] registers a card, and the view draws the blurred, more saturated
 * scene inside that card's rounded outline. The card's own translucent background goes on top, so
 * it reads as frosted glass.
 *
 * [fullBlur] draws the whole scene blurred, for the call screen.
 */
class SynthwaveBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var fullBlur = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val glass = UiTheme.isGlass(context)
    /** The cards' corner, so the frost fits under them. */
    private val cornerRadius = run {
        val value = TypedValue()
        if (context.theme.resolveAttribute(R.attr.uiCardRadius, value, true)) value.getDimension(resources.displayMetrics) else 14f * density
    }
    private val frosted = ArrayList<View>()
    private val frostRects = HashMap<View, RectF>()

    private val scenePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frostPaint = SynthwaveScene.frostPaint()
    private val clip = Path()
    private val src = Rect()
    private val dst = Rect()
    private val myLocation = IntArray(2)
    private val itsLocation = IntArray(2)

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (updateFrostRects()) invalidate()
        true
    }

    /** Draws frosted glass under each of [cards] from now on. */
    fun frost(vararg cards: View) {
        cards.forEach { if (it !in frosted) frosted.add(it) }
        updateFrostRects()
        invalidate()
    }

    override fun isOpaque(): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
        // The scene covers the window, so the window's own background need not be painted under it.
        activity()?.setOpaqueContent(true)
    }

    override fun onDetachedFromWindow() {
        activity()?.setOpaqueContent(false)
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    private fun activity(): BaseActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c as? BaseActivity
            c = c.baseContext
        }
        return null
    }

    /** Where each card sits over this view now. True when any moved, appeared or went away. */
    private fun updateFrostRects(): Boolean {
        if (frosted.isEmpty()) return false
        getLocationInWindow(myLocation)
        var changed = false
        for (card in frosted) {
            if (!card.isShown || card.width == 0) {
                if (frostRects.remove(card) != null) changed = true
                continue
            }
            card.getLocationInWindow(itsLocation)
            val left = (itsLocation[0] - myLocation[0]).toFloat()
            val top = (itsLocation[1] - myLocation[1]).toFloat()
            val rect = frostRects.getOrPut(card) { changed = true; RectF() }
            if (rect.left != left || rect.top != top || rect.width() != card.width.toFloat() ||
                rect.height() != card.height.toFloat()) {
                rect.set(left, top, left + card.width, top + card.height)
                changed = true
            }
        }
        return changed
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val scene = SynthwaveScene.get(resources, width, height, glass)
        dst.set(0, 0, width, height)
        src.set(0, 0, scene.blurred.width, scene.blurred.height)
        if (fullBlur) {
            canvas.drawBitmap(scene.blurred, src, dst, frostPaint)
            return
        }
        canvas.drawBitmap(scene.sharp, 0f, 0f, scenePaint)
        for (rect in frostRects.values) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawBitmap(scene.blurred, src, dst, frostPaint)
            canvas.restore()
        }
    }
}
