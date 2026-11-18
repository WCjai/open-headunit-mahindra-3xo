package com.andrerinas.openheadunit.carplay

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.theme.UiTheme
import com.andrerinas.openheadunit.main.bento.TopwayForegroundKeeper
import com.andrerinas.openheadunit.main.synthwave.SynthwaveSceneDrawable
import com.andrerinas.openheadunit.utils.AppLog
import com.shilapi.xcertplay.CarPlaySettings

/**
 * This app's look on the CarPlay screen: the round Home button in a bottom corner of the picture,
 * and the sunset behind the "Connecting to your iPhone" screen.
 */
object CarPlayScreenDecor {

    /**
     * The Home button: a dark glass disc in the bottom corner away from the driver, where CarPlay's
     * dock is not. CarPlay keeps running behind the home; its card there brings it back.
     */
    fun addHomeButton(activity: Activity, root: FrameLayout) {
        val density = activity.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val margin = (12 * density).toInt()
        val padding = (15 * density).toInt()
        val side = if (CarPlaySettings.rightHandDrive(activity)) Gravity.LEFT else Gravity.RIGHT
        val button = ImageView(activity).apply {
            id = R.id.projection_home_button
            setImageResource(R.drawable.sw_ic_home)
            setColorFilter(if (UiTheme.isGlass(context)) Color.BLACK else Color.WHITE)
            background = UiTheme.drawable(context, R.attr.uiHomeButtonBg)
            setPadding(padding, padding, padding, padding)
            contentDescription = activity.getString(R.string.tv_back_to_home)
            isClickable = true
            isFocusable = false
            elevation = 6 * density
            setOnClickListener {
                AppLog.i("CarPlay: Home button, showing the home; CarPlay keeps running")
                runCatching { activity.startActivity(TopwayForegroundKeeper.homeIntent(activity)) }
            }
        }
        root.addView(button, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or side).apply {
            bottomMargin = margin
            leftMargin = margin
            rightMargin = margin
        })
    }

    /** The connecting screen in the home's style (the current UiTheme): frosted scene, logo, type. */
    fun paintWaitingScreen(panel: View, icon: ImageView, title: TextView, lines: List<TextView>, buttons: List<TextView>) {
        val context = panel.context
        val density = context.resources.displayMetrics.density
        val glass = UiTheme.isGlass(context)
        panel.background = SynthwaveSceneDrawable(context.resources, blurred = true, veil = if (glass) 0x40000000 else 0x590A0414, glass = glass)
        icon.setImageResource(R.drawable.sw_ic_carplay)
        icon.setColorFilter(UiTheme.color(context, R.attr.uiAccent))
        val header = UiTheme.font(context, R.attr.uiFontDisplay)
        val label = UiTheme.font(context, R.attr.uiFontLabel)
        title.typeface = header
        val caps = UiTheme.caps(context)
        val shadow = if (glass) Color.TRANSPARENT else Color.BLACK
        title.isAllCaps = caps
        title.letterSpacing = if (glass) 0f else 0.03f
        title.setTextColor(UiTheme.color(context, R.attr.uiTextTitle))
        title.setShadowLayer(1f, 2f * density, 2f * density, shadow)
        lines.forEachIndexed { index, line ->
            line.typeface = label
            line.letterSpacing = if (glass) 0f else 0.02f
            line.setTextColor(if (index == 0) ContextCompat.getColor(context, R.color.tv_white) else UiTheme.color(context, R.attr.uiTextIce))
            line.setShadowLayer(1f, density, density, shadow)
        }
        buttons.forEach { button ->
            button.background = UiTheme.drawable(button.context, R.attr.uiBtnBg)
            button.setTextColor(Color.WHITE)
            button.typeface = header
            button.isAllCaps = caps
        }
    }
}
