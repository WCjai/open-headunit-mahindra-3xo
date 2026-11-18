package com.andrerinas.openheadunit.carplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog
import com.shilapi.xcertplay.OpenHeadunitBridge

/**
 * Ends CarPlay when the iPhone is unplugged. The CarPlay stack only sees a lost link and keeps
 * retrying for minutes, so the home went on saying "Connected" with no iPhone there.
 *
 * The iPhone drops off the bus for a moment by itself when it switches into CarPlay mode, so an
 * unplug counts only once no iPhone has come back for [GRACE_MS].
 */
object CarPlayUnplugWatcher {

    private const val GRACE_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var installed = false

    private val check = Runnable {
        val context = appContext ?: return@Runnable
        if (CarPlayLauncher.attachedIphone(context) != null) {
            AppLog.i("CarPlay: the iPhone came back on USB; CarPlay carries on")
            return@Runnable
        }
        if (!OpenHeadunitBridge.screenOpen() && !OpenHeadunitBridge.sessionActive() && !CarPlaySession.active.value) return@Runnable
        AppLog.i("CarPlay: iPhone unplugged, ending CarPlay")
        OpenHeadunitBridge.endSession("iPhone unplugged")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (!CarPlayLauncher.isIphone(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    AppLog.i("CarPlay: iPhone ${device?.deviceName} left USB; checking again in ${GRACE_MS}ms")
                    device?.let { CarPlayLauncher.forget(it) }
                    main.removeCallbacks(check)
                    main.postDelayed(check, GRACE_MS)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> main.removeCallbacks(check)
            }
        }
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        // System broadcasts: the not-exported flag still receives them.
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
