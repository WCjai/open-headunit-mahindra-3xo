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
        /** Link drops per CarPlay session before a phone that keeps reconnecting is left alone. */
        const val MAX_CARPLAY_DROPS = 3
        /** After CarPlay ends, how often and how long to look for the phone's return to the module. */
        const val LINK_WATCH_MS = 5_000L
        const val LINK_WATCH_TRIES = 36
        /** After CarPlay, the first ask for the phone to reconnect, and the wait before asking again. */
        const val RECONNECT_FIRST_MS = 2_000L
        const val RECONNECT_RETRY_MS = 15_000L
        const val MAX_RECONNECTS = 3
        /** The module's ids and serial speed, exactly as com.tw.bt opens them (its C0008i). */
        val MODULE_IDS = shortArrayOf(513, 769, -25080, -25059)
        const val MODULE_CTOR = 8
        const val MODULE_BAUD = 115_200
        /** com.tw.bt's module command for "connect this paired phone": write(6, 1, 0, address). */
        const val MODULE_CONNECT = 6
        const val PREFS = "topway_call_bridge"
        const val KEY_PHONE_ADDRESS = "phone_address"
        const val KEY_RECONNECT_PENDING = "reconnect_pending"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The module's phone as com.tw.bt names it (its MAC, the form its connect command takes). */
    private var phoneAddress: String? = prefs.getString(KEY_PHONE_ADDRESS, null)

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
    /** Apple CarPlay runs (wired). Kept apart from Android Auto's [sessionActive]. */
    private var carPlayActive = false
    /** Bluetooth music was released for CarPlay and must be claimed back when it ends. */
    private var releasedForCarPlay = false
    /** The module's phone link was dropped because the iPhone asked (disableBluetooth). */
    private var droppedForCarPlay = false
    /** Drops in this CarPlay session, so a phone that keeps coming back is not fought forever. */
    private var carPlayDrops = 0
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

    /**
     * Apple CarPlay started (true) or ended (false). Main thread.
     *
     * While it runs, the module's Bluetooth music is released, as for Android Auto: CarPlay plays
     * the iPhone's audio itself. When it ends, the car's audio goes back to Bluetooth music, or,
     * when the link was dropped for CarPlay, as soon as the phone reconnects to the module. Without
     * that the phone's music stayed silent until com.tw.bt's music page was opened.
     */
    fun onCarPlayChanged(active: Boolean) {
        if (!isAvailable || active == carPlayActive) return
        carPlayActive = active
        if (active) {
            carPlayDrops = 0
            main.removeCallbacks(reconnect)
            ensureRegistered()
            if (moduleConnected) releaseMusicForCarPlay()
            queryLink()
            return
        }
        if (releasedForCarPlay && moduleConnected && !droppedForCarPlay) {
            releasedForCarPlay = false
            AppLog.i("TopwayCallBridge: CarPlay ended, asking com.tw.bt to take Bluetooth music back")
            sendPlain(TopwayBluetooth.ACTION_ACQUIRE_BT_MUSIC)
        }
        if (droppedForCarPlay) {
            AppLog.i("TopwayCallBridge: CarPlay ended; asking the module to reconnect the phone ($phoneAddress)")
            linkWatchLeft = LINK_WATCH_TRIES
            main.removeCallbacks(linkWatch)
            main.postDelayed(linkWatch, LINK_WATCH_MS)
            reconnectTries = 0
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, RECONNECT_FIRST_MS)
        }
        unregisterIfUnused()
    }

    /**
     * After CarPlay, asks the module to connect the phone it let go of, as com.tw.bt does when a paired
     * phone is tapped. An iPhone does not come back by itself after the car drops it. Tries again
     * while the link watch still sees no phone, [MAX_RECONNECTS] times in all.
     */
    private var reconnectTries = 0
    private val reconnect = object : Runnable {
        override fun run() {
            if (!droppedForCarPlay || carPlayActive || moduleConnected) return
            val address = phoneAddress
            if (address == null) {
                AppLog.w("TopwayCallBridge: CarPlay ended but the phone's Bluetooth address is unknown; reconnect it from the phone")
                return
            }
            if (reconnectTries >= MAX_RECONNECTS) {
                AppLog.w("TopwayCallBridge: the phone did not come back after $reconnectTries asks; reconnect it from the phone")
                return
            }
            reconnectTries++
            connectModulePhone(address)
            main.postDelayed({ queryLink() }, LINK_WATCH_MS)
            main.postDelayed(this, RECONNECT_RETRY_MS)
        }
    }

    /**
     * Sends the module "connect [address]", the command com.tw.bt sends from its pairing page. The
     * module's port is opened send-only and closed at once: com.tw.bt stays its only reader.
     */
    private fun connectModulePhone(address: String) {
        val channel = TwUtilChannel("bt-connect", MODULE_IDS, ctorArg = MODULE_CTOR, baud = MODULE_BAUD, listen = false)
        val error = channel.open(app) { _, _, _, _ -> }
        if (error != null) {
            AppLog.w("TopwayCallBridge: could not reach the Bluetooth module to reconnect the phone: $error")
            return
        }
        try {
            val result = channel.writeObjects(MODULE_CONNECT, 1, 0, address, null)
            AppLog.i("TopwayCallBridge: asked the module to reconnect $address (try $reconnectTries) -> $result")
        } finally {
            channel.close()
        }
    }

    private fun rememberPhone(address: String) {
        if (address == phoneAddress) return
        phoneAddress = address
        prefs.edit().putString(KEY_PHONE_ADDRESS, address).apply()
    }

    /** "90:2c:09:d7:72:9c" as the module writes it, "902C09D7729C"; null when it is not an address. */
    private fun moduleAddressOf(deviceId: String?): String? {
        val hex = deviceId?.replace(":", "")?.replace("-", "")?.uppercase() ?: return null
        return hex.takeIf { it.length == 12 && it.all { c -> c in "0123456789ABCDEF" } }
    }

    /** After CarPlay, asks the module about its link until the phone is back (the answer claims the music). */
    private var linkWatchLeft = 0
    private val linkWatch = object : Runnable {
        override fun run() {
            if (!droppedForCarPlay || carPlayActive || linkWatchLeft <= 0) return
            linkWatchLeft--
            queryLink()
            main.postDelayed(this, LINK_WATCH_MS)
        }
    }

    /**
     * Wired CarPlay: the iPhone sent disableBluetooth, asking the car to drop its Bluetooth link with
     * it, so calls and audio go over CarPlay. Kept, the iPhone sends a call's audio to the module's
     * hands-free link instead, and the module plays it through TopWay's phone path, which is silent
     * while the CarPlay screen is in front: the caller could not be heard. Main thread.
     */
    fun dropLinkForCarPlay(deviceId: String?) {
        if (!isAvailable || !carPlayActive) return
        if (!moduleConnected) {
            AppLog.i("TopwayCallBridge: CarPlay asked to drop Bluetooth (device=$deviceId); the module has no phone")
            return
        }
        if (carPlayDrops >= MAX_CARPLAY_DROPS) {
            AppLog.w("TopwayCallBridge: the phone keeps reconnecting to the module during CarPlay; leaving it")
            return
        }
        carPlayDrops++
        droppedForCarPlay = true
        if (phoneAddress == null) moduleAddressOf(deviceId)?.let { rememberPhone(it) }
        prefs.edit().putBoolean(KEY_RECONNECT_PENDING, true).apply()
        AppLog.i("TopwayCallBridge: CarPlay asked to drop Bluetooth (device=$deviceId); disconnecting the module's phone")
        linkCommand(TopwayBluetooth.LINK_DISCONNECT)
        // The module may not announce the drop by itself; its answer updates [moduleConnected].
        main.postDelayed({ queryLink() }, LINK_WATCH_MS)
    }

    private fun releaseMusicForCarPlay() {
        if (releasedForCarPlay) return
        releasedForCarPlay = true
        AppLog.i("TopwayCallBridge: asking com.tw.bt to release Bluetooth music while CarPlay plays")
        sendPlain(TopwayBluetooth.ACTION_RELEASE_BT_MUSIC)
    }

    /** A screen (the home, the Bluetooth screen) starts showing call and link state. Main thread. */
    fun attachHome() {
        if (!isAvailable) return
        homeAttachCount++
        ensureRegistered()
        queryLink()
        if (homeAttachCount == 1 && !carPlayActive && !droppedForCarPlay && prefs.getBoolean(KEY_RECONNECT_PENDING, false)) {
            // The app restarted while the phone was still dropped for CarPlay: bring it back now.
            AppLog.i("TopwayCallBridge: a phone dropped for CarPlay was not reconnected before a restart; asking again")
            droppedForCarPlay = true
            reconnectTries = 0
            main.postDelayed(reconnect, LINK_WATCH_MS)
        }
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
        if (!registered || sessionActive || homeAttached || carPlayActive || droppedForCarPlay) return
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
        val becameConnected = connected && !moduleConnected
        maybeAcquireMusicAtStart()
        intent.getStringExtra(TopwayBluetooth.EXTRA_BT_NAME)?.takeIf { it.isNotBlank() }?.let {
            if (it != moduleName) {
                moduleName = it
                notifyState()
            }
        }
        if (connected) {
            intent.getStringExtra(TopwayBluetooth.EXTRA_BT_ADDRESS)?.takeIf { it.isNotBlank() }?.let { rememberPhone(it) }
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
        if (connected && carPlayActive) {
            if (droppedForCarPlay) {
                // The module or the phone linked again while CarPlay runs: CarPlay carries the calls.
                if (becameConnected) dropLinkForCarPlay("reconnected")
            } else {
                releaseMusicForCarPlay()
            }
        }
        if (becameConnected && !carPlayActive && droppedForCarPlay) {
            // The phone is back on the module after CarPlay: its music plays in the car again.
            droppedForCarPlay = false
            releasedForCarPlay = false
            main.removeCallbacks(reconnect)
            prefs.edit().putBoolean(KEY_RECONNECT_PENDING, false).apply()
            AppLog.i("TopwayCallBridge: phone back on Bluetooth after CarPlay, asking com.tw.bt to take Bluetooth music back")
            sendPlain(TopwayBluetooth.ACTION_ACQUIRE_BT_MUSIC)
            unregisterIfUnused()
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
