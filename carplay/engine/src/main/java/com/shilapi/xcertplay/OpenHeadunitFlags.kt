package com.shilapi.xcertplay

/**
 * Open Headunit addition, not part of upstream DiPlay: switches for DiPlay's engine diagnostics that
 * cost the head unit time and CPU while CarPlay starts.
 */
object OpenHeadunitFlags {

    /**
     * DiPlay's "temporary lab capture": for two minutes after every connection it streams the iPhone's
     * whole system log over USB, filters it and logs the CarPlay lines. On this unit that is about 50
     * log lines a second on top of the video, and opening the log service delays the connection by
     * ~0.4 s. Off unless someone is debugging the iPhone's side of authentication.
     */
    @Volatile var phoneLogCapture: Boolean = false

    /**
     * Wired CarPlay: the iPhone sent disableBluetooth, asking the car to drop its own Bluetooth link
     * with it so calls and audio go over CarPlay. DiPlay only acts on it for BYD's Android Bluetooth;
     * the host drops the link on its own module. Called on an engine thread with the iPhone's
     * Bluetooth address as sent (may be null).
     */
    @Volatile var onBluetoothHandoff: ((deviceId: String?) -> Unit)? = null

    /**
     * Plays CarPlay call audio on the media path (the speakers CarPlay music uses) instead of
     * Android's in-call path, and records the call with the plain microphone. DiPlay does this only
     * once it has turned a BYD's Bluetooth off. TopWay units route Android's in-call audio to their
     * Bluetooth module's phone path, which is silent unless that module carries the call, so with
     * the iPhone's audio set to CarPlay the caller was not heard.
     */
    @Volatile var callsOnMediaPath: Boolean = true
}
