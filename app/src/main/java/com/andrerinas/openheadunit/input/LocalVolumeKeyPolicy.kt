package com.andrerinas.openheadunit.input

import android.view.KeyEvent

/**
 * Whether a key should change this head unit's own volume instead of travelling to the phone.
 *
 * Android Auto's audio is played here, on our streams, so the phone cannot turn it up or down: a
 * volume key sent over the projection does nothing a driver can hear. A real volume `KeyEvent`
 * never reaches this decision — the projection activity hands those straight to Android, which
 * changes the stream itself. What does reach it is a button the user has *bound* to Volume Up,
 * Volume Down or Mute in the keymap, typically a proprietary steering-wheel key (TopWay, BYD, ...)
 * that has no volume meaning of its own until then.
 *
 * Only an explicit binding counts. A volume code that arrives unmapped keeps its old path, so
 * nothing changes for a unit that already worked.
 *
 * Pure: no Android beyond the key code constants.
 */
object LocalVolumeKeyPolicy {

    enum class Action { RAISE, LOWER, TOGGLE_MUTE }

    /**
     * @param physicalCode the code the button arrived with.
     * @param logicalCode  what the keymap turned it into.
     * @return the local volume change to make, or null when the key goes to Android Auto as usual.
     */
    fun actionFor(physicalCode: Int, logicalCode: Int): Action? {
        if (physicalCode == logicalCode) return null
        return when (logicalCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Action.RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> Action.LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> Action.TOGGLE_MUTE
            else -> null
        }
    }
}
