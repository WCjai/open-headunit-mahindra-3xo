package com.andrerinas.openheadunit.main.bento

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.carkey.topway.TwUtilChannel
import com.andrerinas.openheadunit.input.TopwayKey
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Keeps this app's own screens (the home, Bluetooth, Radio) in front when the unit's phone screen
 * jumps over them while no phone is projecting.
 *
 * `com.tw.service` opens `com.tw.bt`'s phone screen whenever the steering wheel's answer or
 * hang-up button is pressed, call or no call, and `com.tw.bt` opens it again when a call rings.
 * Neither can be stopped, so the screen that was covered is brought back:
 *  - during a call, the home, whose call card answers and hangs up;
 *  - otherwise, whichever of our screens was in front.
 *
 * A cover counts only when it comes within [WINDOW_MS] of one of those two causes, so an app the
 * user opens is left alone. While a phone projects, the projection screen does this itself.
 */
object TopwayForegroundKeeper {

    enum class Screen { HOME, BLUETOOTH, RADIO }

    private const val WINDOW_MS = 4_000L
    private const val FIRST_MS = 250L
    private const val RETRY_MS = 700L
    private const val MAX_ATTEMPTS = 4
    private const val KEY_ANSWER = 0x18
    private const val KEY_HANG_UP = 0x19

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var keyChannel: TwUtilChannel? = null

    private var resumed: Screen? = null
    private var lastPaused: Screen? = null
    private var pausedAtMs = 0L
    private var causeAtMs = 0L
    private var attempts = 0

    /** Main thread. Starts watching the wheel's call buttons; idempotent. */
    fun start(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        if (!TwUtilChannel.available) return
        // The wheel's keys go to every app that opens 0x201, so listening takes nothing away.
        val ctor = TwUtilChannel.workingCtor(ctx) ?: TwUtilChannel.NO_ARG
        val ch = TwUtilChannel("keeper-keys", shortArrayOf(TopwayKey.MSG_KEY.toShort()), TwUtilChannel.ctorArgOf(ctor))
        val error = ch.open(ctx) { what, _, arg2, _ ->
            if (what == TopwayKey.MSG_KEY && (arg2 == KEY_ANSWER || arg2 == KEY_HANG_UP)) {
                main.post { onCause("wheel call button") }
            }
        }
        if (error == null) keyChannel = ch else AppLog.w("TopwayForegroundKeeper: wheel keys unavailable: $error")
    }

    fun onResumed(screen: Screen) {
        resumed = screen
        main.removeCallbacks(raise)
    }

    fun onPaused(screen: Screen) {
        if (resumed == screen) resumed = null
        lastPaused = screen
        pausedAtMs = SystemClock.elapsedRealtime()
        if (recent(causeAtMs)) schedule()
    }

    /** A call rang, was answered or ended. Called by the home, which watches the call state. */
    fun onCallStateChanged() = onCause("call state")

    private fun onCause(reason: String) {
        causeAtMs = SystemClock.elapsedRealtime()
        attempts = 0
        // The cover may already be up (the press was handled before we heard of it), or still to come.
        if (resumed == null && recent(pausedAtMs)) {
            AppLog.i("TopwayForegroundKeeper: $reason while covered, bringing ${target()} back")
            schedule()
        }
    }

    private fun recent(atMs: Long) = atMs > 0 && SystemClock.elapsedRealtime() - atMs < WINDOW_MS

    private fun schedule() {
        main.removeCallbacks(raise)
        main.postDelayed(raise, FIRST_MS)
    }

    private fun target(): Screen {
        val ctx = app ?: return Screen.HOME
        val inCall = App.provide(ctx).topwayCallBridge.callState.inCall
        return if (inCall) Screen.HOME else lastPaused ?: Screen.HOME
    }

    private val raise = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (resumed != null || !recent(causeAtMs) || attempts >= MAX_ATTEMPTS) return
            val component = App.provide(ctx)
            if (component.commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) return
            if (!component.settings.raiseProjectionDuringCall) return
            attempts++
            val screen = target()
            AppLog.i("TopwayForegroundKeeper: bringing $screen back over the unit's phone screen (attempt $attempts)")
            try {
                ctx.startActivity(intentFor(ctx, screen))
            } catch (e: Exception) {
                AppLog.w("TopwayForegroundKeeper: could not bring $screen back: ${e.message}")
            }
            main.postDelayed(this, RETRY_MS)
        }
    }

    private fun intentFor(ctx: Context, screen: Screen): Intent = when (screen) {
        Screen.BLUETOOTH -> Intent(ctx, BentoBluetoothActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Screen.RADIO -> Intent(ctx, BentoRadioActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Screen.HOME -> homeIntent(ctx)
    }

    /**
     * The Home intent when this app is the launcher: Android always brings the home to the front
     * for it, the way the Home button does. Starting MainActivity by name only reorders its task,
     * which Android 9 ignored once another app had finished opening over the home.
     */
    fun homeIntent(ctx: Context): Intent {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return if (launcher?.activityInfo?.packageName == ctx.packageName) {
            home.setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
    }
}
