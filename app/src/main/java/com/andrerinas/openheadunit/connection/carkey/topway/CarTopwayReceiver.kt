package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.TopwayKey
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale

/**
 * Steering-wheel and panel keys on a TopWay ("TW") head unit.
 *
 * How a button reaches us, from the stock learning app (`com.tw.keypad`) and Bluetooth app
 * (`com.tw.bt`): the wheel's buttons are resistor ladders the MCU reads. Learning (see
 * [TopwayWheel]) stores "this resistance means function 0x13" inside the MCU. From then on every
 * press arrives on TWUtil message 0x201 with the function id in `arg2`. A button that was never
 * learned sends nothing, to any app.
 *
 * This receiver opens only 0x201 and only listens (plus one read-only query of the unit's customer
 * id for the status line), so the unit's own key handling is untouched. Each press becomes a
 * virtual key code; `CommManager` turns it into an Android Auto action through the keymap, or
 * through [TopwayWheel.defaultKeyCodeFor] when the user has not mapped it.
 *
 * Session-scoped like the FYT receiver: open while a projection runs or the keymap screen is
 * learning, closed otherwise.
 */
class CarTopwayReceiver : CarKeyReceiver {

    companion object {
        private const val DEBUG_ACTION = "com.andrerinas.openheadunit.DEBUG_KEY"

        /** Customer-id query: `write(0xFFF1)` answers with it, as the factory Settings app uses. */
        const val QUERY_CUSTOMER_ID = 0xFFF1

        /**
         * One line on how the key channel is doing, for the keymap screen: there is no other place a
         * user without adb can see why a wheel button does nothing. Null on a unit without TWUtil.
         */
        @Volatile var status: String? = null
            private set

        /**
         * When the wheel's answer or hang-up button was last pressed (elapsedRealtime), 0 if never.
         * `com.tw.service` opens the unit's phone screen over the projection in answer to that very
         * press, so the projection uses this to tell that cover from any other.
         */
        @Volatile var lastWheelCallKeyAtMs = 0L
            private set

        /** Told on the main thread right after a wheel call key, so a covered projection can come back. */
        @Volatile var onWheelCallKey: (() -> Unit)? = null

        private fun report(line: String) {
            status = line
            AppLog.i("CarKeyReceiver: TopWay key channel $line")
        }
    }

    @Volatile private var context: Context? = null
    private var channel: TwUtilChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var messagesSeen = 0

    override val isSupported: Boolean get() = TwUtilChannel.available

    override val isSUNeeded = false

    override val isSessionScoped = true

    override fun register(context: Context) {
        val app = context.applicationContext
        this.context = app
        // TWUtil may bind its message delivery to the looper of the thread that builds it.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (this.context === app && channel == null) register(app) }
            return
        }
        // The constructor the learning screen found answers on this unit; the music app's
        // no-argument one (it listens to these keys too) until one is known.
        val ctor = TwUtilChannel.workingCtor(app) ?: TwUtilChannel.NO_ARG
        val ch = TwUtilChannel("keys", shortArrayOf(TopwayKey.MSG_KEY.toShort()), TwUtilChannel.ctorArgOf(ctor))
        val error = ch.open(app) { what, arg1, arg2, obj -> onMessage(what, arg1, arg2, obj) }
        if (error != null) {
            report(error)
            return
        }
        channel = ch
        messagesSeen = 0
        val id = ch.write(QUERY_CUSTOMER_ID)
            ?.let { String.format(Locale.US, ", unit customer ID %d (0x%02X)", it, it) }
            .orEmpty()
        report("listening on TWUtil 0x201 via ${TwUtilChannel.describeCtor(ctor)}$id - press a learned steering-wheel button")
    }

    override fun unregister() {
        channel?.close()
        channel = null
        mainHandler.removeCallbacksAndMessages(null)
        context = null
    }

    private fun onMessage(what: Int, arg1: Int, arg2: Int, obj: Any?) {
        val ctx = context ?: return
        val raw = TopwayKey.describe(what, arg1, arg2, obj)
        messagesSeen++
        if (messagesSeen == 1) report("receiving - first message: $raw")
        val id = TopwayKey.keyId(what, arg2)
        val keyCode = if (id == TopwayKey.NO_KEY) -1 else TopwayKey.toKeyCode(id)

        AppLog.i(
            if (id == TopwayKey.NO_KEY) "CarKeyReceiver: TopWay message $raw (not a key)"
            else "CarKeyReceiver: TopWay key ${TopwayKey.nameOf(id) ?: "unnamed"} 0x%02X -> keycode %d [%s]".format(id, keyCode, raw)
        )

        // The keymap screen's debugger shows every message, keys or not.
        ctx.sendBroadcast(Intent(DEBUG_ACTION).apply {
            setPackage(ctx.packageName)
            putExtra("action", "TopWay TWUtil")
            if (keyCode >= 0) putExtra("keyCode", keyCode)
            putExtra("topway", raw)
        })

        if (keyCode < 0) return
        if (id == 0x18 || id == 0x19) {
            lastWheelCallKeyAtMs = android.os.SystemClock.elapsedRealtime()
            mainHandler.post { onWheelCallKey?.invoke() }
        }
        // CommManager keeps its key state unsynchronised and is driven from the main thread
        // everywhere else; deliver there. A message carries no release, so it becomes a click,
        // and CommManager's debounce folds a press/release pair or a repeat into one.
        mainHandler.post {
            val current = context ?: return@post
            handleClick(current, keyCode)
        }
    }
}
