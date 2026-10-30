package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Bluetooth music from the phone, played by a TopWay unit's own Bluetooth module (A2DP), driven
 * from this app's home screen.
 *
 * Uses the music half of the broadcast interface `com.tw.bt` offers in-car assistant apps (the
 * same one [TopwayBluetooth] documents for calls):
 * ```
 * com.txznet.launcher.btmusic.action.send   extra "action": 5 play/pause, 6 previous, 7 next,
 *                                             8 ask for the current song
 * com.txznet.launcher.btmusic.action.recv   extras "title", "artist", "picUrl"
 * net.easyconn.a2dp.acquire                 put the car's speakers on Bluetooth music
 * ```
 * The unit reports no play/pause state on this interface, so none is claimed here.
 */
class TopwayMusicBridge(context: Context) {

    companion object {
        const val ACTION_COMMAND = "com.txznet.launcher.btmusic.action.send"
        const val ACTION_INFO = "com.txznet.launcher.btmusic.action.recv"
        const val ACTION_ACQUIRE = "net.easyconn.a2dp.acquire"

        const val PLAY_PAUSE = 5
        const val PREVIOUS = 6
        const val NEXT = 7
        const val QUERY_SONG = 8

        /** How often the song is asked for while the home screen shows it. */
        const val POLL_MS = 3_000L
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var attached = false

    @Volatile var title: String = ""
        private set
    @Volatile var artist: String = ""
        private set

    /** Told on the main thread when the song changes. */
    @Volatile var onChanged: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_INFO) return
            val newTitle = intent.getStringExtra("title").orEmpty()
            val newArtist = intent.getStringExtra("artist").orEmpty()
            if (newTitle == title && newArtist == artist) return
            title = newTitle
            artist = newArtist
            onChanged?.invoke()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!attached) return
            send(QUERY_SONG)
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Main thread. */
    fun attach() {
        if (attached) return
        attached = true
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_INFO), ContextCompat.RECEIVER_EXPORTED)
        main.post(poll)
    }

    /** Main thread. */
    fun detach() {
        if (!attached) return
        attached = false
        onChanged = null
        main.removeCallbacks(poll)
        try {
            app.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    fun playPause() {
        // The speakers stay on whatever source last had them; claim them for Bluetooth music first.
        app.sendBroadcast(Intent(ACTION_ACQUIRE).setPackage(TopwayBluetooth.PACKAGE))
        send(PLAY_PAUSE)
    }

    fun next() = send(NEXT)

    fun previous() = send(PREVIOUS)

    private fun send(command: Int) {
        try {
            app.sendBroadcast(Intent(ACTION_COMMAND).setPackage(TopwayBluetooth.PACKAGE).putExtra("action", command))
        } catch (e: Exception) {
            AppLog.w("TopwayMusicBridge: could not reach com.tw.bt: ${e.message}")
        }
    }
}
