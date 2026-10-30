package com.andrerinas.openheadunit.connection.carkey.topway

import android.view.KeyEvent
import com.andrerinas.openheadunit.input.TopwayKey

/**
 * The broadcast interface of a TopWay head unit's Bluetooth app (`com.tw.bt`), and the decisions
 * built on it. Recovered from that app's `ATBluetoothService` and its two assistant bridges (TXZ
 * and AISpeech).
 *
 * Why this exists: on these units hands-free calls run through TopWay's own Bluetooth module, not
 * Android's Bluetooth stack. `com.tw.bt` drives that module over the MCU, and on every call-state
 * change it switches the car's speakers to the call itself (`TWUtil.write(0x503, state)`). A call
 * that reaches the module is therefore heard in the car with no help from us. A call whose
 * hands-free link landed on Android's Bluetooth instead (where only TopWay's own CarPlay/AA app
 * knows how to route it) is silent — which is the "caller not heard" report.
 *
 * So this app does not carry call audio. It reads the module's call state, answers and hangs up
 * through the module, and asks it to let go of Bluetooth music while Android Auto plays.
 *
 * All of `com.tw.bt`'s receivers are registered at runtime without a permission, so these
 * broadcasts reach it from any app; we still address it by package.
 *
 * Pure: constants and decisions only.
 */
object TopwayBluetooth {

    const val PACKAGE = "com.tw.bt"

    // --- From com.tw.bt ---------------------------------------------------------------------

    /** Call state: extras `callState` (int, see [CallState]), `name`, `number`. Sent on every change. */
    const val ACTION_CALL_STATE = "com.txznet.launcher.call.action.recv"

    /** Module link state: extras `action` (1 connected, 2 not), `btName`, `btAddress` (the connected phone). */
    const val ACTION_LINK_STATE = "com.txznet.launcher.bt.action.recv"

    const val EXTRA_CALL_STATE = "callState"
    const val EXTRA_NAME = "name"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_ACTION = "action"
    const val EXTRA_BT_NAME = "btName"
    const val EXTRA_BT_ADDRESS = "btAddress"

    const val LINK_CONNECTED = 1
    const val LINK_DISCONNECTED = 2

    // --- To com.tw.bt -----------------------------------------------------------------------

    /** Call commands, extra `action`: [CALL_ANSWER] or [CALL_END]. */
    const val ACTION_CALL_COMMAND = "com.txznet.launcher.call.action.send"

    /** `write(10, 1)` on the module: accept the ringing call. */
    const val CALL_ANSWER = 5

    /** Rejects a ringing call (`write(10, 2)`) and hangs up any other (`write(10, 0)`); the app picks. */
    const val CALL_END = 6

    /** Link commands, extra `action`: [LINK_QUERY] makes it answer with [ACTION_LINK_STATE]. */
    const val ACTION_LINK_COMMAND = "com.txznet.launcher.bt.action.send"
    const val LINK_QUERY = 3
    /** Opens `com.tw.bt` on its pairing page: search, pair, connect and forget phones. */
    const val LINK_OPEN_PAIRING = 4
    /** Disconnects the phone (`write(6, 0)` and `write(38, 0, 0)` on the module). */
    const val LINK_DISCONNECT = 5
    /** Opens `com.tw.bt` on its settings page: the car's Bluetooth name and PIN. */
    const val LINK_OPEN_SETTINGS = 6
    /** Opens `com.tw.bt` on its phonebook page, where the phone's contacts are downloaded. Connected only. */
    const val LINK_OPEN_PHONEBOOK = 7

    /**
     * Asks `com.tw.bt` how the module stands. It answers [ACTION_STATUS_OPEN] with extras `name`
     * (the car's Bluetooth name) and `pin` when no phone is connected, or [ACTION_STATUS_CONNECTED].
     */
    const val ACTION_CHECK_STATUS = "net.easyconn.bt.checkstatus"
    const val ACTION_STATUS_OPEN = "net.easyconn.bt.opened"
    const val ACTION_STATUS_CONNECTED = "net.easyconn.bt.connected"
    const val EXTRA_PIN = "pin"

    /**
     * After the module downloads the phone's phonebook, `com.tw.bt` sends it on [ACTION_CALL_STATE]
     * with `action` = this string (not the call broadcast's int) and `contact` = a JSON array of
     * `{"number", "name"}`. The call-state reader must skip it.
     */
    const val CONTACTS_ACTION = "bluetooth.contact"
    const val EXTRA_CONTACTS = "contact"

    /** Whether a [ACTION_CALL_STATE] broadcast carries the phonebook rather than a call state. */
    fun isContactsBroadcast(extras: android.os.Bundle?): Boolean =
        extras?.get(EXTRA_ACTION) == CONTACTS_ACTION

    /**
     * Makes `com.tw.bt` release the car's Bluetooth-music audio source (`write(0x9E11, 0xC0, 0x88)`).
     * The hook TopWay added for phone-mirroring apps, whose audio would otherwise fight the module's
     * A2DP stream.
     */
    const val ACTION_RELEASE_BT_MUSIC = "net.easyconn.a2dp.release"

    /**
     * The opposite of [ACTION_RELEASE_BT_MUSIC]: `com.tw.bt` claims the car's audio for Bluetooth
     * music (`write(0x9E11, 0xC0, 8)`), exactly what opening its music page does. Until something
     * claims it after a boot the car plays nothing from the phone: the module only starts A2DP on a
     * new link while that source is the current one.
     */
    const val ACTION_ACQUIRE_BT_MUSIC = "net.easyconn.a2dp.acquire"

    /** How long after a boot the home still counts as the unit's first start. */
    const val START_ACQUIRE_WINDOW_MS = 10 * 60_000L

    /**
     * Whether to claim Bluetooth music for the user now, as they used to by opening `com.tw.bt`'s
     * music page after every boot. Only once, only near the boot, and never over a source someone
     * chose: Android Auto playing or the radio on.
     */
    fun acquireMusicAtStart(sinceBootMs: Long, alreadyDone: Boolean, projecting: Boolean, radioPlaying: Boolean): Boolean =
        !alreadyDone && !projecting && !radioPlaying && sinceBootMs in 0 until START_ACQUIRE_WINDOW_MS

    /**
     * The second set of call-state broadcasts `com.tw.bt` sends (for the AISpeech assistant), one
     * action per state. Listened to as well, so the call state is known even on a firmware that
     * sends only one of the two sets.
     */
    val AIOS_CALL_ACTIONS: Map<String, CallState> = mapOf(
        "action.bt.AIOS_INCOMING_RINGING" to CallState.INCOMING,
        "action.bt.AIOS_INCOMING_OFFHOOK" to CallState.ACTIVE,
        "action.bt.AIOS_OUTGOING_RINGING" to CallState.OUTGOING,
        "action.bt.AIOS_OUTGOING_OFFHOOK" to CallState.ACTIVE,
        "action.bt.AIOS_INCOMING_IDLE" to CallState.IDLE,
        "action.bt.AIOS_OUTGOING_IDLE" to CallState.IDLE,
    )

    /** The call state an AISpeech-style broadcast action announces, or null for another action. */
    fun callStateOfAction(action: String?): CallState? = AIOS_CALL_ACTIONS[action]

    /**
     * How long after the wheel's answer or hang-up button the projection treats a cover as the
     * unit's phone screen. `com.tw.service` opens that screen in answer to the same press, so a
     * cover inside this window is it; outside it, the cover is something else (the reverse camera,
     * say) and is left alone.
     */
    const val WHEEL_CALL_KEY_WINDOW_MS = 4_000L

    /** Whether a wheel call key at [lastAtMs] still explains a cover at [nowMs]. 0 means never pressed. */
    fun wheelCallKeyRecent(nowMs: Long, lastAtMs: Long): Boolean =
        lastAtMs > 0L && nowMs - lastAtMs in 0 until WHEEL_CALL_KEY_WINDOW_MS

    /** `com.tw.bt`'s call states, from its `be` field. */
    enum class CallState {
        IDLE,
        INCOMING,
        ACTIVE,
        OUTGOING;

        val inCall: Boolean get() = this != IDLE
    }

    fun callStateOf(code: Int): CallState = when (code) {
        1 -> CallState.INCOMING
        2 -> CallState.ACTIVE
        3, 4 -> CallState.OUTGOING
        else -> CallState.IDLE
    }

    /** True/false for a link report, null for an `action` this decoder does not know. */
    fun linkConnectedOf(action: Int): Boolean? = when (action) {
        LINK_CONNECTED -> true
        LINK_DISCONNECTED -> false
        else -> null
    }

    /** Where a call key should go. */
    enum class CallKeyRoute {
        /** Not a call situation the unit owns: Android Auto, as before. */
        ANDROID_AUTO,

        /** Answer through the module. */
        UNIT_ANSWER,

        /** Reject or hang up through the module. */
        UNIT_END,

        /**
         * The unit already acted on this very press: `com.tw.bt` handles the wheel's own answer and
         * hang-up keys (0x18 / 0x19 on TWUtil 0x201) itself while its module is connected. Sending
         * it again would answer twice or hang up a call the first press just answered.
         */
        ALREADY_HANDLED,
    }

    private val WHEEL_ANSWER = TopwayKey.toKeyCode(0x18)
    private val WHEEL_HANG_UP = TopwayKey.toKeyCode(0x19)

    /**
     * @param physicalCode the code the button arrived with.
     * @param logicalCode  what the keymap made of it.
     * @param callState    the module's last reported call state; IDLE on a unit without it.
     * @param moduleConnected whether the module reports a phone connected.
     */
    fun routeCallKey(
        physicalCode: Int,
        logicalCode: Int,
        callState: CallState,
        moduleConnected: Boolean,
    ): CallKeyRoute {
        val isAnswer = logicalCode == KeyEvent.KEYCODE_CALL
        val isEnd = logicalCode == KeyEvent.KEYCODE_ENDCALL
        if (!isAnswer && !isEnd) return CallKeyRoute.ANDROID_AUTO

        // The wheel's own answer and hang-up buttons. com.tw.bt answers or ends a call on these
        // itself, but only a call that is on the car's Bluetooth module. When the phone's call is
        // somewhere else (the usual case while projecting), the unit only opens its phone screen and
        // the call is untouched, so Android Auto has to get the press. Measured on a TS-series unit:
        // holding these back left calls unanswerable from the wheel.
        if (physicalCode == WHEEL_ANSWER || physicalCode == WHEEL_HANG_UP) {
            return if (moduleConnected && callState.inCall) CallKeyRoute.ALREADY_HANDLED
            else CallKeyRoute.ANDROID_AUTO
        }

        // A different button the user mapped to a call action (not the wheel's call keys): route it
        // through the module when we can see a call there, otherwise leave it to Android Auto.
        if (!callState.inCall) return CallKeyRoute.ANDROID_AUTO
        return when {
            isAnswer && callState == CallState.INCOMING -> CallKeyRoute.UNIT_ANSWER
            isAnswer -> CallKeyRoute.ANDROID_AUTO
            else -> CallKeyRoute.UNIT_END
        }
    }
}
