package com.andrerinas.openheadunit.main.bento

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayCallBridge
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayContacts
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayMusicBridge
import com.andrerinas.openheadunit.utils.ToastUtils

/**
 * The car's Bluetooth inside this app: whether a phone is connected, its music, and the way to
 * pair, forget or rename.
 *
 * Everything goes through `com.tw.bt`, the unit's Bluetooth app, which stays the module's only
 * reader. The module talks over one serial line (`/dev/ttyS3`, a pseudo-terminal of the `gocsdk`
 * daemon); a second reader would take part of every reply, `com.tw.bt` included, and could make it
 * miss a ringing call. So this screen shows the link and the music, disconnects, and opens
 * `com.tw.bt`'s own pages for searching, pairing and forgetting phones, which need the module's
 * replies. See [TopwayBluetooth].
 */
class BentoBluetoothActivity : BaseActivity() {

    companion object {
        private const val PREFS = "bento_bluetooth"
        private const val KEY_PIN = "pin"
        private const val KEY_NAME = "name"

        fun open(context: Context) {
            context.startActivity(Intent(context, BentoBluetoothActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private lateinit var bridge: TopwayCallBridge
    private lateinit var music: TopwayMusicBridge
    private lateinit var carName: TextView
    private lateinit var phoneState: TextView
    private lateinit var phoneDetail: TextView
    private lateinit var disconnect: View
    private lateinit var badge: View
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicControls: View
    private lateinit var phonebookDetail: TextView

    private val stateListener: () -> Unit = { render() }

    /** `com.tw.bt`'s answer to a status check: the car's name and PIN while no phone is connected. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != TopwayBluetooth.ACTION_STATUS_OPEN) return
            val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            intent.getStringExtra(TopwayBluetooth.EXTRA_PIN)?.takeIf { it.isNotBlank() }?.let { edit.putString(KEY_PIN, it) }
            intent.getStringExtra(TopwayBluetooth.EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { edit.putString(KEY_NAME, it) }
            edit.apply()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bento_bluetooth)
        bridge = App.provide(this).topwayCallBridge
        music = TopwayMusicBridge(this)

        carName = findViewById(R.id.bt_car_name)
        phoneState = findViewById(R.id.bt_phone_state)
        phoneDetail = findViewById(R.id.bt_phone_detail)
        disconnect = findViewById(R.id.bt_disconnect)
        badge = findViewById(R.id.bt_phone_badge)
        musicTitle = findViewById(R.id.bt_music_title)
        musicArtist = findViewById(R.id.bt_music_artist)
        musicControls = findViewById(R.id.bt_music_controls)
        phonebookDetail = findViewById(R.id.bt_phonebook_detail)

        findViewById<View>(R.id.bento_back).setOnClickListener { finish() }
        disconnect.setOnClickListener {
            bridge.linkCommand(TopwayBluetooth.LINK_DISCONNECT)
            disconnect.postDelayed({ bridge.queryLink() }, 1_500)
        }
        findViewById<View>(R.id.bt_tile_pair).setOnClickListener { bridge.linkCommand(TopwayBluetooth.LINK_OPEN_PAIRING) }
        findViewById<View>(R.id.bt_tile_settings).setOnClickListener { bridge.linkCommand(TopwayBluetooth.LINK_OPEN_SETTINGS) }
        findViewById<View>(R.id.bt_tile_phonebook).setOnClickListener {
            // com.tw.bt opens its phonebook only with a phone connected.
            if (bridge.moduleConnected) {
                bridge.linkCommand(TopwayBluetooth.LINK_OPEN_PHONEBOOK)
            } else {
                ToastUtils.showToast(this, getString(R.string.bento_bt_not_connected_detail), Toast.LENGTH_SHORT, force = true)
            }
        }
        findViewById<View>(R.id.bt_music_prev).setOnClickListener { music.previous() }
        findViewById<View>(R.id.bt_music_play).setOnClickListener { music.playPause() }
        findViewById<View>(R.id.bt_music_next).setOnClickListener { music.next() }

        listOf(R.id.bento_back, R.id.bt_disconnect, R.id.bt_tile_pair, R.id.bt_tile_phonebook,
            R.id.bt_tile_settings, R.id.bt_music_prev, R.id.bt_music_play, R.id.bt_music_next,
        ).forEach { BentoUi.addPressFeedback(findViewById(it)) }

        bridge.attachHome()
        bridge.addStateListener(stateListener)
        music.onChanged = { render() }
        music.attach()
        ContextCompat.registerReceiver(this, statusReceiver,
            IntentFilter(TopwayBluetooth.ACTION_STATUS_OPEN), ContextCompat.RECEIVER_EXPORTED)
        render()
    }

    override fun onResume() {
        super.onResume()
        TopwayForegroundKeeper.onResumed(TopwayForegroundKeeper.Screen.BLUETOOTH)
        bridge.queryLink()
        sendBroadcast(Intent(TopwayBluetooth.ACTION_CHECK_STATUS).setPackage(TopwayBluetooth.PACKAGE))
        render()
    }

    override fun onPause() {
        super.onPause()
        TopwayForegroundKeeper.onPaused(TopwayForegroundKeeper.Screen.BLUETOOTH)
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.removeStateListener(stateListener)
        bridge.detachHome()
        music.detach()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun render() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val name = bridge.moduleName.ifBlank { prefs.getString(KEY_NAME, "").orEmpty() }
        val pin = prefs.getString(KEY_PIN, "").orEmpty()
        carName.visibility = if (name.isBlank()) View.GONE else View.VISIBLE
        carName.text = if (pin.isBlank()) getString(R.string.bento_bt_car_name, name)
            else getString(R.string.bento_bt_car_name_pin, name, pin)

        val connected = bridge.moduleConnected
        when {
            !bridge.isAvailable -> {
                phoneState.setText(R.string.bento_bt_not_connected)
                phoneDetail.setText(R.string.bento_bt_unavailable)
            }
            connected -> {
                phoneState.setText(R.string.bento_bt_connected)
                phoneDetail.setText(R.string.bento_bt_connected_detail)
            }
            else -> {
                phoneState.setText(R.string.bento_bt_not_connected)
                phoneDetail.setText(R.string.bento_bt_not_connected_detail)
            }
        }
        badge.alpha = if (connected) 1f else 0.35f
        disconnect.visibility = if (connected) View.VISIBLE else View.GONE

        if (connected) {
            musicTitle.text = music.title.ifBlank { getString(R.string.bento_bt_play_in_car) }
            musicArtist.text = music.artist
            musicControls.alpha = 1f
        } else {
            musicTitle.setText(R.string.bento_nothing_playing)
            musicArtist.setText(R.string.bento_bt_music_off)
            musicControls.alpha = 0.35f
        }
        musicControls.isEnabled = connected

        val contacts = TopwayContacts.size
        phonebookDetail.text = if (contacts > 0) getString(R.string.bento_bt_phonebook_count, contacts)
            else getString(R.string.bento_bt_phonebook_empty)
    }
}
