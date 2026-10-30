package com.andrerinas.openheadunit.main.bento

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.ImageViewCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayMusicBridge
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayRadio
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.Date

/**
 * Everything on the tvOS home except the Android Auto card, which HomeFragment drives itself.
 *
 *  - Top bar: the menu (Android Settings, Head Unit Settings, TopWay's Bluetooth app), time, date,
 *    and whether a phone is on the car's Bluetooth and the unit on Wi-Fi.
 *  - Now Playing: Android Auto's song while a phone projects; else the car's radio while it plays;
 *    else the phone's Bluetooth audio; else a hint to connect a phone.
 *  - Tiles: App Library (every app) and Radio.
 *  - A call on the car's Bluetooth while nothing projects: a call card over the home.
 *    [TopwayForegroundKeeper] brings the home back over the unit's own phone screen.
 */
class BentoHomeController(
    private val root: View,
    private val openAppDrawer: () -> Unit,
    private val openProjection: () -> Unit,
) {
    private companion object {
        const val CLOCK_TICK_MS = 10_000L
        const val PROGRESS_TICK_MS = 1_000L
        const val MENU_ANIM_MS = 160L
        const val DIM_ALPHA = 0.35f
    }

    private val context: Context = root.context
    private val component = App.provide(context)
    private val commManager = component.commManager
    private val callBridge = component.topwayCallBridge
    private val music = TopwayMusicBridge(context)
    private val radio = TopwayRadio.get(context)
    private val main = Handler(Looper.getMainLooper())

    private val clock: TextView = root.findViewById(R.id.bento_clock)
    private val date: TextView = root.findViewById(R.id.bento_date)
    private val statusBt: ImageView = root.findViewById(R.id.bento_status_bt)
    private val statusWifi: ImageView = root.findViewById(R.id.bento_status_wifi)
    private val menuButton: View = root.findViewById(R.id.bento_menu_button)
    private val menuScrim: View = root.findViewById(R.id.bento_menu_scrim)
    private val menu: View = root.findViewById(R.id.bento_menu)

    private val musicCard: View = root.findViewById(R.id.bento_music_card)
    private val musicChip: View = root.findViewById(R.id.bento_music_chip)
    private val musicSourceIcon: ImageView = root.findViewById(R.id.bento_music_source_icon)
    private val musicSource: TextView = root.findViewById(R.id.bento_music_source)
    private val musicTitle: TextView = root.findViewById(R.id.bento_music_title)
    private val musicArtist: TextView = root.findViewById(R.id.bento_music_artist)
    private val musicArt: ImageView = root.findViewById(R.id.bento_music_art)
    private val musicArtIcon: ImageView = root.findViewById(R.id.bento_music_art_icon)
    private val progressRow: View = root.findViewById(R.id.bento_music_progress_row)
    private val progressBar: ProgressBar = root.findViewById(R.id.bento_music_progress)
    private val elapsed: TextView = root.findViewById(R.id.bento_music_elapsed)
    private val remaining: TextView = root.findViewById(R.id.bento_music_remaining)
    private val musicControls: View = root.findViewById(R.id.bento_music_controls)
    private val prevButton: View = root.findViewById(R.id.bento_music_prev)
    private val playButton: View = root.findViewById(R.id.bento_music_play)
    private val nextButton: View = root.findViewById(R.id.bento_music_next)
    private val playIcon: ImageView = root.findViewById(R.id.bento_music_play_icon)

    /**
     * The radio was turned off from the Now Playing card. The card keeps showing it, switched off,
     * so the same button turns it back on, until the home is left or something else plays.
     */
    private var radioOffFromCard = false

    private val callOverlay: View = root.findViewById(R.id.bento_call_overlay)
    private val callAvatar: TextView = root.findViewById(R.id.bento_call_avatar)
    private val callState: TextView = root.findViewById(R.id.bento_call_state)
    private val callName: TextView = root.findViewById(R.id.bento_call_name)
    private val callNumber: TextView = root.findViewById(R.id.bento_call_number)
    private val callAnswer: View = root.findViewById(R.id.bento_call_answer)
    private val callHangupLabel: TextView = root.findViewById(R.id.bento_call_hangup_label)

    private var lastCallState = TopwayBluetooth.CallState.IDLE
    private val homeListener: () -> Unit = {
        val state = callBridge.callState
        if (state != lastCallState) {
            lastCallState = state
            TopwayForegroundKeeper.onCallStateChanged()
        }
        renderAll()
    }
    private val nowPlayingListener: () -> Unit = { renderMusic() }
    private val radioListener: () -> Unit = { renderMusic() }

    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            renderStatus()
            main.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    /** Moves Android Auto's progress bar along between the phone's reports. */
    private val progressTick = object : Runnable {
        override fun run() {
            if (renderProgress()) main.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    fun onViewCreated() {
        menuButton.setOnClickListener { if (menuScrim.visibility == View.VISIBLE) closeMenu() else openMenu() }
        menuScrim.setOnClickListener { closeMenu() }
        root.findViewById<View>(R.id.bento_menu_android_settings).setOnClickListener {
            closeMenu()
            launch(Intent(android.provider.Settings.ACTION_SETTINGS), context.getString(R.string.tv_menu_android_settings))
        }
        root.findViewById<View>(R.id.bento_menu_headunit_settings).setOnClickListener {
            closeMenu()
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        root.findViewById<View>(R.id.bento_menu_bluetooth).setOnClickListener {
            closeMenu()
            openTopwayBluetooth(musicPage = false)
        }
        root.findViewById<View>(R.id.bento_tile_radio).setOnClickListener { BentoRadioActivity.open(context) }
        root.findViewById<View>(R.id.bento_tile_more).setOnClickListener { openAppDrawer() }
        root.findViewById<View>(R.id.bento_music_card).setOnClickListener { onMusicCardClicked() }
        root.findViewById<View>(R.id.bento_music_prev).setOnClickListener { mediaAction(MediaAction.PREVIOUS) }
        root.findViewById<View>(R.id.bento_music_play).setOnClickListener { mediaAction(MediaAction.PLAY_PAUSE) }
        root.findViewById<View>(R.id.bento_music_next).setOnClickListener { mediaAction(MediaAction.NEXT) }
        callAnswer.setOnClickListener { callBridge.answer() }
        root.findViewById<View>(R.id.bento_call_hangup).setOnClickListener { callBridge.endCall() }

        listOf(
            R.id.bento_music_card, R.id.bento_tile_radio, R.id.bento_tile_more,
            R.id.bento_music_prev, R.id.bento_music_play, R.id.bento_music_next, R.id.bento_menu_button,
        ).forEach { id -> root.findViewById<View>(id)?.let { BentoUi.addPressFeedback(it, focusScale = 1.02f) } }

        TopwayForegroundKeeper.start(context)
        callBridge.attachHome()
        callBridge.onHomeStateChanged = homeListener
        lastCallState = callBridge.callState
        if (callBridge.isAvailable) {
            music.onChanged = { renderMusic() }
            music.attach()
        }
        radio.attach(this, radioListener)
        AaNowPlaying.onChanged = nowPlayingListener
        main.post(clockTick)
        renderAll()
    }

    fun onResume() {
        TopwayForegroundKeeper.onResumed(TopwayForegroundKeeper.Screen.HOME)
        radio.refresh()
        renderAll()
    }

    fun onPause() {
        TopwayForegroundKeeper.onPaused(TopwayForegroundKeeper.Screen.HOME)
        radioOffFromCard = false
        closeMenu(animate = false)
        main.removeCallbacks(progressTick)
    }

    fun onDestroyView() {
        main.removeCallbacksAndMessages(null)
        if (callBridge.onHomeStateChanged === homeListener) callBridge.onHomeStateChanged = null
        callBridge.detachHome()
        music.detach()
        radio.detach(this, radioListener)
        if (AaNowPlaying.onChanged === nowPlayingListener) AaNowPlaying.onChanged = null
    }

    /** Called by HomeFragment whenever the connection state it watches changes. */
    fun onConnectionChanged() = renderAll()

    private fun renderAll() {
        renderClock()
        renderStatus()
        renderMusic()
        renderCall()
    }

    private fun renderClock() {
        val now = Date()
        clock.text = DateFormat.getTimeFormat(context).format(now)
        date.text = DateFormat.format("EEE, d MMM", now)
    }

    // --- Top bar ------------------------------------------------------------------------------

    private fun renderStatus() {
        if (callBridge.isAvailable) {
            val on = callBridge.moduleConnected
            statusBt.visibility = View.VISIBLE
            statusBt.alpha = if (on) 1f else DIM_ALPHA
            statusBt.contentDescription = context.getString(if (on) R.string.tv_bt_on else R.string.tv_bt_off)
        } else {
            statusBt.visibility = View.GONE
        }
        val wifi = wifiConnected()
        statusWifi.setImageResource(if (wifi) R.drawable.tv_ic_wifi else R.drawable.tv_ic_wifi_off)
        statusWifi.alpha = if (wifi) 1f else DIM_ALPHA
        statusWifi.contentDescription = context.getString(if (wifi) R.string.tv_wifi_on else R.string.tv_wifi_off)
    }

    @Suppress("DEPRECATION") // getNetworkInfo(TYPE_WIFI) is the one call that works from API 16 up.
    private fun wifiConnected(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
    } catch (e: Exception) {
        false
    }

    private fun openMenu() {
        menuScrim.visibility = View.VISIBLE
        menuButton.isSelected = true
        menu.alpha = 0f
        menu.translationY = -12f * context.resources.displayMetrics.density
        menu.animate().alpha(1f).translationY(0f).setDuration(MENU_ANIM_MS).start()
    }

    private fun closeMenu(animate: Boolean = true) {
        if (menuScrim.visibility != View.VISIBLE) return
        menuButton.isSelected = false
        if (!animate) {
            menuScrim.visibility = View.GONE
            return
        }
        menu.animate().alpha(0f).translationY(-12f * context.resources.displayMetrics.density)
            .setDuration(MENU_ANIM_MS).withEndAction { menuScrim.visibility = View.GONE }.start()
    }

    // --- Now Playing --------------------------------------------------------------------------

    private enum class Source { ANDROID_AUTO, RADIO, BLUETOOTH, NONE }

    private fun source(): Source = when {
        projecting() -> Source.ANDROID_AUTO
        radio.isPlaying || radioOffFromCard -> Source.RADIO
        callBridge.isAvailable && callBridge.moduleConnected -> Source.BLUETOOTH
        else -> Source.NONE
    }

    private fun renderMusic() {
        if (projecting()) radioOffFromCard = false
        musicArtIcon.setImageResource(R.drawable.tv_ic_music)
        musicChip.visibility = View.VISIBLE
        musicControls.alpha = 1f
        val source = source()
        // The card itself opens only screens of this app: Android Auto or the radio. On Bluetooth
        // audio (or with nothing playing) it is not pressable at all; its buttons still work.
        val cardOpens = source == Source.ANDROID_AUTO || source == Source.RADIO
        musicCard.isClickable = cardOpens
        musicCard.isFocusable = cardOpens
        // The radio gets one button, its power; the others get previous, play and next.
        val radioCard = source == Source.RADIO
        prevButton.visibility = if (radioCard) View.GONE else View.VISIBLE
        nextButton.visibility = if (radioCard) View.GONE else View.VISIBLE
        val lit = !radioCard || radio.isPlaying
        playButton.setBackgroundResource(if (lit) R.drawable.tv_white_circle_bg else R.drawable.tv_disc_glass)
        ImageViewCompat.setImageTintList(playIcon, ColorStateList.valueOf(if (lit) Color.BLACK else Color.WHITE))
        playButton.contentDescription = context.getString(if (radioCard) R.string.tv_radio_power else R.string.tv_play_pause)
        when (source) {
            Source.ANDROID_AUTO -> {
                setChip(R.drawable.tv_ic_phone, context.getString(R.string.tv_source_aa))
                musicTitle.text = AaNowPlaying.title.ifBlank { context.getString(R.string.tv_nothing_playing) }
                musicArtist.text = AaNowPlaying.artist
                showArt(AaNowPlaying.albumArt)
                playIcon.setImageResource(if (AaNowPlaying.isPlaying) R.drawable.tv_ic_pause else R.drawable.tv_ic_play)
            }
            Source.RADIO -> {
                setChip(R.drawable.tv_ic_radio_outline, TopwayRadio.bandName(radio.band))
                musicTitle.text = context.getString(R.string.bento_radio_frequency,
                    TopwayRadio.format(radio.band, radio.frequency), TopwayRadio.unitOf(radio.band))
                musicArtist.text = if (radio.isPlaying) radio.stationName.ifBlank { radio.radioText }
                    else context.getString(R.string.tv_radio_off)
                showArt(null)
                musicArtIcon.setImageResource(R.drawable.tv_ic_radio_outline)
                // The tuner has no pause, so its one button turns it on and off.
                playIcon.setImageResource(R.drawable.tv_ic_power)
            }
            Source.BLUETOOTH -> {
                setChip(R.drawable.tv_ic_bluetooth, context.getString(R.string.tv_source_bt))
                musicTitle.text = songText(music.title).ifBlank { context.getString(R.string.tv_source_bt) }
                musicArtist.text = songText(music.artist)
                showArt(null)
                // The unit reports no play state for Bluetooth audio, so one button does both.
                playIcon.setImageResource(R.drawable.tv_ic_play_pause)
            }
            Source.NONE -> {
                musicChip.visibility = View.INVISIBLE
                musicTitle.setText(R.string.tv_nothing_playing)
                musicArtist.setText(R.string.tv_connect_to_play)
                showArt(null)
                playIcon.setImageResource(R.drawable.tv_ic_play)
                musicControls.alpha = DIM_ALPHA
            }
        }
        main.removeCallbacks(progressTick)
        if (renderProgress()) main.postDelayed(progressTick, PROGRESS_TICK_MS)
    }

    /**
     * A phone with no song details sends placeholders over Bluetooth ("Not Provided", "Unknown").
     * Those read as a song name, so they count as nothing.
     */
    private fun songText(value: String): String {
        val text = value.trim()
        return if (text.equals("Not Provided", ignoreCase = true) || text.equals("Unknown", ignoreCase = true) ||
            text.equals("<unknown>", ignoreCase = true)) "" else text
    }

    private fun setChip(icon: Int, text: String) {
        musicSourceIcon.setImageResource(icon)
        musicSource.text = text
    }

    /** Android Auto's elapsed and remaining time. True while it should keep ticking. */
    private fun renderProgress(): Boolean {
        val duration = AaNowPlaying.durationMs
        if (source() != Source.ANDROID_AUTO || duration <= 0L) {
            progressRow.visibility = View.GONE
            return false
        }
        val position = AaNowPlaying.currentPositionMs(SystemClock.elapsedRealtime())
        progressRow.visibility = View.VISIBLE
        progressBar.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
        elapsed.text = formatTime(position)
        remaining.text = "-" + formatTime((duration - position).coerceAtLeast(0L))
        return AaNowPlaying.isPlaying
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000L
        return "%d:%02d".format(total / 60L, total % 60L)
    }

    private fun showArt(bitmap: android.graphics.Bitmap?) {
        if (bitmap != null) {
            musicArt.setImageBitmap(bitmap)
            musicArtIcon.visibility = View.GONE
        } else {
            musicArt.setImageDrawable(null)
            musicArtIcon.visibility = View.VISIBLE
        }
    }

    // --- Call card ----------------------------------------------------------------------------

    private fun renderCall() {
        val state = callBridge.callState
        val show = state.inCall && !projecting()
        callOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val incoming = state == TopwayBluetooth.CallState.INCOMING
        val stateText = context.getString(when (state) {
            TopwayBluetooth.CallState.INCOMING -> R.string.bento_call_incoming
            TopwayBluetooth.CallState.OUTGOING -> R.string.bento_call_outgoing
            else -> R.string.bento_call_active
        })
        callState.text = context.getString(R.string.tv_call_kicker, stateText)
        // The contact name when the phonebook knows the number; the number otherwise.
        val name = callBridge.callerName
        val number = callBridge.callerNumber
        val shown = name.ifBlank { number.ifBlank { context.getString(R.string.bento_call_unknown_caller) } }
        callName.text = shown
        callNumber.text = if (name.isNotBlank()) number else ""
        callNumber.visibility = if (callNumber.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val initial = name.trim().firstOrNull()
        callAvatar.text = if (initial != null && initial.isLetter()) initial.uppercaseChar().toString() else "#"
        callAnswer.visibility = if (incoming) View.VISIBLE else View.GONE
        callHangupLabel.setText(if (incoming) R.string.tv_decline else R.string.tv_end_call)
    }

    // --- Actions ------------------------------------------------------------------------------

    private enum class MediaAction { PREVIOUS, PLAY_PAUSE, NEXT }

    private fun mediaAction(action: MediaAction) {
        when (source()) {
            Source.ANDROID_AUTO -> {
                val key = when (action) {
                    MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                }
                commManager.sendKey(key, true, null, "home")
                commManager.sendKey(key, false, null, "home")
            }
            Source.RADIO -> when (action) {
                // Stored stations first, as the stock radio's wheel buttons do; a seek with none.
                MediaAction.PREVIOUS -> radio.nextPreset(forward = false)?.let { radio.selectPreset(it.slot) } ?: radio.seekDown()
                MediaAction.NEXT -> radio.nextPreset(forward = true)?.let { radio.selectPreset(it.slot) } ?: radio.seekUp()
                MediaAction.PLAY_PAUSE -> if (radio.isPlaying) {
                    AppLog.i("BentoHome: radio turned off from Now Playing")
                    radio.stop() // The tuner's answer redraws the card.
                    // With a phone on Bluetooth its music takes over and the card shows it; with
                    // none, the card stays on the radio, switched off, so this button turns it on.
                    radioOffFromCard = !callBridge.returnToBluetoothMusic()
                } else {
                    AppLog.i("BentoHome: radio turned on from Now Playing")
                    radioOffFromCard = false
                    radio.playInBackground()
                }
            }
            Source.BLUETOOTH -> when (action) {
                MediaAction.PREVIOUS -> music.previous()
                MediaAction.PLAY_PAUSE -> music.playPause()
                MediaAction.NEXT -> music.next()
            }
            Source.NONE -> Unit
        }
    }

    private fun onMusicCardClicked() {
        when (source()) {
            Source.ANDROID_AUTO -> openProjection()
            Source.RADIO -> BentoRadioActivity.open(context)
            // The card's own buttons drive Bluetooth audio; TopWay's app stays closed.
            Source.BLUETOOTH, Source.NONE -> Unit
        }
    }

    /**
     * TopWay's own Bluetooth app (`com.tw.bt`): pairing, phonebook and the car's name and PIN live
     * there. [musicPage] opens it on its Bluetooth music page (its `showui=a2dp` extra).
     */
    private fun openTopwayBluetooth(musicPage: Boolean) {
        val label = context.getString(R.string.tv_menu_bluetooth)
        val direct = Intent().apply {
            component = ComponentName(TopwayBluetooth.PACKAGE, "${TopwayBluetooth.PACKAGE}.ATBluetoothActivity")
            if (musicPage) putExtra("showui", "a2dp")
        }
        if (launch(direct, label, quiet = true)) return
        val fallback = context.packageManager.getLaunchIntentForPackage(TopwayBluetooth.PACKAGE)
        if (fallback == null || !launch(fallback, label, quiet = true)) {
            ToastUtils.showToast(context, context.getString(R.string.bento_app_missing, label), Toast.LENGTH_SHORT, force = true)
        }
    }

    private fun projecting(): Boolean =
        commManager.connectionState.value is CommManager.ConnectionState.TransportStarted

    private fun launch(intent: Intent, label: String, quiet: Boolean = false): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        AppLog.w("BentoHome: could not open $label: ${e.message}")
        if (!quiet) {
            ToastUtils.showToast(context, context.getString(R.string.bento_app_missing, label), Toast.LENGTH_SHORT, force = true)
        }
        false
    }
}
