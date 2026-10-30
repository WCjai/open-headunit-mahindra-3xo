package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Calls through a TopWay head unit's Bluetooth module.
 *
 * See [TopwayBluetooth] for the interface and why the module, not this app, carries the voice.
 * This class:
 *  1. tracks the module's call state, caller and link state from `com.tw.bt`'s broadcasts, so
 *     `CommManager` can route the Call / End Call keys and the home screen can show a call card;
 *  2. answers and hangs up through the module when asked;
 *  3. while a projection runs, asks `com.tw.bt` once per connected link to release the
 *     Bluetooth-music audio source, so the phone's A2DP stream stops fighting Android Auto's audio,
 *     and gives it back when the projection ends;
 *  4. on the unit's first start after a boot, has `com.tw.bt` claim that source, which otherwise
 *     only happens when someone opens its music page: until then the car plays nothing from the
 *     phone.
 *
 * It listens while a projection session runs or while the home screen has it attached, and not
 * otherwise. On a unit without `com.tw.bt` nothing is registered and [callState] stays IDLE.
 */
class TopwayCallBridge(context: Context) {

    private companion object {
        /** About a minute of asking for com.tw.bt after a boot before giving up. */
        const val START_ACQUIRE_TRIES = 20
        const val START_ACQUIRE_RETRY_MS = 3_000L
    }

    private val app = context.applicationContext

    @Volatile var callState: TopwayBluetooth.CallState = TopwayBluetooth.CallState.IDLE
        private set

    @Volatile var moduleConnected: Boolean = false
        private set

    /**
     * The caller's name: the module's, or else the phonebook's entry for [callerNumber]; empty when
     * neither knows the number. The module names a caller only when it holds a phonebook itself.
     */
    @Volatile var callerName: String = ""
        private set
        get() = field.ifBlank { TopwayContacts.nameFor(callerNumber).orEmpty() }
    @Volatile var callerNumber: String = ""
        private set

    /**
     * Told on the main thread whenever the call state changes. The projection uses it to put Android
     * Auto back over `com.tw.bt`'s call screen, which opens at the same moment.
     */
    @Volatile var onCallStateChanged: ((TopwayBluetooth.CallState) -> Unit)? = null

    /** Told on the main thread on any change the home screen shows: call, caller or link. */
    @Volatile var onHomeStateChanged: (() -> Unit)? = null

    /** The car's own Bluetooth name, as the module reports it; empty until it does. */
    @Volatile var moduleName: String = ""
        private set

    /** More screens told of the same changes as [onHomeStateChanged]. Main thread. */
    private val stateListeners = LinkedHashSet<() -> Unit>()

    private var registered = false
    private var sessionActive = false
    private var homeAttachCount = 0
    private val homeAttached: Boolean get() = homeAttachCount > 0
    private var releasedForThisLink = false

    private val main = Handler(Looper.getMainLooper())
    private var startAcquireDone = false
    private var startAcquireTries = 0

    /**
     * After a boot `com.tw.bt`'s service can come up after this app; until it does, nothing answers
     * the link query and a claim sent now would be lost. Asks again until it answers.
     */
    private val startAcquireRetry = object : Runnable {
        override fun run() {
            if (startAcquireDone || startAcquireTries >= START_ACQUIRE_TRIES) return
            startAcquireTries++
            queryLink()
            main.postDelayed(this, START_ACQUIRE_RETRY_MS)
        }
    }

    val isAvailable: Boolean by lazy {
        try {
            app.packageManager.getPackageInfo(TopwayBluetooth.PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (val action = intent?.action) {
                TopwayBluetooth.ACTION_CALL_STATE -> onCallState(intent)
                TopwayBluetooth.ACTION_LINK_STATE -> onLinkState(intent)
                else -> TopwayBluetooth.callStateOfAction(action)?.let {
                    intent?.getStringExtra(TopwayBluetooth.EXTRA_NAME)?.let { name -> callerName = name }
                    intent?.getStringExtra(TopwayBluetooth.EXTRA_NUMBER)?.let { number -> callerNumber = number }
                    setCallState(it, "assistant broadcast $action")
                }
            }
        }
    }

    /** Called on the main thread when a projection session starts. */
    fun onSessionStarted() {
        if (!isAvailable) return
        sessionActive = true
        releasedForThisLink = false
        ensureRegistered()
        // The answer arrives as ACTION_LINK_STATE and drives the music release.
        send(TopwayBluetooth.ACTION_LINK_COMMAND, TopwayBluetooth.LINK_QUERY)
    }

    /** Called on the main thread when the projection session ends. */
    fun onSessionEnded() {
        sessionActive = false
        if (releasedForThisLink && moduleConnected) {
            // The music was let go for Android Auto; with it gone the phone's Bluetooth audio should
            // reach the car again without a visit to com.tw.bt's music page.
            releasedForThisLink = false
            AppLog.i("TopwayCallBridge: Android Auto ended, asking com.tw.bt to take Bluetooth music back")
            sendPlain(TopwayBluetooth.ACTION_ACQUIRE_BT_MUSIC)
        }
        unregisterIfUnused()
        if (!homeAttached) setCallState(TopwayBluetooth.CallState.IDLE, "session ended")
    }

    /** A screen (the home, the Bluetooth screen) starts showing call and link state. Main thread. */
    fun attachHome() {
        if (!isAvailable) return
        homeAttachCount++
        ensureRegistered()
        queryLink()
        if (!startAcquireDone) {
            main.removeCallbacks(startAcquireRetry)
            main.postDelayed(startAcquireRetry, START_ACQUIRE_RETRY_MS)
        }
    }

    /**
     * Called on `com.tw.bt`'s first answer, so its service, and the receiver for the claim, are up.
     * Once per process; [TopwayBluetooth.acquireMusicAtStart] keeps it to the start after a boot.
     */
    private fun maybeAcquireMusicAtStart() {
        if (startAcquireDone) return
        startAcquireDone = true
        main.removeCallbacks(startAcquireRetry)
        val sinceBoot = SystemClock.elapsedRealtime()
        val radioPlaying = TopwayRadio.get(app).isPlaying
        if (!TopwayBluetooth.acquireMusicAtStart(sinceBoot, alreadyDone = false, projecting = sessionActive, radioPlaying = radioPlaying)) {
            AppLog.i("TopwayCallBridge: not claiming Bluetooth music at start " +
                "(${sinceBoot / 1000}s after boot, session=$sessionActive, radio=$radioPlaying)")
            return
        }
        AppLog.i("TopwayCallBridge: first start after boot, asking com.tw.bt to claim the car's audio for " +
            "Bluetooth music, as opening its music page does")
        sendPlain(TopwayBluetooth.ACTION_ACQUIRE_BT_MUSIC)
    }

    /** That screen is gone. Main thread. */
    fun detachHome() {
        if (!homeAttached) return
        homeAttachCount--
        if (!homeAttached) onHomeStateChanged = null
        unregisterIfUnused()
    }

    fun addStateListener(listener: () -> Unit) { stateListeners.add(listener) }

    fun removeStateListener(listener: () -> Unit) { stateListeners.remove(listener) }

    /** Asks `com.tw.bt` for the link; the answer arrives as a state change. */
    fun queryLink() = send(TopwayBluetooth.ACTION_LINK_COMMAND, TopwayBluetooth.LINK_QUERY)

    /** Sends one `com.txznet.launcher.bt.action.send` command ([TopwayBluetooth.LINK_DISCONNECT] and so on). */
    fun linkCommand(command: Int) = send(TopwayBluetooth.ACTION_LINK_COMMAND, command)

    private fun notifyState() {
        onHomeStateChanged?.invoke()
        stateListeners.toList().forEach { it() }
    }

    fun answer() {
        AppLog.i("TopwayCallBridge: answering through the module")
        send(TopwayBluetooth.ACTION_CALL_COMMAND, TopwayBluetooth.CALL_ANSWER)
    }

    fun endCall() {
        AppLog.i("TopwayCallBridge: ending the call through the module (state=$callState)")
        send(TopwayBluetooth.ACTION_CALL_COMMAND, TopwayBluetooth.CALL_END)
    }

    private fun ensureRegistered() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(TopwayBluetooth.ACTION_CALL_STATE)
            addAction(TopwayBluetooth.ACTION_LINK_STATE)
            TopwayBluetooth.AIOS_CALL_ACTIONS.keys.forEach { addAction(it) }
        }
        // Exported: the sender is com.tw.bt.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        AppLog.i("TopwayCallBridge: listening to com.tw.bt for calls (session=$sessionActive, home=$homeAttached)")
    }

    private fun unregisterIfUnused() {
        if (!registered || sessionActive || homeAttached) return
        try {
            app.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
        registered = false
    }

    private fun onCallState(intent: Intent) {
        // The same action also carries the phonebook; TopwayContacts reads that one.
        if (TopwayBluetooth.isContactsBroadcast(intent.extras)) return
        val next = TopwayBluetooth.callStateOf(intent.getIntExtra(TopwayBluetooth.EXTRA_CALL_STATE, 0))
        callerName = intent.getStringExtra(TopwayBluetooth.EXTRA_NAME).orEmpty()
        callerNumber = intent.getStringExtra(TopwayBluetooth.EXTRA_NUMBER).orEmpty()
        // The number stays out of the log; whether one was present is enough to debug with.
        setCallState(next, "call broadcast, number present=${callerNumber.isNotEmpty()}")
        notifyState()
    }

    private fun setCallState(next: TopwayBluetooth.CallState, source: String) {
        if (next == callState) return
        AppLog.i("TopwayCallBridge: module call state $callState -> $next ($source)")
        callState = next
        if (!next.inCall) {
            callerName = ""
            callerNumber = ""
        }
        onCallStateChanged?.invoke(next)
        notifyState()
    }

    private fun onLinkState(intent: Intent) {
        val connected = TopwayBluetooth.linkConnectedOf(intent.getIntExtra(TopwayBluetooth.EXTRA_ACTION, -1)) ?: return
        maybeAcquireMusicAtStart()
        intent.getStringExtra(TopwayBluetooth.EXTRA_BT_NAME)?.takeIf { it.isNotBlank() }?.let {
            if (it != moduleName) {
                moduleName = it
                notifyState()
            }
        }
        if (connected != moduleConnected) {
            AppLog.i("TopwayCallBridge: module link ${if (connected) "connected" else "not connected"} " +
                "(module name=${intent.getStringExtra(TopwayBluetooth.EXTRA_BT_NAME)})")
            moduleConnected = connected
            if (!connected) releasedForThisLink = false
            notifyState()
        }
        if (connected && sessionActive && !releasedForThisLink) {
            releasedForThisLink = true
            AppLog.i("TopwayCallBridge: asking com.tw.bt to release Bluetooth music while Android Auto plays")
            sendPlain(TopwayBluetooth.ACTION_RELEASE_BT_MUSIC)
        }
        if (!connected && sessionActive) {
            AppLog.w("TopwayCallBridge: no phone on the car's Bluetooth module. Calls are only heard in the " +
                "car when the phone is paired in the car's own Bluetooth app")
        }
    }

    /** A `com.tw.bt` broadcast with no extras: the music claim and release. */
    /**
     * The radio was switched off: give the speakers back to the phone's Bluetooth music, as
     * opening com.tw.bt's music page would. True when a phone is there to take them.
     */
    fun returnToBluetoothMusic(): Boolean {
        if (!isAvailable || !moduleConnected || sessionActive) return false
        AppLog.i("TopwayCallBridge: radio off, asking com.tw.bt to take the car's audio back for Bluetooth music")
        sendPlain(TopwayBluetooth.ACTION_ACQUIRE_BT_MUSIC)
        return true
    }

    private fun sendPlain(action: String) {
        try {
            app.sendBroadcast(Intent(action).setPackage(TopwayBluetooth.PACKAGE))
        } catch (e: Exception) {
            AppLog.w("TopwayCallBridge: could not reach com.tw.bt: ${e.message}")
        }
    }

    private fun send(action: String, command: Int) {
        try {
            app.sendBroadcast(Intent(action).setPackage(TopwayBluetooth.PACKAGE).putExtra(TopwayBluetooth.EXTRA_ACTION, command))
        } catch (e: Exception) {
            AppLog.w("TopwayCallBridge: could not reach com.tw.bt: ${e.message}")
        }
    }
}
