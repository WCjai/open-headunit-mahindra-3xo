package com.andrerinas.openheadunit.carplay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.OpenHeadunitBridge

/**
 * Open Headunit's side of Apple CarPlay. The CarPlay stack itself is DiPlay (carplay/engine and
 * carplay/ui); this object decides when its screen opens and sets it up for the car.
 *
 * An Android phone goes to Android Auto as before. An iPhone (Apple's USB vendor id) opens the
 * CarPlay screen, wired only: the TopWay unit's Bluetooth belongs to its own module, so wireless
 * CarPlay cannot start there.
 */
object CarPlayLauncher {

    const val APPLE_VENDOR_ID = 0x05AC
    private const val IDENTITY_ASSET = "offline-mfi/identity.pk8"
    private const val PREFS = "carplay_launcher"
    private const val KEY_CAR_DEFAULTS = "car_defaults_version"
    /**
     * 2: USB default mode, setup guide seen. 3: the car's name instead of DiPlay's and BYD's.
     * 4: echo cancellation on CarPlay calls, which now play on the media speakers.
     */
    private const val CAR_DEFAULTS_VERSION = 5

    /** USB device ids already handed to CarPlay. A re-plug gets a new id and opens CarPlay again. */
    private val handledDeviceIds = HashSet<Int>()

    fun isIphone(device: UsbDevice?): Boolean = device?.vendorId == APPLE_VENDOR_ID

    /** False when the APK was built without the accessory identity (see carplay/README.md). */
    fun hasIdentity(context: Context): Boolean =
        runCatching { context.assets.open(IDENTITY_ASSET).use { true } }.getOrDefault(false)

    /** An iPhone on one of the unit's USB ports, if any. */
    fun attachedIphone(context: Context): UsbDevice? = runCatching {
        (context.getSystemService(Context.USB_SERVICE) as? UsbManager)?.deviceList?.values?.firstOrNull(::isIphone)
    }.getOrNull()

    /**
     * Writes the car's choices into CarPlay's settings once, before CarPlay first opens: USB instead
     * of wireless, the driving side from Open Headunit's settings, and the car's name where the
     * CarPlay stack would name itself. The driver can change the CarPlay ones in Settings later.
     */
    fun applyCarDefaultsOnce(context: Context, rightHandDrive: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_CAR_DEFAULTS, 0)
        if (applied >= CAR_DEFAULTS_VERSION) return
        if (applied < 3) OpenHeadunitBridge.applyCarDefaults(
            context, rightHandDrive,
            manufacturer = context.getString(R.string.carplay_car_make),
            model = context.getString(R.string.carplay_car_model),
            carIconLabel = context.getString(R.string.carplay_car_icon_label),
        )
        if (applied < 4) OpenHeadunitBridge.applyCallDefaults(context)
        // v5: the car's Bluetooth lets go of the iPhone during CarPlay, so CarPlay calls are heard.
        if (applied < 5) CarPlaySession.setDropBluetoothDuringCarPlay(context, true)
        prefs.edit().putInt(KEY_CAR_DEFAULTS, CAR_DEFAULTS_VERSION).apply()
        AppLog.i("CarPlay: car defaults applied (USB, rightHandDrive=$rightHandDrive)")
    }

    /** Opens the CarPlay screen for an iPhone that was just plugged in. */
    fun onIphoneAttached(context: Context, device: UsbDevice, source: String) {
        synchronized(handledDeviceIds) {
            if (!handledDeviceIds.add(device.deviceId)) {
                AppLog.i("CarPlay: ${device.deviceName} already handed over ($source)")
                return
            }
        }
        if (OpenHeadunitBridge.screenOpen()) {
            // The iPhone re-enumerates once while it switches to CarPlay mode: the screen already
            // connecting to it needs nothing, and opening it again would pull it over the home.
            AppLog.i("CarPlay: iPhone ${device.deviceName} back on USB; the CarPlay screen is already open ($source)")
            return
        }
        AppLog.i("CarPlay: iPhone ${device.deviceName} (pid 0x%04X) attached, opening CarPlay ($source)".format(device.productId))
        open(context, device)
    }

    /** An unplugged iPhone: the next time it is plugged in counts as new, even with the same USB id. */
    fun forget(device: UsbDevice) {
        synchronized(handledDeviceIds) { handledDeviceIds.remove(device.deviceId) }
    }

    /** The service's USB scan: opens CarPlay for an iPhone left plugged in across a boot or restart. */
    fun scanForIphone(context: Context, attached: Collection<UsbDevice>): Boolean {
        val iphone = attached.firstOrNull(::isIphone) ?: return false
        onIphoneAttached(context, iphone, "USB scan")
        return true
    }

    /**
     * The home's "Connect over USB" on the CarPlay card: back to CarPlay while it runs, CarPlay for a
     * plugged-in iPhone, and otherwise only a "No device found" note. False when nothing opened.
     */
    fun connectOverUsb(context: Context): Boolean {
        if (OpenHeadunitBridge.screenOpen() || OpenHeadunitBridge.sessionActive()) {
            OpenHeadunitBridge.showScreen(context)
            return true
        }
        val iphone = attachedIphone(context)
        if (iphone == null) {
            AppLog.i("CarPlay: Connect over USB with no iPhone attached")
            ToastUtils.showToast(context, context.getString(R.string.no_device_found), Toast.LENGTH_SHORT, force = true)
            return false
        }
        synchronized(handledDeviceIds) { handledDeviceIds.add(iphone.deviceId) }
        AppLog.i("CarPlay: Connect over USB, opening CarPlay for ${iphone.deviceName}")
        open(context, iphone)
        return true
    }

    /** Opens the CarPlay screen; with [device], it connects to that iPhone over USB at once. */
    fun open(context: Context, device: UsbDevice? = null) {
        if (!hasIdentity(context)) {
            AppLog.w("CarPlay: this build has no accessory identity; the CarPlay screen will report it")
        }
        val intent = Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (device != null) {
            // The same shape as the system's attach intent, which the CarPlay screen reads to choose USB.
            intent.action = UsbManager.ACTION_USB_DEVICE_ATTACHED
            intent.putExtra(UsbManager.EXTRA_DEVICE, device)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.e("CarPlay: could not open the CarPlay screen: ${e.message}")
        }
    }

    /** Head Unit Settings on its Apple CarPlay tab. */
    fun settingsIntent(context: Context): Intent =
        Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_CARPLAY)

    fun openSettings(context: Context) {
        try {
            context.startActivity(settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("CarPlay: could not open CarPlay settings: ${e.message}")
        }
    }
}
