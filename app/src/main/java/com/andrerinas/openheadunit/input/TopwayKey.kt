package com.andrerinas.openheadunit.input

import java.util.Locale

/**
 * Decodes the steering-wheel and panel key messages a TopWay ("TW") head unit hands out through its
 * framework class `android.tw.john.TWUtil`, and maps them onto the key codes the rest of this app
 * speaks.
 *
 * Where this comes from:
 *  - The stock learning app (`com.tw.keypad`, "Steering Wheel") programs the MCU through TWUtil:
 *    `write(0x103, 1, keyId)` binds the next resistor value the MCU reads to a function, and the
 *    function ids are the table in that app's `assets/key_config.json`, reproduced in [NAMES].
 *    The learning app never consumes a live press itself.
 *  - The live press travels on message [MSG_KEY] (0x201). TW's own apps open that id, and inject a
 *    key into the unit with `write(0x201, 1, keyId)` using the same ids (next 0x13, play/pause
 *    0x14, previous 0x15 — confirmed independently in TheCarLauncher's TW port).
 *
 * The receiving side matches the unit's own Bluetooth app (`com.tw.bt`), whose service handles
 * `what = 0x201` with `arg2` = key id (0x18 answer, 0x19 hang up) and `arg1 == 1` for a short press
 * (anything else is a long one). `arg1` is logged but not interpreted here; every message reaches
 * the keymap screen's debugger with its raw fields.
 *
 * Every id becomes a virtual code — none is mapped straight to an Android key code — so a button
 * does nothing in Android Auto until the user binds it in the keymap, exactly like [BydPanelKey].
 * The TW system keeps acting on its own keys (volume, its Bluetooth app's answer/hang-up), and an
 * unbound button staying inert here is what keeps one press from doing two things.
 *
 * Pure: no Android.
 */
object TopwayKey {

    /** The fully qualified framework class on TopWay firmware. Absent everywhere else. */
    const val TWUTIL_CLASS = "android.tw.john.TWUtil"

    /** Key events (0x201). */
    const val MSG_KEY = 0x201

    /** Not a key message. */
    const val NO_KEY = -1

    /**
     * Base for this receiver's virtual key codes. Clear of the other proprietary ranges (NWD
     * `1000+`, Eryanet `2001+`, BZ `3001+`, BYD `4000..4255`); `CommManager` drops anything at or
     * above 1000 that the user has not mapped.
     */
    const val VIRTUAL_BASE = 5000

    /** One virtual code per possible key id. */
    val VIRTUAL_RANGE = VIRTUAL_BASE until (VIRTUAL_BASE + 256)

    /** Function ids from the stock app's `key_config.json` and `key_config_panel.json`. */
    private val NAMES = mapOf(
        0x01 to "Vol+",
        0x02 to "Vol-",
        0x03 to "Mute",
        0x04 to "Power",
        0x07 to "Eject",
        0x08 to "AudioOff",
        0x09 to "PanelUp",
        0x0A to "PanelDown",
        0x0B to "Rolling",
        0x10 to "Home",
        0x11 to "Menu",
        0x12 to "Back",
        0x13 to "Next",
        0x14 to "PlayPause",
        0x15 to "Prev",
        0x16 to "FastForward",
        0x17 to "Rewind",
        0x18 to "Answer",
        0x19 to "HangUp",
        0x1A to "Backlight",
        0x1F to "Button",
        0x20 to "Navi",
        0x21 to "Radio",
        0x22 to "Apps",
        0x23 to "iPod",
        0x24 to "Disc",
        0x25 to "Mode",
        0x26 to "Screen",
        0x27 to "TV",
        0x28 to "Aux",
        0x29 to "Music",
        0x2C to "Video",
        0x2D to "Photo",
        0x2E to "Media",
        0x30 to "0",
        0x31 to "1",
        0x32 to "2",
        0x33 to "3",
        0x34 to "4",
        0x35 to "5",
        0x36 to "6",
        0x37 to "7",
        0x38 to "8",
        0x39 to "9",
        0x3A to "Up",
        0x3B to "Down",
        0x3C to "Left",
        0x3D to "Right",
        0x3F to "Enter",
        0x42 to "EQ",
        0x4E to "Star",
        0x4F to "Pound",
        0x50 to "Help",
        0x51 to "Recent",
        0x52 to "Screenshot",
        0x54 to "AMS",
        0x59 to "Settings",
        0x5A to "Voice",
        0x66 to "Recorder",
        0x67 to "Delete",
        0x71 to "FrontCam",
        0x72 to "DVR",
        0x73 to "Loud",
        0x74 to "AirOnOff",
        0x75 to "AirAC",
        0x76 to "AirMode",
        0x77 to "AirCirculation",
        0x78 to "FrontHeat",
        0x79 to "Heat",
        0x7A to "FanUp",
        0x7B to "FanDown",
        0x7C to "TempUp",
        0x7D to "TempDown",
        0x7E to "AirMaxAC",
        0x7F to "AirShow",
    )

    /**
     * The key id carried by a TWUtil message, or [NO_KEY] when it is not a key message.
     *
     * Id 0 is "no key" in the stock app's own table handling, so it is never a press.
     */
    fun keyId(what: Int, arg2: Int): Int {
        if (what != MSG_KEY) return NO_KEY
        val id = arg2 and 0xFF
        return if (id == 0) NO_KEY else id
    }

    /** The key code to hand to `CarKeyReceiver.handleClick` for a key id. */
    fun toKeyCode(keyId: Int): Int = VIRTUAL_BASE + (keyId and 0xFF)

    /** The key id behind a virtual key code, or null when [keyCode] is not one of ours. */
    fun keyIdOf(keyCode: Int): Int? =
        if (keyCode in VIRTUAL_RANGE) keyCode - VIRTUAL_BASE else null

    /** The stock app's name for a key id, or null for one it does not list. */
    fun nameOf(keyId: Int): String? = NAMES[keyId]

    /**
     * A name for one of our virtual key codes, for the keymap screen, or null for any other code.
     * Not translated, like the `KeyEvent.keyCodeToString` names it sits beside: the hex is what a
     * user quotes in a bug report.
     */
    fun label(keyCode: Int): String? {
        val id = keyIdOf(keyCode) ?: return null
        return String.format(Locale.US, "TopWay %s (0x%02X)", nameOf(id) ?: "key", id)
    }

    /** One log/debugger line describing a raw message, so an unexpected layout stays readable. */
    fun describe(what: Int, arg1: Int, arg2: Int, obj: Any?): String {
        val payload = when (obj) {
            null -> ""
            is ByteArray -> " obj=[" + obj.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) } + "]"
            is IntArray -> " obj=" + obj.joinToString(",", "[", "]")
            else -> " obj=$obj"
        }
        return String.format(Locale.US, "what=0x%X arg1=%d (0x%X) arg2=%d (0x%X)%s", what, arg1, arg1, arg2, arg2, payload)
    }
}
