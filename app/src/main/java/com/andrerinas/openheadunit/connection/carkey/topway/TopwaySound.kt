package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The car's sound processor, as TopWay's own EQ app (`com.tw.eq`) drives it. Read from that app's
 * code (EQ [1.0]: `com.tw.eq.b.a`, `com.tw.eq.b.b`, `com.tw.eq.activity.NormalActivity`):
 *
 *  - TWUtil message 257 carries every setting as `write(257, sub, value)`; `write(257, 255)` asks
 *    for the state, which comes back on 257 as a byte array.
 *  - Message 266 answered with a string `a-b-c-dddT…` names the processor: the hex digit T
 *    (field 3, character 3) is 3 for a PT2313, 6 for a Rohm, anything else for the "normal" one.
 *
 * Only the normal processor's controls are offered here, the plain integer ones its EQ app sends:
 *  - preset: sub 0, 1..8 (Normal, Jazz, Pop, Classical, Rock, News, Urban, Techno);
 *  - bass, middle, treble level: sub 1/2/3, 0..14 (7 flat), and their centre frequency: sub 8/9/10,
 *    0..3 (bass 60/80/100/120 Hz, middle 500 Hz/1/1.5/2 kHz, treble 7.5/10/12.5/15 kHz);
 *  - subwoofer: sub 4, 0..14; loudness: sub 5, 0..3;
 *  - fader: sub 6, 0..18 (9 centre); balance: sub 7, 0..18 (9 centre).
 * The state answer carries the preset (byte 0, one-based), subwoofer (4), loudness (5), fader (6)
 * and balance (7); band levels are not read back, so the last values set here are kept.
 *
 * The processor sits after the unit's source switch, so its settings shape every source: Bluetooth,
 * radio, and Android's own audio, Android Auto and CarPlay included.
 */
class TopwaySound private constructor(context: Context) {

    enum class Chip { UNKNOWN, NORMAL, PT2313, ROHM }

    data class State(
        val preset: Int = 1,
        val bands: IntArray = intArrayOf(FLAT, FLAT, FLAT),
        val frequencies: IntArray = intArrayOf(0, 0, 0),
        val subwoofer: Int = 0,
        val loudness: Int = 0,
        val fader: Int = CENTRE,
        val balance: Int = CENTRE,
    )

    companion object {
        const val MSG_SOUND = 257
        const val MSG_INFO = 266
        const val QUERY = 255

        const val SUB_PRESET = 0
        const val SUB_SUBWOOFER = 4
        const val SUB_LOUDNESS = 5
        const val SUB_FADER = 6
        const val SUB_BALANCE = 7
        /** Bass, middle, treble levels are subs 1..3; their centre frequencies 8..10. */
        const val SUB_BAND_FIRST = 1
        const val SUB_FREQUENCY_OFFSET = 7

        const val FLAT = 7
        const val BAND_MAX = 14
        const val SUBWOOFER_MAX = 14
        const val LOUDNESS_MAX = 3
        const val CENTRE = 9
        const val ZONE_MAX = 18
        const val PRESETS = 8

        val BAND_FREQUENCIES = arrayOf(
            arrayOf("60 Hz", "80 Hz", "100 Hz", "120 Hz"),
            arrayOf("500 Hz", "1 kHz", "1.5 kHz", "2 kHz"),
            arrayOf("7.5 kHz", "10 kHz", "12.5 kHz", "15 kHz"),
        )

        private const val PREFS = "topway_sound"

        @Volatile private var instance: TopwaySound? = null

        fun get(context: Context): TopwaySound =
            instance ?: synchronized(this) { instance ?: TopwaySound(context.applicationContext).also { instance = it } }

        /** The processor named by message 266's answer, as `com.tw.eq.EQActivity` reads it. */
        fun chipOf(info: String?): Chip {
            val digit = info?.split("-")?.getOrNull(3)?.getOrNull(3) ?: return Chip.UNKNOWN
            return when (Character.digit(digit, 16)) {
                3 -> Chip.PT2313
                6 -> Chip.ROHM
                -1 -> Chip.UNKNOWN
                else -> Chip.NORMAL
            }
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var channel: TwUtilChannel? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile var chip: Chip = Chip.UNKNOWN
        private set
    @Volatile var state: State = loadState()
        private set
    /** Why the channel could not open, if it could not. */
    @Volatile var error: String? = null
        private set

    /** Main thread. Opens the channel for the first listener and asks for the processor and its state. */
    fun attach(listener: () -> Unit) {
        listeners.add(listener)
        if (channel == null) open()
        refresh()
    }

    fun detach(listener: () -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            channel?.close()
            channel = null
        }
    }

    fun refresh() {
        channel?.write(MSG_INFO, QUERY)
        channel?.write(MSG_SOUND, QUERY)
    }

    fun setPreset(preset: Int) {
        val value = preset.coerceIn(1, PRESETS)
        send(SUB_PRESET, value)
        update(state.copy(preset = value))
    }

    /** [band] 0 bass, 1 middle, 2 treble; [level] 0..14 with 7 flat. */
    fun setBand(band: Int, level: Int) {
        if (band !in 0..2) return
        val value = level.coerceIn(0, BAND_MAX)
        // As the EQ app does: the band's centre first, then its level.
        send(SUB_BAND_FIRST + band + SUB_FREQUENCY_OFFSET, state.frequencies[band])
        send(SUB_BAND_FIRST + band, value)
        update(state.copy(bands = state.bands.copyOf().also { it[band] = value }))
    }

    /** [band] 0 bass, 1 middle, 2 treble; [frequency] 0..3, see [BAND_FREQUENCIES]. */
    fun setFrequency(band: Int, frequency: Int) {
        if (band !in 0..2) return
        val value = frequency.coerceIn(0, 3)
        send(SUB_BAND_FIRST + band + SUB_FREQUENCY_OFFSET, value)
        send(SUB_BAND_FIRST + band, state.bands[band])
        update(state.copy(frequencies = state.frequencies.copyOf().also { it[band] = value }))
    }

    fun setSubwoofer(level: Int) {
        val value = level.coerceIn(0, SUBWOOFER_MAX)
        send(SUB_SUBWOOFER, value)
        update(state.copy(subwoofer = value))
    }

    fun setLoudness(level: Int) {
        val value = level.coerceIn(0, LOUDNESS_MAX)
        send(SUB_LOUDNESS, value)
        update(state.copy(loudness = value))
    }

    /** Balance (left 0 .. right 18) and fader (front 0 .. rear 18), 9 each in the middle. */
    fun setZone(balance: Int, fader: Int) {
        val x = balance.coerceIn(0, ZONE_MAX)
        val y = fader.coerceIn(0, ZONE_MAX)
        send(SUB_FADER, y)
        send(SUB_BALANCE, x)
        update(state.copy(balance = x, fader = y))
    }

    /** Flat bands, no extra bass or loudness, sound in the middle of the car. */
    fun reset() {
        setPreset(1)
        for (band in 0..2) setBand(band, FLAT)
        setSubwoofer(0)
        setLoudness(0)
        setZone(CENTRE, CENTRE)
    }

    private fun send(sub: Int, value: Int) {
        val ch = channel ?: return
        AppLog.i("TopwaySound: write(257, $sub, $value)")
        ch.write(MSG_SOUND, sub, value)
    }

    private fun open() {
        // The EQ app opens TWUtil with its no-argument constructor on 257 and 266.
        val ch = TwUtilChannel("sound", shortArrayOf(MSG_SOUND.toShort(), MSG_INFO.toShort()))
        error = ch.open(app) { what, arg1, _, obj -> main.post { onMessage(what, arg1, obj) } }
        if (error == null) channel = ch else AppLog.w("TopwaySound: channel unavailable: $error")
    }

    private fun onMessage(what: Int, arg1: Int, obj: Any?) {
        when (what) {
            MSG_INFO -> if (arg1 == 0 && obj is String) {
                val found = chipOf(obj)
                if (found != chip) {
                    chip = found
                    AppLog.i("TopwaySound: sound processor $found (info=$obj)")
                    notifyListeners()
                }
            }
            MSG_SOUND -> (obj as? ByteArray)?.let { bytes ->
                if (bytes.size < 8) return
                val read = state.copy(
                    preset = bytes[0].toInt().coerceIn(1, PRESETS),
                    subwoofer = bytes[4].toInt().coerceIn(0, SUBWOOFER_MAX),
                    loudness = bytes[5].toInt().coerceIn(0, LOUDNESS_MAX),
                    fader = bytes[6].toInt().coerceIn(0, ZONE_MAX),
                    balance = bytes[7].toInt().coerceIn(0, ZONE_MAX),
                )
                update(read)
            }
        }
    }

    private fun update(next: State) {
        state = next
        saveState(next)
        notifyListeners()
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    private fun loadState(): State = State(
        preset = prefs.getInt("preset", 1),
        bands = IntArray(3) { prefs.getInt("band$it", FLAT) },
        frequencies = IntArray(3) { prefs.getInt("freq$it", 0) },
        subwoofer = prefs.getInt("subwoofer", 0),
        loudness = prefs.getInt("loudness", 0),
        fader = prefs.getInt("fader", CENTRE),
        balance = prefs.getInt("balance", CENTRE),
    )

    private fun saveState(s: State) {
        prefs.edit().apply {
            putInt("preset", s.preset)
            for (i in 0..2) { putInt("band$i", s.bands[i]); putInt("freq$i", s.frequencies[i]) }
            putInt("subwoofer", s.subwoofer)
            putInt("loudness", s.loudness)
            putInt("fader", s.fader)
            putInt("balance", s.balance)
        }.apply()
    }
}
