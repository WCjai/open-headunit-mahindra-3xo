package com.andrerinas.openheadunit.carplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.carkey.topway.TwUtilChannel
import com.andrerinas.openheadunit.input.TopwayKey
import com.andrerinas.openheadunit.utils.AppLog
import com.shilapi.xcertplay.OpenHeadunitBridge

/**
 * TopWay steering-wheel keys during a CarPlay session.
 *
 * Keys come from the same TWUtil 0x201 message the Android Auto receiver reads (closed while CarPlay
 * runs). Answer and hang-up go to the iPhone's call, Voice starts Siri, and next, previous and
 * play/pause go to CarPlay's media. `com.tw.service` passes the media keys to Android only once the
 * unit's source is Android, which happens when CarPlay first plays: before that, with CarPlay paused,
 * the wheel did nothing until play was touched on screen. So they are sent from here, and DiPlay
 * drops the unit's forwarded copy of the same press. Only while the unit plays its Bluetooth source
 * with the phone on the module are they left to it: it sends them to the phone over Bluetooth.
 *
 * The unit's firmware opens its own phone screen on the answer/hang-up press. When CarPlay was on
 * screen at that moment it is brought back once the phone screen has opened.
 */
object CarPlayTopwayKeys {

    private const val KEY_ANSWER = 0x18
    private const val KEY_HANG_UP = 0x19
    private const val KEY_VOICE = 0x5A
    private const val KEY_NEXT = 0x13
    private const val KEY_PLAY_PAUSE = 0x14
    private const val KEY_PREVIOUS = 0x15
    /** The unit's current audio source; 8 is its Bluetooth music. */
    private const val SOURCE_FILE = "/sys/class/tw/misc/source"
    private const val SOURCE_BLUETOOTH = 8
    /** com.tw.service opens com.tw.bt's call screen right after the press; return after it. */
    private const val RETURN_DELAY_MS = 700L

    private val main = Handler(Looper.getMainLooper())
    private var channel: TwUtilChannel? = null
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** From [CarPlaySession]: listens while a CarPlay session runs. */
    fun onSessionChanged(active: Boolean) {
        if (!TwUtilChannel.available) return
        if (active) open() else close()
    }

    private fun open() {
        val ctx = appContext ?: return
        if (channel != null) return
        val ctor = TwUtilChannel.workingCtor(ctx) ?: TwUtilChannel.NO_ARG
        val ch = TwUtilChannel("carplay-keys", shortArrayOf(TopwayKey.MSG_KEY.toShort()), TwUtilChannel.ctorArgOf(ctor))
        val error = ch.open(ctx) { what, arg1, arg2, _ -> main.post { onKey(what, arg1, arg2) } }
        if (error != null) {
            AppLog.w("CarPlay keys: TopWay key channel unavailable: $error")
            return
        }
        channel = ch
        AppLog.i("CarPlay keys: listening for TopWay wheel keys")
    }

    private fun close() {
        channel?.close()
        channel = null
        main.removeCallbacksAndMessages(null)
        AppLog.i("CarPlay keys: stopped")
    }

    private fun onKey(what: Int, arg1: Int, arg2: Int) {
        val ctx = appContext ?: return
        val id = TopwayKey.keyId(what, arg2)
        if (id == TopwayKey.NO_KEY) return
        if (!OpenHeadunitBridge.sessionActive()) {
            // Logged, so a press that did nothing shows up in a report.
            AppLog.i("CarPlay keys: TopWay key 0x%02X with no CarPlay picture session; sending what can be sent".format(id))
        }
        val screenWasVisible = OpenHeadunitBridge.screenVisible()
        when (id) {
            KEY_ANSWER -> {
                if (OpenHeadunitBridge.inCall()) {
                    AppLog.i("CarPlay keys: answer (sent=${OpenHeadunitBridge.answerCall()})")
                } else {
                    // Siri has its own key (Voice); like Android Auto, answer with no call does nothing.
                    AppLog.i("CarPlay keys: answer key with no CarPlay call")
                }
                if (screenWasVisible) returnToCarPlay(ctx)
            }
            KEY_HANG_UP -> {
                // Always sent: the iPhone ignores a Drop with no call, and DiPlay's own call tracking can lag
                // the iPhone, which used to swallow the press.
                val inCall = OpenHeadunitBridge.inCall()
                AppLog.i("CarPlay keys: hang up (inCall=$inCall sent=${OpenHeadunitBridge.endCall()})")
                if (screenWasVisible) returnToCarPlay(ctx)
            }
            KEY_VOICE -> AppLog.i("CarPlay keys: voice, Siri (sent=${OpenHeadunitBridge.requestSiri()})")
            KEY_NEXT, KEY_PLAY_PAUSE, KEY_PREVIOUS -> {
                val name = when (id) { KEY_NEXT -> "next"; KEY_PREVIOUS -> "previous"; else -> "play/pause" }
                if (leftToBluetooth(ctx)) {
                    AppLog.i("CarPlay keys: $name left to the unit (its Bluetooth source has the phone)")
                } else {
                    AppLog.i("CarPlay keys: $name to CarPlay")
                    OpenHeadunitBridge.wheelMediaKey(when (id) { KEY_NEXT -> 1; KEY_PREVIOUS -> -1; else -> 0 })
                }
            }
            else -> AppLog.i("CarPlay keys: TopWay key 0x%02X (arg1=%d) left to the unit".format(id, arg1))
        }
    }

    /** The unit plays its Bluetooth source and the phone is on the module: the unit sends the key itself. */
    private fun leftToBluetooth(ctx: Context): Boolean {
        val source = try {
            java.io.File(SOURCE_FILE).readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
        return source == SOURCE_BLUETOOTH && App.provide(ctx).topwayCallBridge.moduleConnected
    }

    private fun returnToCarPlay(ctx: Context) {
        main.postDelayed({
            if (OpenHeadunitBridge.sessionActive() || OpenHeadunitBridge.screenOpen()) OpenHeadunitBridge.showScreen(ctx)
        }, RETURN_DELAY_MS)
    }
}
