package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Open Headunit addition, not part of upstream DiPlay: everything the host app sets or reads on the
 * CarPlay stack. With the host's hooks set, DiPlay's own screens never show: its start page,
 * settings and three-finger menu give way to the host's home and settings, and the CarPlay screen
 * carries the host's look and Home button.
 */
object OpenHeadunitBridge {

    /** Told on the main thread when a CarPlay session starts (true) or ends (false). */
    @Volatile var onSessionChanged: ((Boolean) -> Unit)? = null

    /**
     * The host screen to show where DiPlay would open one of its own pages: "home" (Back, the car
     * icon in CarPlay), "settings", or anything else DiPlay asks for. Null keeps DiPlay's page.
     */
    @Volatile var hostScreenIntent: ((Context, String) -> Intent?)? = null

    /** The car's name on the iPhone (Settings > General > CarPlay) and in AirPlay. */
    @Volatile var carName: String = "Open Headunit"

    /** DiPlay's three-finger swipe-down menu over CarPlay. Off: settings live in the host app. */
    @Volatile var quickMenuEnabled: Boolean = false

    /** Adds the host's controls (its Home button) over the CarPlay screen, once, when it is built. */
    @Volatile var decorateScreen: ((Activity, FrameLayout) -> Unit)? = null

    /**
     * Restyles the connecting screen shown before the iPhone's picture arrives: its background
     * ([panel]), the CarPlay icon, the title, the status lines and the buttons. Called whenever
     * DiPlay repaints it (a day/night change), after DiPlay's own colours.
     */
    @Volatile var paintWaitingScreen: ((panel: View, icon: ImageView, title: TextView, lines: List<TextView>, buttons: List<TextView>) -> Unit)? = null

    private fun controller() = CarPlayBackgroundSession.snapshot()?.controller

    /** A CarPlay session is running (the iPhone is projecting), whether or not its screen shows. */
    fun sessionActive(): Boolean = controller()?.activeAirPlaySessionToken() != null

    /** The CarPlay screen exists: connecting, projecting or behind another screen. */
    fun screenOpen(): Boolean = CarPlayScreen.current() != null

    /** The CarPlay screen exists and is at least started (visible or about to be). */
    fun screenVisible(): Boolean =
        CarPlayScreen.current()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    /** The iPhone reports a call (ringing, dialling or active) over CarPlay. */
    fun inCall(): Boolean = BydNavigationOutputs.carPlayCall() != null

    fun answerCall(): Boolean = controller()?.answerCall() == true

    fun endCall(): Boolean = controller()?.endCall() == true

    fun requestSiri(): Boolean = controller()?.requestSiri() == true

    fun playPause(): Boolean = controller()?.sendMediaButton(CarPlayMediaButton.PLAY_PAUSE) == true

    /** A steering-wheel media key the host caught itself: 0 play/pause, 1 next, -1 previous. */
    fun wheelMediaKey(direction: Int) = CarPlayMediaKeys.onDirectWheelKey(
        when {
            direction > 0 -> CarPlayMediaButton.NEXT
            direction < 0 -> CarPlayMediaButton.PREVIOUS
            else -> CarPlayMediaButton.PLAY_PAUSE
        },
    )
    fun next(): Boolean = controller()?.sendMediaButton(CarPlayMediaButton.NEXT) == true
    fun previous(): Boolean = controller()?.sendMediaButton(CarPlayMediaButton.PREVIOUS) == true

    fun nowPlaying(): CarPlayNowPlaying? = controller()?.nowPlayingSnapshot()

    /**
     * Ends CarPlay for an iPhone that was unplugged: DiPlay itself only sees a lost link and keeps
     * retrying, so the session would stay "connected". Stopping it also closes the CarPlay screen;
     * a screen still connecting, with no session yet, is closed directly.
     */
    fun endSession(reason: String) {
        android.util.Log.i("OpenHeadunit", "Ending CarPlay: $reason")
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop()
        } else {
            CarPlayScreen.current()?.finish()
        }
        sessionChanged(false)
    }

    /** Brings the CarPlay screen back to the front, e.g. after the unit's phone screen covered it. */
    fun showScreen(context: Context) {
        runCatching {
            context.startActivity(
                CarPlayScreen.intent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    /**
     * The car's CarPlay settings, written once by the host app: USB instead of DiPlay's wireless
     * default (in both places DiPlay keeps it), the driving side, the BYD first-run guide marked
     * seen, and the car's own name where DiPlay would send "DiPlay" or "BYD".
     */
    fun applyCarDefaults(context: Context, rightHandDrive: Boolean, manufacturer: String, model: String, carIconLabel: String) {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.USB)
        AirPlayPersistence.saveRightHandDrive(context, rightHandDrive)
        com.shilapi.xcertplay.setup.SetupGuide.markSeen(context)
        AirPlayPersistence.saveManufacturer(context, manufacturer)
        AirPlayPersistence.saveModel(context, model)
        AirPlayPersistence.saveOemLabel(context, carIconLabel)
    }

    /**
     * Car defaults added later: DiPlay's own echo canceller on CarPlay calls. They play on the
     * media speakers here (OpenHeadunitFlags.callsOnMediaPath), without Android's in-call echo
     * cancelling, so the other person would hear themselves through the car's microphone.
     */
    fun applyCallDefaults(context: Context) {
        AirPlayPersistence.saveCallEchoCancellation(context, true)
    }

    /**
     * Cancels DiPlay's own update checks (they ask DiPlay's GitHub for new releases), which its
     * settings screen schedules once and WorkManager then repeats daily, even with that screen gone.
     */
    fun cancelDiPlayUpdateChecks(context: Context) {
        runCatching {
            val manager = androidx.work.WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork("diplay-periodic-update-check")
            manager.cancelUniqueWork("diplay-open-update-check")
            manager.cancelAllWorkByTag(com.shilapi.xcertplay.update.BackgroundUpdateWorker::class.java.name)
        }
    }

    /** The host screen for [page], or null to keep DiPlay's. */
    internal fun hostScreen(context: Context, page: String): Intent? =
        runCatching { hostScreenIntent?.invoke(context, page) }.getOrNull()

    internal fun sessionChanged(active: Boolean) {
        val listener = onSessionChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { listener(active) }
    }
}
