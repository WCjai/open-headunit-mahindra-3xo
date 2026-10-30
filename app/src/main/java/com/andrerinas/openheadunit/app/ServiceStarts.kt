package com.andrerinas.openheadunit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Starts one of this app's services with a plain start whenever Android allows it, and with a
 * foreground-service start only when it does not.
 *
 * A foreground-service start obliges the service to call startForeground within a deadline (10 s on
 * Android 9), counted from the call, not from when the service runs. On a slow head unit at boot,
 * Android queues background service starts and the main thread is busy for seconds, so the deadline
 * passed with the service never having had a chance, and Android killed the app ("did not then call
 * Service.startForeground()"). Commands that stop the service were worse: one could start it just to
 * stop it, which Android 8 and 9 also punish with that crash.
 *
 * A plain start has no deadline. It is allowed whenever the app is in the foreground (a screen
 * showing, or a foreground service running), which covers every start from a screen. From the
 * background it throws, and only then is the foreground start used. The services go foreground
 * themselves as soon as they are created either way.
 */
object ServiceStarts {

    fun start(context: Context, intent: Intent): ComponentName? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return context.startService(intent)
        return try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            // The app is in the background: only a foreground-service start is allowed.
            ContextCompat.startForegroundService(context, intent)
            null
        }
    }
}
