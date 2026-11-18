package com.andrerinas.openheadunit.main.bento

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth
import com.andrerinas.openheadunit.main.TopwayWheelActivity
import com.andrerinas.openheadunit.main.synthwave.Synthwave
import com.andrerinas.openheadunit.main.synthwave.SynthwaveBackdropView
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils

/**
 * More Apps (ui/Head Unit UI Mock.html): the TopWay unit's own apps as eight tiles. Bluetooth, Music,
 * Video, EQ and TV are TopWay's apps (`com.tw.*`); Steering is this app's wheel-key setup, Settings
 * is Android's, and Radio is this app's radio screen.
 */
class MoreAppsActivity : BaseActivity() {

    companion object {
        private const val CLOCK_TICK_MS = 10_000L

        fun open(context: Context) {
            context.startActivity(Intent(context, MoreAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var topBar: Synthwave.TopBar
    private val main = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            topBar.render()
            main.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more_apps)
        findViewById<TextView>(R.id.sw_header_title).setText(R.string.sw_more_apps)
        topBar = Synthwave.TopBar(
            clock = findViewById(R.id.sw_header_clock),
            date = null,
            bluetooth = findViewById(R.id.sw_header_bt),
            wifi = findViewById(R.id.sw_header_wifi),
        )
        findViewById<View>(R.id.bento_back).setOnClickListener { finish() }

        val tiles = listOf(
            R.id.sw_app_bluetooth to { openBluetooth() },
            R.id.sw_app_music to { openPackage("com.tw.music", R.string.sw_app_music) },
            R.id.sw_app_video to { openPackage("com.tw.video", R.string.sw_app_video) },
            // The car's sound in this app (presets, bands, balance, subwoofer); hold for TopWay's own EQ.
            R.id.sw_app_eq to { SoundActivity.open(this) },
            R.id.sw_app_tv to { openPackage("com.tw.tv", R.string.sw_app_tv) },
            R.id.sw_app_steering to { launch(Intent(this, TopwayWheelActivity::class.java), R.string.sw_app_steering) },
            R.id.sw_app_settings to { launch(Intent(android.provider.Settings.ACTION_SETTINGS), R.string.sw_app_settings) },
            R.id.sw_app_radio to { BentoRadioActivity.open(this) },
        )
        val backdrop = findViewById<SynthwaveBackdropView>(R.id.sw_backdrop)
        tiles.forEach { (id, action) ->
            val tile = findViewById<View>(id)
            tile.setOnClickListener { action() }
            BentoUi.addPressFeedback(tile, focusScale = 1.02f)
            backdrop.frost(tile)
        }
        BentoUi.addPressFeedback(findViewById(R.id.bento_back))
        findViewById<View>(R.id.sw_app_eq).setOnLongClickListener {
            openPackage("com.tw.eq", R.string.sw_app_eq)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(clockTick)
    }

    /** TopWay's Bluetooth app on its main page; its launcher entry when that page is missing. */
    private fun openBluetooth() {
        val direct = Intent().setComponent(ComponentName(TopwayBluetooth.PACKAGE, "${TopwayBluetooth.PACKAGE}.ATBluetoothActivity"))
        if (launch(direct, R.string.sw_app_bluetooth, quiet = true)) return
        openPackage(TopwayBluetooth.PACKAGE, R.string.sw_app_bluetooth)
    }

    private fun openPackage(pkg: String, label: Int) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            AppLog.w("MoreApps: $pkg has no launcher entry")
            ToastUtils.showToast(this, getString(R.string.bento_app_missing, getString(label)), Toast.LENGTH_SHORT, force = true)
            return
        }
        launch(intent, label)
    }

    private fun launch(intent: Intent, label: Int, quiet: Boolean = false): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        AppLog.w("MoreApps: could not open ${getString(label)}: ${e.message}")
        if (!quiet) {
            ToastUtils.showToast(this, getString(R.string.bento_app_missing, getString(label)), Toast.LENGTH_SHORT, force = true)
        }
        false
    }
}
