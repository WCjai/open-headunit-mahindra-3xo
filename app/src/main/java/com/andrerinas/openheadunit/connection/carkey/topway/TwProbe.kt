package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Debug builds only: lets a developer at `adb shell` open a TWUtil channel, send one command and
 * read every reply in the log, to confirm a TopWay protocol on the unit before code depends on it.
 *
 * Only a sender holding android.permission.DUMP gets through, which the adb shell has and no
 * installed app can. Release builds never register it.
 *
 *   am broadcast -a com.andrerinas.openheadunit.debug.TW_PROBE \
 *       --ei ctor 8 --es ids 513,769 --ei baud 115200 --ei what 3 --ei a 3 [--ei b 0] [--es s1 text]
 *   am broadcast -a com.andrerinas.openheadunit.debug.TW_PROBE --es close 1
 *
 * ctor -1 is TWUtil(). Without `what` it only opens and listens. A channel stays open for later
 * probes with the same ctor and ids until closed, or for ten minutes.
 */
object TwProbe {

    private const val ACTION = "com.andrerinas.openheadunit.debug.TW_PROBE"
    private const val IDLE_CLOSE_MS = 10 * 60_000L

    private val channels = HashMap<String, TwUtilChannel>()
    private val main = Handler(Looper.getMainLooper())
    private val closeAll = Runnable { closeChannels() }

    fun register(context: Context) {
        if (!BuildConfig.DEBUG || !TwUtilChannel.available) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handle(c, intent)
        }
        ContextCompat.registerReceiver(
            context.applicationContext, receiver, IntentFilter(ACTION),
            android.Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.hasExtra("close")) {
            closeChannels()
            return
        }
        val ctor = intent.getIntExtra("ctor", TwUtilChannel.NO_ARG)
        val ids = (intent.getStringExtra("ids") ?: "").split(',').mapNotNull { it.trim().toIntOrNull()?.toShort() }
        val baud = if (intent.hasExtra("baud")) intent.getIntExtra("baud", 0) else null
        val key = "$ctor/${ids.joinToString(",")}/$baud"
        val channel = channels[key] ?: TwUtilChannel("probe${channels.size}", ids.toShortArray(),
            TwUtilChannel.ctorArgOf(ctor), baud).also { ch ->
            val error = ch.open(context) { what, a1, a2, obj ->
                AppLog.i("TwProbe[$key]: msg what=$what (0x%X) arg1=$a1 (0x%X) arg2=$a2 obj=%s".format(what, a1, describe(obj)))
            }
            if (error != null) {
                AppLog.w("TwProbe[$key]: open failed: $error")
                return
            }
            channels[key] = ch
        }
        main.removeCallbacks(closeAll)
        main.postDelayed(closeAll, IDLE_CLOSE_MS)
        if (!intent.hasExtra("what")) return
        val what = intent.getIntExtra("what", 0)
        val a = intent.getIntExtra("a", 0)
        val b = intent.getIntExtra("b", 0)
        val s1 = intent.getStringExtra("s1")
        val result = when {
            s1 != null -> channel.writeObjects(what, a, b, s1, null)
            intent.hasExtra("b") -> channel.write(what, a, b)
            intent.hasExtra("a") -> channel.write(what, a)
            else -> channel.write(what)
        }
        AppLog.i("TwProbe[$key]: write($what, $a, $b, $s1) -> $result")
    }

    private fun closeChannels() {
        channels.values.forEach { it.close() }
        channels.clear()
    }

    private fun describe(obj: Any?): String {
        if (obj == null) return "null"
        if (obj is String) return "\"$obj\""
        val fields = obj.javaClass.fields.joinToString { f ->
            val v = try { f.get(obj) } catch (e: Exception) { "?" }
            "${f.name}=${if (v is ByteArray) "bytes[${v.size}]" else v}"
        }
        return "${obj.javaClass.simpleName}{$fields}"
    }
}
