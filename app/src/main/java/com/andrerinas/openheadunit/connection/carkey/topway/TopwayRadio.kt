package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale

/**
 * The car's FM/AM tuner on a TopWay unit, driven the way TopWay's own radio app (`com.tw.radio`)
 * drives it: through the MCU on `TWUtil(1)`. Recovered from that app and confirmed on the unit.
 *
 * MCU messages are delivered to every app that opens their ids, so this runs beside the stock
 * radio app without taking anything from it (unlike the Bluetooth module's serial line).
 *
 * ```
 * 0x401 (1025) tuner     arg1 0: status flags in arg2 (0x80 = auto-store running)
 *                             1: band in arg2 (0 FM1, 1 FM2, 2 AM)
 *                             2: frequency in arg2 (FM in 10 kHz, AM in kHz), station name in obj
 *                             4: the preset slot the frequency is on
 *        commands  (255) report all; (0,0) auto-store; (1,0|1) seek up|down;
 *                  (2,0|1) step up|down; (5,band) choose band; (8,slot) store the frequency in a slot
 * 0x402 (1026) presets   arg1 low byte = slot (0-17), arg2 = frequency (0 = empty), obj = name
 *        commands  (slot, band) tune to a preset; (255, frequency) tune to a frequency
 * 0x404 (1028) RDS       arg1 flags (0x40 AF, 0x20 TA), arg2 >> 8 = programme type, obj = name
 *        commands  (255) report; (0, on) AF; (1, on) TA; (4, n) programme-type search, not a store
 * 0x405 (1029) RDS radio text in obj
 * 0x406 (1030) region    arg1 1/2 FM max/min, 3/4 AM max/min, 5/6 FM/AM step; command (0) report
 * 0x9E00 (40448)         the screen in front: 1 radio, 129 radio in the background
 * 0x9E11 (40465, 0xC0, 1) put the car's speakers on the tuner
 * ```
 *
 * One instance per process ([get]); screens [attach] while they show it.
 */
class TopwayRadio private constructor(context: Context) {

    companion object {
        const val MSG_SOURCE = 769
        const val MSG_TUNER = 1025
        const val MSG_PRESET = 1026
        const val MSG_RDS = 1028
        const val MSG_RADIO_TEXT = 1029
        const val MSG_REGION = 1030
        const val MSG_UI = 40448
        const val MSG_AUDIO_SOURCE = 40465

        const val BAND_FM1 = 0
        const val BAND_FM2 = 1
        const val BAND_AM = 2

        const val PRESET_SLOTS = 18
        /** The MCU's audio source number for the tuner, as `com.tw.radio` claims it. */
        const val SOURCE_RADIO = 1

        private const val STATUS_AUTO_STORE = 0x80
        private const val RDS_AF = 0x40
        private const val RDS_TA = 0x20

        @Volatile private var instance: TopwayRadio? = null

        fun get(context: Context): TopwayRadio =
            instance ?: synchronized(this) {
                instance ?: TopwayRadio(context.applicationContext).also { instance = it }
            }

        /** "101.8" for FM, "1008" for AM; FM keeps a second decimal only on a 50 kHz raster. */
        fun format(band: Int, frequency: Int): String = when {
            frequency <= 0 -> "--"
            band == BAND_AM -> frequency.toString()
            frequency % 10 == 0 -> String.format(Locale.US, "%.1f", frequency / 100.0)
            else -> String.format(Locale.US, "%.2f", frequency / 100.0)
        }

        fun unitOf(band: Int): String = if (band == BAND_AM) "kHz" else "MHz"

        fun bandName(band: Int): String = when (band) {
            BAND_FM1 -> "FM1"
            BAND_FM2 -> "FM2"
            BAND_AM -> "AM"
            else -> "FM"
        }
    }

    data class Range(val min: Int, val max: Int, val step: Int)

    data class Preset(val slot: Int, val frequency: Int, val name: String) {
        val isEmpty: Boolean get() = frequency <= 0
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val owners = LinkedHashSet<Any>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var channel: TwUtilChannel? = null
    /**
     * `TWUtil(16)`, the system service's channel: the MCU answers the audio-source query (769) only
     * there, though every channel then hears the answer.
     */
    private var sourceChannel: TwUtilChannel? = null

    /** Why the tuner is unreachable, or null while it answers. */
    var error: String? = null
        private set

    var band = BAND_FM1
        private set
    var frequency = 0
        private set
    var fmRange = Range(8750, 10800, 10)
        private set
    var amRange = Range(531, 1629, 9)
        private set
    var stationName = ""
        private set
    var radioText = ""
        private set
    var currentSlot = -1
        private set
    var autoStoring = false
        private set
    var af = false
        private set
    var ta = false
        private set
    /** The MCU's current audio source, -1 until reported. [SOURCE_RADIO] while the tuner plays. */
    var source = -1
        private set

    private val presetFreq = IntArray(PRESET_SLOTS)
    private val presetName = Array(PRESET_SLOTS) { "" }

    val range: Range get() = if (band == BAND_AM) amRange else fmRange
    val isFm: Boolean get() = band != BAND_AM
    val isPlaying: Boolean get() = source == SOURCE_RADIO

    val presets: List<Preset>
        get() = (0 until PRESET_SLOTS).map { Preset(it, presetFreq[it], presetName[it]) }

    /** Main thread. Opens the tuner channel for the first owner and asks for everything. */
    fun attach(owner: Any, listener: () -> Unit) {
        listeners.add(listener)
        if (!owners.add(owner) || owners.size > 1) {
            listener()
            return
        }
        if (!TwUtilChannel.available) {
            error = "This head unit has no TopWay tuner"
            listener()
            return
        }
        val ch = TwUtilChannel(
            "radio",
            shortArrayOf(MSG_SOURCE.toShort(), MSG_TUNER.toShort(), MSG_PRESET.toShort(), MSG_RDS.toShort(),
                MSG_RADIO_TEXT.toShort(), MSG_REGION.toShort(), MSG_UI.toShort()),
            ctorArg = 1,
        )
        error = ch.open(app) { what, arg1, arg2, obj -> main.post { onMessage(what, arg1, arg2, obj) } }
        if (error == null) {
            channel = ch
            val sc = TwUtilChannel("radio-source", shortArrayOf(MSG_SOURCE.toShort()), ctorArg = 16)
            val sourceError = sc.open(app) { what, arg1, _, _ -> if (what == MSG_SOURCE) main.post { onMessage(what, arg1, 0, null) } }
            if (sourceError == null) sourceChannel = sc else AppLog.w("TopwayRadio: audio source unknown: $sourceError")
            refresh()
        } else {
            AppLog.w("TopwayRadio: tuner unreachable: $error")
        }
        notifyListeners()
    }

    /** Main thread. Closes the channel after the last owner. */
    fun detach(owner: Any, listener: () -> Unit) {
        listeners.remove(listener)
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        channel?.close()
        channel = null
        sourceChannel?.close()
        sourceChannel = null
    }

    fun refresh() {
        write(MSG_REGION, 0)
        write(MSG_TUNER, 255)
        write(MSG_RDS, 255)
        sourceChannel?.write(MSG_SOURCE, 255)
    }

    /**
     * The radio screen is in front: tell the MCU, and put the car's speakers on the tuner. What
     * `com.tw.radio` does when it opens.
     */
    fun enterForeground() {
        write(MSG_UI, 1)
        write(MSG_AUDIO_SOURCE, 0xC0, SOURCE_RADIO)
        sourceChannel?.write(MSG_SOURCE, 255)
    }

    /** The radio screen went behind another. The tuner keeps playing, as with the stock app. */
    fun enterBackground() {
        write(MSG_UI, 129)
    }

    /**
     * Stops the radio: tells the MCU the radio screen is gone and gives the speakers back
     * (`write(0x9E11, 0xC0, 129)`), what `com.tw.radio` does when it closes.
     */
    fun stop() {
        write(MSG_UI, 129)
        write(MSG_AUDIO_SOURCE, 0xC0, 129)
        sourceChannel?.write(MSG_SOURCE, 255)
    }

    /** Puts the car's speakers on the tuner without the radio screen. */
    fun play() = write(MSG_AUDIO_SOURCE, 0xC0, SOURCE_RADIO)

    /** Turns the radio on from another screen (the home): speakers on the tuner, its screen behind. */
    fun playInBackground() {
        play()
        write(MSG_UI, 129)
        sourceChannel?.write(MSG_SOURCE, 255)
    }

    fun seekUp() = write(MSG_TUNER, 1, 0)
    fun seekDown() = write(MSG_TUNER, 1, 1)
    fun stepUp() = write(MSG_TUNER, 2, 0)
    fun stepDown() = write(MSG_TUNER, 2, 1)
    fun autoStore() = write(MSG_TUNER, 0, 0)

    fun selectBand(newBand: Int) = write(MSG_TUNER, 5, newBand.coerceIn(BAND_FM1, BAND_AM))

    /** FM and AM as two choices: FM picks FM1 unless an FM band is already on. */
    fun selectFm() { if (band == BAND_AM) selectBand(BAND_FM1) }
    fun selectAm() { if (band != BAND_AM) selectBand(BAND_AM) }

    fun tune(newFrequency: Int) {
        val r = range
        val snapped = r.min + ((newFrequency - r.min + r.step / 2) / r.step) * r.step
        write(MSG_PRESET, 255, snapped.coerceIn(r.min, r.max))
    }

    fun selectPreset(slot: Int) {
        if (slot !in 0 until PRESET_SLOTS || presetFreq[slot] <= 0) return
        write(MSG_PRESET, slot, band)
    }

    /** Stores the current frequency in [slot] of this band. */
    fun storePreset(slot: Int) {
        if (slot !in 0 until PRESET_SLOTS) return
        // What com.tw.radio sends when a frequency is dropped on a station button.
        write(MSG_TUNER, 8, slot)
    }

    fun setAf(on: Boolean) = write(MSG_RDS, 0, if (on) 1 else 0)
    fun setTa(on: Boolean) = write(MSG_RDS, 1, if (on) 1 else 0)

    /** The next stored preset after the current frequency, wrapping; null with none stored. */
    fun nextPreset(forward: Boolean): Preset? {
        val stored = presets.filterNot { it.isEmpty }
        if (stored.isEmpty()) return null
        val from = currentSlot.takeIf { it >= 0 } ?: stored.indexOfFirst { it.frequency == frequency }
        val ordered = if (forward) stored else stored.reversed()
        return ordered.firstOrNull { if (forward) it.slot > from else it.slot < from } ?: ordered.first()
    }

    private fun write(vararg args: Int) {
        val ch = channel ?: return
        ch.write(*args)
    }

    private fun onMessage(what: Int, arg1: Int, arg2: Int, obj: Any?) {
        val text = (obj as? String)?.trim().orEmpty()
        when (what) {
            MSG_SOURCE -> source = arg1
            MSG_TUNER -> when (arg1) {
                0 -> autoStoring = arg2 and STATUS_AUTO_STORE != 0
                1 -> if (band != arg2) {
                    band = arg2
                    currentSlot = -1
                    stationName = ""
                    radioText = ""
                }
                2 -> {
                    if (frequency != arg2) radioText = ""
                    frequency = arg2
                    stationName = text
                }
                4 -> currentSlot = arg2
            }
            MSG_PRESET -> {
                val slot = arg1 and 0xFF
                if (slot in 0 until PRESET_SLOTS) {
                    presetFreq[slot] = arg2
                    presetName[slot] = text
                }
            }
            MSG_RDS -> {
                af = arg1 and RDS_AF != 0
                ta = arg1 and RDS_TA != 0
                if (text.isNotEmpty()) stationName = text
            }
            MSG_RADIO_TEXT -> radioText = text
            MSG_REGION -> when (arg1) {
                1 -> fmRange = fmRange.copy(max = arg2)
                2 -> fmRange = fmRange.copy(min = arg2)
                3 -> amRange = amRange.copy(max = arg2)
                4 -> amRange = amRange.copy(min = arg2)
                5 -> if (arg2 > 0) fmRange = fmRange.copy(step = arg2)
                6 -> if (arg2 > 0) amRange = amRange.copy(step = arg2)
            }
            else -> return
        }
        notifyListeners()
    }

    // A report arrives as a burst (eighteen presets, then the tuner); one redraw covers it.
    private val notifyRunnable = Runnable { listeners.toList().forEach { it() } }

    private fun notifyListeners() {
        main.removeCallbacks(notifyRunnable)
        main.post(notifyRunnable)
    }
}
