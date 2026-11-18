package com.andrerinas.openheadunit.main.settings

import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.theme.UiTheme
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The Themes tab of Head Unit Settings: the look of the home, Radio, More Apps, Sound and call
 * screens. One switch per look, only one on at a time; the choice saves at once and every screen
 * takes it the next time it comes to the front. Layouts, tile sizes and icons are the same in both.
 */
class ThemesSettingsSection(private val context: Context, private val refresh: () -> Unit) {

    fun items(): List<SettingItem> {
        val current = UiTheme.current(context)
        val items = mutableListOf<SettingItem>()
        items.add(SettingItem.CategoryHeader("themeLook", R.string.theme_category_look))
        for (theme in UiTheme.values()) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "theme_${theme.key}",
                nameResId = theme.label,
                descriptionResId = theme.summary,
                isChecked = theme == current,
                onCheckedChanged = { on ->
                    // Turning the current look off does nothing: one is always on.
                    if (on && theme != UiTheme.current(context)) {
                        AppLog.i("Settings: theme = ${theme.key}")
                        UiTheme.set(context, theme)
                    }
                    refresh()
                },
            ))
        }
        items.add(SettingItem.InfoBanner("themeInfo", R.string.theme_info))
        return items
    }
}
