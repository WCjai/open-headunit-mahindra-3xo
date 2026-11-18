package com.andrerinas.openheadunit.carplay

import android.content.Context
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.bento.TopwayForegroundKeeper
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.shilapi.xcertplay.OpenHeadunitBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hooks Open Headunit into the CarPlay stack once, at app start, and says whether a CarPlay session
 * runs (for the home's CarPlay card and the wheel keys). Updated on the main thread.
 *
 * With the hooks set, the CarPlay stack never shows a screen of its own: Back, the car icon in
 * CarPlay and the round Home button go to this app's home, its settings requests to Head Unit
 * Settings, and its connecting screen wears the home's look.
 */
object CarPlaySession {

    private const val PREFS = "carplay_launcher"
    private const val KEY_DROP_BLUETOOTH = "drop_bluetooth_during_carplay"

    /**
     * Whether the car's Bluetooth module lets go of the iPhone when CarPlay asks (disableBluetooth),
     * so calls can only go over CarPlay. On by default: kept on the car's Bluetooth, the iPhone also
     * rings the module, and TopWay's service then mutes Android's music path, which carries the
     * CarPlay call, so the caller was not heard. The module reconnects the phone when CarPlay ends
     * (TopwayCallBridge).
     */
    fun dropBluetoothDuringCarPlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DROP_BLUETOOTH, true)

    fun setDropBluetoothDuringCarPlay(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DROP_BLUETOOTH, value).apply()
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun install(context: Context) {
        val app = context.applicationContext
        CarPlayTopwayKeys.install(app)
        CarPlayUnplugWatcher.install(app)
        OpenHeadunitBridge.cancelDiPlayUpdateChecks(app)
        OpenHeadunitBridge.carName = app.getString(R.string.carplay_car_name)
        OpenHeadunitBridge.quickMenuEnabled = false
        OpenHeadunitBridge.hostScreenIntent = { ctx, page ->
            when (page) {
                "settings" -> CarPlayLauncher.settingsIntent(ctx)
                "setup-error" -> {
                    AppLog.w("CarPlay: the CarPlay screen could not start (no accessory identity in this build?)")
                    ToastUtils.showToast(ctx, ctx.getString(R.string.carplay_unavailable), Toast.LENGTH_LONG, force = true)
                    TopwayForegroundKeeper.homeIntent(ctx)
                }
                else -> TopwayForegroundKeeper.homeIntent(ctx)
            }
        }
        OpenHeadunitBridge.decorateScreen = { activity, root -> CarPlayScreenDecor.addHomeButton(activity, root) }
        OpenHeadunitBridge.paintWaitingScreen = { panel, icon, title, lines, buttons ->
            CarPlayScreenDecor.paintWaitingScreen(panel, icon, title, lines, buttons)
        }
        // The car's own Bluetooth module: its music released while CarPlay plays and given back after,
        // and its link with the iPhone dropped when the iPhone asks, so calls come through CarPlay.
        val bluetooth = com.andrerinas.openheadunit.App.provide(app).topwayCallBridge
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        com.shilapi.xcertplay.OpenHeadunitFlags.onBluetoothHandoff = { deviceId ->
            main.post {
                if (dropBluetoothDuringCarPlay(app)) {
                    bluetooth.dropLinkForCarPlay(deviceId)
                } else {
                    AppLog.i("CarPlay: the iPhone asked the car to drop Bluetooth (device=$deviceId); " +
                        "kept, as set in Settings")
                }
            }
        }
        OpenHeadunitBridge.onSessionChanged = { running ->
            if (_active.value != running) AppLog.i("CarPlay: session ${if (running) "started" else "ended"}")
            _active.value = running
            CarPlayTopwayKeys.onSessionChanged(running)
            bluetooth.onCarPlayChanged(running)
        }
    }
}
