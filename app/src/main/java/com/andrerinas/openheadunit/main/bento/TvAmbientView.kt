package com.andrerinas.openheadunit.main.bento

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.andrerinas.openheadunit.app.BaseActivity

/**
 * The tvOS screens' background: near-black with three soft pools of indigo, blue and pink light,
 * the reference dashboard's blurred blobs.
 *
 * Painted once, in software, into a small bitmap that is then stretched over the screen. Drawing
 * the three gradients live cost four full-screen blended passes on every frame, more than the head
 * unit's GPU (Mali-400 class) can fill at 60 fps; the soft gradients lose nothing at a third of the
 * resolution. The view is opaque and asks its activity to drop the window background under it, so
 * the screen is filled exactly once.
 */
class TvAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Blob(val cx: Float, val cy: Float, val radius: Float, val color: Int)

    private companion object {
        /** The bitmap is this many times smaller than the view on each side. */
        const val SCALE = 3
        const val BASE = 0xFF08080F.toInt()

        // Centres and radii as fractions of the width, measured off the 1280x480 reference.
        val BLOBS = listOf(
            Blob(0.30f, 0.22f, 0.40f, 0xFF5A67D8.toInt()),
            Blob(0.56f, 0.70f, 0.36f, 0xFF3182CE.toInt()),
            Blob(0.88f, 1.05f, 0.44f, 0xFFD53F8C.toInt()),
        )
    }

    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    override fun isOpaque(): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activity()?.setOpaqueContent(true)
    }

    override fun onDetachedFromWindow() {
        activity()?.setOpaqueContent(false)
        super.onDetachedFromWindow()
        bitmap?.recycle()
        bitmap = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmap?.recycle()
        bitmap = null
        if (w <= 0 || h <= 0) return
        val bw = (w / SCALE).coerceAtLeast(1)
        val bh = (h / SCALE).coerceAtLeast(1)
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BASE)
        val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        BLOBS.forEach { blob ->
            blobPaint.shader = RadialGradient(
                blob.cx * bw, blob.cy * bh,
                blob.radius * bw,
                intArrayOf(withAlpha(blob.color, 0.62f), withAlpha(blob.color, 0.30f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), blobPaint)
        }
        bitmap = out
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null) {
            canvas.drawColor(BASE)
            return
        }
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, paint)
    }

    private fun activity(): BaseActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c as? BaseActivity
            c = c.baseContext
        }
        return null
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
}
