package com.andrerinas.openheadunit.connection.carkey.topway

import android.view.KeyEvent
import com.andrerinas.openheadunit.input.TopwayKey

/**
 * TopWay steering-wheel learning, as the stock "Steering Wheel" app (`com.tw.keypad`) does it, and
 * the Android Auto action each learned function gets by default.
 *
 * How the unit works. The wheel's buttons share one or two wires (KEY1/KEY2) and differ only in
 * resistance. The MCU measures it; learning teaches the MCU which resistance is which *function*
 * (the ids in [TopwayKey]). After learning, every press arrives on TWUtil 0x201 with that function
 * id, for any app that listens. Nothing else is involved, so a button that was never learned is
 * silent everywhere.
 *
 * The protocol, on TWUtil message 0x103 (panel keys use 0x108 the same way):
 * ```
 * write(0x103, 0xFF)        ask for the learned table and the learning state
 * write(0x103, 0, 0)        start learning            (the stock "START" button)
 * write(0x103, 0, 2)        end learning and keep it  (the stock "END" button)
 * write(0x103, 1, id)       the next button pressed means function `id`
 * write(0x103, 2)           forget every learned button
 * write(0x10B, 0xFF)        ask for the impedance
 * write(0x10B, 9, level)    impedance: 1 Low, 2 High (the stock Middle button sends nothing)
 * ```
 * and the MCU answers on 0x103:
 * ```
 * arg1 = 0        arg2 = 0 while learning, anything else when not
 * arg1 = 1..127   one learned entry: arg2 = id | secondId << 8 | flags << 16
 * arg1 = 130      arg2 = 1..3: how badly the last button read (try another impedance), else fine
 * arg1 = 144..147 long-press settings (not used here)
 * ```
 * and on 0x10B with a byte array whose byte 9 is the impedance level.
 *
 * Pure: no Android beyond key code constants.
 */
object TopwayWheel {

    const val MSG_WHEEL = 0x103
    const val MSG_PANEL = 0x108
    const val MSG_IMPEDANCE = 0x10B

    /**
     * The ids a learning channel opens: exactly the stock app's set, 0x102..0x112 plus 0x603, 0x605
     * and 0x204, in case the MCU answers only a client registered the way the stock one is.
     */
    val CHANNEL_IDS = shortArrayOf(258, 259, 260, 262, 264, 265, 266, 267, 272, 274, 1539, 1541, 516)

    // --- Commands: the arguments to TwUtilChannel.write --------------------------------------

    val QUERY = intArrayOf(MSG_WHEEL, 0xFF)
    val START_LEARNING = intArrayOf(MSG_WHEEL, 0, 0)
    val END_LEARNING = intArrayOf(MSG_WHEEL, 0, 2)
    val CLEAR_ALL = intArrayOf(MSG_WHEEL, 2)
    val QUERY_IMPEDANCE = intArrayOf(MSG_IMPEDANCE, 0xFF)

    fun learn(functionId: Int) = intArrayOf(MSG_WHEEL, 1, functionId and 0xFF)

    fun setImpedance(level: Impedance) = intArrayOf(MSG_IMPEDANCE, 9, level.value)

    enum class Impedance(val value: Int) {
        LOW(1),
        HIGH(2),
        MIDDLE(3);

        companion object {
            fun of(value: Int): Impedance? = values().firstOrNull { it.value == value }
        }
    }

    // --- Replies ------------------------------------------------------------------------------

    sealed class Reply {
        data class LearningState(val learning: Boolean) : Reply()
        data class Learned(val functionId: Int, val secondFunctionId: Int, val flags: Int) : Reply()
        /** 1..3: how far off the last reading was; the stock app suggests another impedance. */
        data class ReadQuality(val warning: Int) : Reply()
        data class ImpedanceState(val level: Impedance?) : Reply()
        object Other : Reply()
    }

    fun decode(what: Int, arg1: Int, arg2: Int, obj: Any?): Reply = when (what) {
        MSG_WHEEL -> when {
            arg1 == 0 -> Reply.LearningState(learning = arg2 == 0)
            arg1 == 130 -> Reply.ReadQuality(if (arg2 in 1..3) arg2 else 0)
            arg1 in 1..127 -> {
                val id = arg2 and 0xFF
                if (id == 0) Reply.Other
                else Reply.Learned(id, (arg2 shr 8) and 0xFF, (arg2 shr 16) and 0xFF)
            }
            else -> Reply.Other
        }
        MSG_IMPEDANCE -> {
            val bytes = obj as? ByteArray
            if (bytes != null && bytes.size > 9) Reply.ImpedanceState(Impedance.of(bytes[9].toInt() and 0xFF))
            else Reply.Other
        }
        else -> Reply.Other
    }

    // --- Functions offered for learning, and their default Android Auto action ---------------

    /**
     * @param keyCode what the press does in Android Auto when the keymap has no entry for it, or
     *   null when the unit acts on the button itself (volume, mute) and a second action would be a
     *   double one.
     */
    data class Function(val id: Int, val label: String, val keyCode: Int?)

    /** In the order the learning screen lists them. */
    val FUNCTIONS = listOf(
        Function(0x13, "Next track", KeyEvent.KEYCODE_MEDIA_NEXT),
        Function(0x15, "Previous track", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        Function(0x14, "Play / Pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        Function(0x18, "Answer call", KeyEvent.KEYCODE_CALL),
        Function(0x19, "Reject / hang up", KeyEvent.KEYCODE_ENDCALL),
        Function(0x5A, "Voice assistant", KeyEvent.KEYCODE_SEARCH),
        Function(0x20, "Navigation", 65538),
        Function(0x12, "Back", KeyEvent.KEYCODE_BACK),
        Function(0x01, "Volume up", null),
        Function(0x02, "Volume down", null),
        Function(0x03, "Mute", null),
        Function(0x29, "Music", KeyEvent.KEYCODE_MUSIC),
        Function(0x3A, "Up", KeyEvent.KEYCODE_DPAD_UP),
        Function(0x3B, "Down", KeyEvent.KEYCODE_DPAD_DOWN),
        Function(0x3C, "Left", KeyEvent.KEYCODE_DPAD_LEFT),
        Function(0x3D, "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
        Function(0x3F, "Enter", KeyEvent.KEYCODE_DPAD_CENTER),
        Function(0x16, "Fast forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
        Function(0x17, "Rewind", KeyEvent.KEYCODE_MEDIA_REWIND),
        Function(0x10, "Home (unit)", null),
        Function(0x25, "Mode (unit)", null),
    )

    private val DEFAULTS: Map<Int, Int> = FUNCTIONS.mapNotNull { f -> f.keyCode?.let { f.id to it } }.toMap()

    /**
     * The Android Auto key a TopWay virtual key code means when the user has not mapped it, or null
     * for none. This is what lets a button learned on the unit work in Android Auto with no second
     * mapping step; the keymap still overrides it.
     */
    fun defaultKeyCodeFor(virtualKeyCode: Int): Int? {
        val id = TopwayKey.keyIdOf(virtualKeyCode) ?: return null
        return DEFAULTS[id]
    }
}
