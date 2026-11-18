package com.andrerinas.openheadunit.main.synthwave

import com.andrerinas.openheadunit.main.theme.UiTheme
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.format.DateFormat
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import java.util.Date
import java.util.Locale

/** Small pieces the synthwave screens share. */
object Synthwave {

    /** Gives [view] the panel look (ScopeDrawable). */
    fun scope(view: View) {
        view.background = ScopeDrawable(view.resources.displayMetrics.density, UiTheme.isGlass(view.context))
    }

    /**
     * A wide button's label as the mock sets it: the first word in heavy capitals, the rest in the
     * narrow face, so "Connect over USB" reads "CONNECT over USB".
     */
    fun buttonLabel(context: Context, text: CharSequence, bigSp: Int = 19, smallSp: Int = 15): CharSequence {
        val full = text.toString().trim()
        if (full.isEmpty()) return full
        if (UiTheme.isGlass(context)) {
            // Glass: the label as written, in one face.
            val out = SpannableStringBuilder(full)
            out.setSpan(FontSpan(UiTheme.font(context, R.attr.uiFontDisplay)), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(AbsoluteSizeSpan(bigSp - 2, true), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return out
        }
        val split = full.indexOf(' ')
        val head = if (split < 0) full else full.substring(0, split)
        val tail = if (split < 0) "" else full.substring(split)
        val big = UiTheme.font(context, R.attr.uiFontDisplay)
        val small = UiTheme.font(context, R.attr.uiFontLabel)
        val out = SpannableStringBuilder()
        out.append(head.uppercase(Locale.getDefault()))
        out.setSpan(FontSpan(big), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(AbsoluteSizeSpan(bigSp, true), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (tail.isNotEmpty()) {
            val start = out.length
            out.append(tail)
            out.setSpan(FontSpan(small), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(AbsoluteSizeSpan(smallSp, true), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** TypefaceSpan(Typeface) is Android 9; this does the same on 8. */
    private class FontSpan(private val typeface: Typeface?) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = apply(paint)
        override fun updateMeasureState(paint: TextPaint) = apply(paint)
        private fun apply(paint: Paint) {
            typeface ?: return
            paint.typeface = typeface
        }
    }

    /**
     * The clock and the Bluetooth and Wi-Fi badges at the top of every synthwave screen. The date
     * is the home's only. Call [render] on resume and every few seconds.
     */
    class TopBar(
        private val clock: TextView,
        private val date: TextView?,
        private val bluetooth: ImageView?,
        private val wifi: ImageView?,
    ) {
        private val context = clock.context

        fun render() {
            val now = Date()
            clock.text = DateFormat.getTimeFormat(context).format(now)
            date?.text = DateFormat.format("EEE, d MMM", now)
            bluetooth?.let { renderBluetooth(it) }
            wifi?.let { renderWifi(it) }
        }

        private fun renderBluetooth(icon: ImageView) {
            val bridge = App.provide(context).topwayCallBridge
            if (!bridge.isAvailable) {
                icon.visibility = View.GONE
                return
            }
            val on = bridge.moduleConnected
            icon.visibility = View.VISIBLE
            icon.alpha = if (on) 1f else DIM
            icon.contentDescription = context.getString(if (on) R.string.tv_bt_on else R.string.tv_bt_off)
        }

        private fun renderWifi(icon: ImageView) {
            val on = wifiConnected()
            icon.setImageResource(if (on) R.drawable.sw_ic_wifi else R.drawable.sw_ic_wifi_off)
            icon.alpha = if (on) 1f else DIM
            icon.contentDescription = context.getString(if (on) R.string.tv_wifi_on else R.string.tv_wifi_off)
        }

        @Suppress("DEPRECATION") // getNetworkInfo(TYPE_WIFI) is the one call that works on every unit.
        private fun wifiConnected(): Boolean = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
        } catch (e: Exception) {
            false
        }

        private companion object {
            const val DIM = 0.4f
        }
    }
}
