package com.andrerinas.openheadunit.main.theme

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.andrerinas.openheadunit.R

/**
 * The look of the head-unit screens (home, Radio, More Apps, Sound, the call card, the CarPlay
 * waiting screen). Both looks share every layout, tile size and icon; a theme only fills in the
 * `ui*` attributes of attrs_ui_theme.xml (colours, glass, type) and picks the background scene.
 *
 * Every BaseActivity applies the chosen one before it inflates anything, and recreates itself when
 * the choice changed while it was in the background.
 */
enum class UiTheme(val key: String, val overlay: Int, val label: Int, val summary: Int) {
    SYNTHWAVE("synthwave", R.style.UiTheme_Synthwave, R.string.theme_synthwave, R.string.theme_synthwave_summary),
    GLASS("glass", R.style.UiTheme_Glass, R.string.theme_glass, R.string.theme_glass_summary);

    companion object {
        private const val PREFS = "ui_theme"
        private const val KEY = "theme"

        @Volatile private var cached: UiTheme? = null
        private val listeners = LinkedHashSet<(UiTheme) -> Unit>()

        fun current(context: Context): UiTheme = cached ?: run {
            val key = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            (values().firstOrNull { it.key == key } ?: SYNTHWAVE).also { cached = it }
        }

        /** Main thread. Saves [theme]; open screens pick it up when they next come to the front. */
        fun set(context: Context, theme: UiTheme) {
            if (current(context) == theme) return
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.key).apply()
            cached = theme
            listeners.toList().forEach { it(theme) }
        }

        fun addListener(listener: (UiTheme) -> Unit) { listeners.add(listener) }
        fun removeListener(listener: (UiTheme) -> Unit) { listeners.remove(listener) }

        /** Before setContentView. Returns the theme applied, to compare on resume. */
        fun applyTo(activity: Activity): UiTheme {
            val theme = current(activity)
            activity.theme.applyStyle(theme.overlay, true)
            return theme
        }

        /** [context] with the current look's attributes, whether or not its activity applied them. */
        fun themed(context: Context): Context {
            val probe = TypedValue()
            if (context.theme.resolveAttribute(R.attr.uiCardBg, probe, true)) return context
            return ContextThemeWrapper(context, current(context).overlay)
        }

        fun color(context: Context, attr: Int): Int {
            val c = themed(context)
            val value = TypedValue()
            c.theme.resolveAttribute(attr, value, true)
            return if (value.resourceId != 0) ContextCompat.getColor(c, value.resourceId) else value.data
        }

        fun resource(context: Context, attr: Int): Int {
            val value = TypedValue()
            themed(context).theme.resolveAttribute(attr, value, true)
            return value.resourceId
        }

        fun drawable(context: Context, attr: Int): Drawable? {
            val c = themed(context)
            return resource(c, attr).takeIf { it != 0 }?.let { ContextCompat.getDrawable(c, it) }
        }

        fun font(context: Context, attr: Int): Typeface? =
            resource(context, attr).takeIf { it != 0 }?.let { runCatching { ResourcesCompat.getFont(context, it) }.getOrNull() }

        fun caps(context: Context): Boolean {
            val value = TypedValue()
            themed(context).theme.resolveAttribute(R.attr.uiCaps, value, true)
            return value.data != 0
        }

        fun isGlass(context: Context): Boolean = current(context) == GLASS
    }
}
