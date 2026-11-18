package com.andrerinas.openheadunit.main.synthwave

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.max

/**
 * The head-unit screens' background, painted once per screen size into a bitmap, with a blurred
 * quarter-size copy for frosted glass. Every screen shares the last pair.
 *
 * Synthwave shows the palm-tree sunset photo (res/drawable-nodpi/sw_bg.webp, from Desktop/car/wall.webp),
 * centre-cropped to the screen, falling back to the painted coast of ui/Head Unit UI Mock.html if it
 * cannot be read. Glass ([glass]) paints the earlier tvOS-style look: near-black with soft pools of
 * indigo, blue and pink light.
 */
object SynthwaveScene {

    class Pair(val width: Int, val height: Int, val glass: Boolean, val sharp: Bitmap, val blurred: Bitmap)

    private var cached: Pair? = null

    /** Main thread. */
    fun get(resources: Resources, width: Int, height: Int, glass: Boolean = false): Pair {
        cached?.let { if (it.width == width && it.height == height && it.glass == glass) return it }
        val density = resources.displayMetrics.density
        val sharp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sharp)
        when {
            glass -> paintAmbient(canvas, width, height)
            !paintPhoto(resources, canvas, width, height) -> paint(canvas, width, height)
        }
        // A quarter-size copy blurred with three box passes: close to the mock's 22px backdrop blur.
        val small = Bitmap.createScaledBitmap(sharp, (width / 4).coerceAtLeast(1), (height / 4).coerceAtLeast(1), true)
        boxBlur(small, radius = (5 * density).toInt().coerceAtLeast(2), passes = 3)
        return Pair(width, height, glass, sharp, small).also { cached = it }
    }

    /** The mock's 160% saturation on frosted glass. */
    fun frostPaint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })
    }

    /**
     * Synthwave: the sunset photo scaled to cover the screen and centred, then lightly darkened at the
     * top and bottom so the clock and the cards' edges stay readable. False when it cannot be decoded.
     */
    private fun paintPhoto(resources: Resources, canvas: Canvas, width: Int, height: Int): Boolean {
        val photo = try {
            // The wallpaper is 3440x1440: decode it at the smallest power-of-two size that still covers
            // the screen (1720x720 on the 1280x480 unit), a quarter of the memory of the full image.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, R.drawable.sw_bg, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
            BitmapFactory.decodeResource(resources, R.drawable.sw_bg, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) { // OutOfMemoryError included: the painted coast still works.
            AppLog.w("SynthwaveScene: background photo unavailable: ${e.message}")
            null
        } ?: return false
        val scale = max(width / photo.width.toFloat(), height / photo.height.toFloat())
        val w = photo.width * scale
        val h = photo.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        canvas.drawBitmap(photo, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        photo.recycle()
        val shade = Paint()
        shade.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0x40000000, Color.TRANSPARENT, Color.TRANSPARENT, 0x59000000),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        return true
    }

    private const val DESIGN_W = 1280f
    private const val DESIGN_H = 480f

    /** The palm from the mock, drawn at (x, 480) scaled by s. */
    private val PALM = listOf(
        "M0 0 C 6 -60, 4 -140, 14 -220 L 22 -220 C 14 -140, 18 -60, 12 0 Z",
        "M18 -222 C -20 -250, -70 -240, -100 -205 C -60 -228, -20 -230, 18 -215 Z",
        "M18 -222 C 50 -260, 110 -255, 140 -215 C 100 -238, 60 -240, 20 -214 Z",
        "M18 -224 C 0 -280, -40 -300, -60 -300 C -30 -280, -5 -255, 14 -218 Z",
        "M18 -224 C 40 -285, 90 -300, 110 -290 C 75 -278, 45 -255, 22 -218 Z",
        "M18 -220 C -10 -200, -40 -160, -46 -130 C -25 -165, -5 -190, 18 -212 Z",
        "M20 -220 C 55 -200, 80 -165, 86 -135 C 65 -170, 45 -192, 22 -212 Z",
    )
    private val PALMS = listOf(60f to 1.25f, 250f to 0.9f, 1180f to 1.15f, 1060f to 0.75f)

    private fun paint(canvas: Canvas, width: Int, height: Int) {
        canvas.save()
        canvas.scale(width / DESIGN_W, height / DESIGN_H)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(0f, 0f, 0f, DESIGN_H,
            intArrayOf(0xFF1B0A33.toInt(), 0xFF5A1A6E.toInt(), 0xFFE2407F.toInt(), 0xFFFF9A4D.toInt(), 0xFFFF9A4D.toInt()),
            floatArrayOf(0f, 0.35f, 0.6f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, DESIGN_W, DESIGN_H, paint)

        paint.shader = LinearGradient(0f, 180f, 0f, 480f, 0xFFFFE08A.toInt(), 0xFFFF4F8B.toInt(), Shader.TileMode.CLAMP)
        paint.alpha = 242
        canvas.drawCircle(700f, 330f, 150f, paint)
        paint.shader = null
        paint.alpha = 255

        paint.color = 0xE6E2407F.toInt()
        canvas.drawRect(540f, 300f, 860f, 306f, paint)
        canvas.drawRect(540f, 316f, 860f, 324f, paint)
        canvas.drawRect(540f, 334f, 860f, 344f, paint)

        paint.color = 0xD92A0F3F.toInt()
        for (b in listOf(floatArrayOf(880f, 250f, 40f, 110f), floatArrayOf(925f, 215f, 34f, 145f),
            floatArrayOf(965f, 265f, 50f, 95f), floatArrayOf(1020f, 235f, 28f, 125f), floatArrayOf(1052f, 275f, 60f, 85f))) {
            canvas.drawRect(b[0], b[1], b[0] + b[2], b[1] + b[3], paint)
        }

        paint.shader = LinearGradient(0f, 350f, 0f, 480f, 0xFF3A1F6B.toInt(), 0xFF0A0718.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 350f, DESIGN_W, DESIGN_H, paint)
        paint.shader = null

        paint.color = 0x8CFF7AB0.toInt()
        paint.strokeWidth = 2f
        canvas.drawLine(590f, 362f, 810f, 362f, paint)
        canvas.drawLine(620f, 378f, 780f, 378f, paint)
        canvas.drawLine(650f, 396f, 750f, 396f, paint)
        canvas.drawLine(675f, 416f, 725f, 416f, paint)

        paint.color = 0xFF12061C.toInt()
        val palm = Path()
        PALM.forEach { palm.addPath(PathParser.createPathFromPathData(it)) }
        val placed = Path()
        val matrix = Matrix()
        for ((x, s) in PALMS) {
            matrix.setScale(s, s)
            matrix.postTranslate(x, DESIGN_H)
            palm.transform(matrix, placed)
            canvas.drawPath(placed, paint)
        }

        paint.shader = LinearGradient(0f, 0f, 0f, DESIGN_H,
            intArrayOf(0x40000000, Color.TRANSPARENT, Color.TRANSPARENT, 0x8C000000.toInt()),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, DESIGN_W, DESIGN_H, paint)
        canvas.restore()
    }

    /** Centres and radii as fractions of the width, measured off the 1280x480 tvOS reference. */
    private val BLOBS = listOf(
        floatArrayOf(0.30f, 0.22f, 0.40f) to 0xFF5A67D8.toInt(),
        floatArrayOf(0.56f, 0.70f, 0.36f) to 0xFF3182CE.toInt(),
        floatArrayOf(0.88f, 1.05f, 0.44f) to 0xFFD53F8C.toInt(),
    )

    /** Glass: the old TvAmbientView, three soft pools of light on near-black. */
    private fun paintAmbient(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(0xFF08080F.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val w = width.toFloat()
        for ((geometry, color) in BLOBS) {
            val rgb = color and 0x00FFFFFF
            paint.shader = RadialGradient(geometry[0] * w, geometry[1] * height, geometry[2] * w,
                intArrayOf(rgb or (158 shl 24), rgb or (77 shl 24), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
        }
    }

    /** In-place box blur, [passes] times each way; three passes come close to a Gaussian. */
    private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        val scratch = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurLines(pixels, scratch, w, h, radius, horizontal = true)
            blurLines(scratch, pixels, w, h, radius, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun blurLines(from: IntArray, to: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            val stride = if (horizontal) 1 else w
            fun at(i: Int): Int = from[base + i.coerceIn(0, length - 1) * stride]
            var a = 0; var red = 0; var green = 0; var blue = 0
            for (i in -r..r) {
                val c = at(i)
                a += c ushr 24; red += (c shr 16) and 0xFF; green += (c shr 8) and 0xFF; blue += c and 0xFF
            }
            for (i in 0 until length) {
                to[base + i * stride] = ((a / window) shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
                val out = at(i - r)
                val add = at(i + r + 1)
                a += (add ushr 24) - (out ushr 24)
                red += ((add shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                green += ((add shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                blue += (add and 0xFF) - (out and 0xFF)
            }
        }
    }
}

/**
 * The scene as a background drawable, for screens that cannot hold a [SynthwaveBackdropView]
 * (the CarPlay connecting screen). [blurred] draws it frosted, with [veil] over it for legible text.
 */
class SynthwaveSceneDrawable(
    private val resources: Resources,
    private val blurred: Boolean,
    private val veil: Int = Color.TRANSPARENT,
    private val glass: Boolean = false,
) : Drawable() {
    private val paint = if (blurred) SynthwaveScene.frostPaint() else Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scene = SynthwaveScene.get(resources, b.width(), b.height(), glass)
        val bitmap = if (blurred) scene.blurred else scene.sharp
        src.set(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, src, b, paint)
        if (Color.alpha(veil) > 0) canvas.drawColor(veil)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
