package com.andrerinas.openheadunit.main.bento

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.theme.UiTheme
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.connection.carkey.topway.TopwaySound
import com.andrerinas.openheadunit.main.synthwave.Synthwave
import com.andrerinas.openheadunit.main.synthwave.SynthwaveBackdropView
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils

/**
 * The car's sound in this app: presets, bass/middle/treble with their frequencies, balance, fader,
 * subwoofer and loudness, sent to the car's sound processor as TopWay's EQ app sends them
 * ([TopwaySound]). The processor shapes every source, so this is the sound of Android Auto and
 * CarPlay as much as of Bluetooth and the radio.
 *
 * It drives only the processor TopWay calls "normal". For the others, or with no answer, it hands
 * over to TopWay's own EQ app.
 */
class SoundActivity : BaseActivity() {

    companion object {
        private const val CLOCK_TICK_MS = 10_000L
        private const val ANSWER_WAIT_MS = 4_000L
        private const val TOPWAY_EQ = "com.tw.eq"

        fun open(context: Context) {
            context.startActivity(Intent(context, SoundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var sound: TopwaySound
    private lateinit var topBar: Synthwave.TopBar
    private val main = Handler(Looper.getMainLooper())

    private val presetViews = ArrayList<TextView>()
    private val bandBars = ArrayList<SeekBar>()
    private val bandValues = ArrayList<TextView>()
    private val frequencyButtons = ArrayList<TextView>()
    private lateinit var balanceBar: SeekBar
    private lateinit var balanceValue: TextView
    private lateinit var faderBar: SeekBar
    private lateinit var faderValue: TextView
    private lateinit var subwooferBar: SeekBar
    private lateinit var subwooferValue: TextView
    private val loudnessViews = ArrayList<TextView>()
    /** A slider being dragged keeps its place; the processor's answer must not move it under the finger. */
    private var dragging = false

    private val listener: () -> Unit = { render() }
    private val clockTick = object : Runnable {
        override fun run() {
            topBar.render()
            main.postDelayed(this, CLOCK_TICK_MS)
        }
    }
    private val noAnswer = Runnable {
        // A channel that never opened has its own, more useful message; keep that one.
        if (sound.chip == TopwaySound.Chip.UNKNOWN && sound.error == null) showNotice(getString(R.string.sound_no_answer), offerTopway = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound)
        sound = TopwaySound.get(this)
        findViewById<TextView>(R.id.sw_header_title).setText(R.string.sound_title)
        topBar = Synthwave.TopBar(
            clock = findViewById(R.id.sw_header_clock),
            date = null,
            bluetooth = findViewById(R.id.sw_header_bt),
            wifi = findViewById(R.id.sw_header_wifi),
        )
        findViewById<View>(R.id.bento_back).setOnClickListener { finish() }
        findViewById<SynthwaveBackdropView>(R.id.sw_backdrop).frost(
            findViewById(R.id.sound_eq_card), findViewById(R.id.sound_zone_card),
        )
        Synthwave.scope(findViewById(R.id.sound_bands))
        Synthwave.scope(findViewById(R.id.sound_zone))
        findViewById<View>(R.id.sound_reset).setOnClickListener { sound.reset() }
        findViewById<View>(R.id.sound_centre).setOnClickListener { sound.setZone(TopwaySound.CENTRE, TopwaySound.CENTRE) }
        findViewById<View>(R.id.sound_open_topway).setOnClickListener { openTopwayEq() }

        buildPresets()
        buildBands()
        buildZone()
        listOf(R.id.bento_back, R.id.sound_reset, R.id.sound_centre).forEach { BentoUi.addPressFeedback(findViewById(it)) }
        sound.attach(listener)
        showNotice(getString(R.string.sound_reading), offerTopway = false)
        main.postDelayed(noAnswer, ANSWER_WAIT_MS)
    }

    override fun onResume() {
        super.onResume()
        sound.refresh()
        main.post(clockTick)
        render()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        sound.detach(listener)
    }

    // --- Building ------------------------------------------------------------------------------

    private fun buildPresets() {
        val names = listOf(
            R.string.sound_preset_normal, R.string.sound_preset_jazz, R.string.sound_preset_pop, R.string.sound_preset_classical,
            R.string.sound_preset_rock, R.string.sound_preset_news, R.string.sound_preset_urban, R.string.sound_preset_techno,
        )
        val container = findViewById<LinearLayout>(R.id.sound_presets)
        val gap = dp(8)
        for (row in 0 until 2) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                    if (row > 0) topMargin = gap
                }
            }
            for (col in 0 until 4) {
                val preset = row * 4 + col + 1
                val button = glassButton(getString(names[preset - 1]), 15f).apply {
                    setOnClickListener { sound.setPreset(preset) }
                }
                line.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (col > 0) leftMargin = gap
                })
                presetViews.add(button)
            }
            container.addView(line)
        }
    }

    private fun buildBands() {
        val container = findViewById<LinearLayout>(R.id.sound_bands)
        val names = listOf(R.string.sound_bass, R.string.sound_middle, R.string.sound_treble)
        for (band in 0..2) {
            val row = row(container)
            row.addView(label(getString(names[band])), LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT))
            val frequency = glassButton("", 13f).apply {
                setOnClickListener { sound.setFrequency(band, (sound.state.frequencies[band] + 1) % 4) }
            }
            row.addView(frequency, LinearLayout.LayoutParams(dp(78), dp(36)))
            frequencyButtons.add(frequency)
            val bar = slider(TopwaySound.BAND_MAX) { sound.setBand(band, it) }
            row.addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(6) })
            bandBars.add(bar)
            val value = valueText()
            row.addView(value, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            bandValues.add(value)
        }
    }

    private fun buildZone() {
        val container = findViewById<LinearLayout>(R.id.sound_zone)
        fun sliderRow(name: Int, max: Int, onChange: (Int) -> Unit): Pair<SeekBar, TextView> {
            val row = row(container)
            row.addView(label(getString(name)), LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
            val bar = slider(max, onChange)
            row.addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f))
            val value = valueText()
            row.addView(value, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            return bar to value
        }
        sliderRow(R.string.sound_balance, TopwaySound.ZONE_MAX) { sound.setZone(it, sound.state.fader) }
            .let { (bar, value) -> balanceBar = bar; balanceValue = value }
        sliderRow(R.string.sound_fader, TopwaySound.ZONE_MAX) { sound.setZone(sound.state.balance, it) }
            .let { (bar, value) -> faderBar = bar; faderValue = value }
        sliderRow(R.string.sound_subwoofer, TopwaySound.SUBWOOFER_MAX) { sound.setSubwoofer(it) }
            .let { (bar, value) -> subwooferBar = bar; subwooferValue = value }

        val row = row(container)
        row.addView(label(getString(R.string.sound_loudness)), LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
        for (level in 0..TopwaySound.LOUDNESS_MAX) {
            val text = if (level == 0) getString(R.string.sound_off) else level.toString()
            val button = glassButton(text, 15f).apply { setOnClickListener { sound.setLoudness(level) } }
            row.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (level > 0) leftMargin = dp(6) })
            loudnessViews.add(button)
        }
    }

    // --- Showing -------------------------------------------------------------------------------

    private fun render() {
        sound.error?.let {
            showNotice(getString(R.string.sound_unavailable, it), offerTopway = true)
            return
        }
        when (sound.chip) {
            TopwaySound.Chip.UNKNOWN -> return // the "reading" notice stays until the answer or the timeout
            TopwaySound.Chip.PT2313, TopwaySound.Chip.ROHM -> {
                showNotice(getString(R.string.sound_other_chip, sound.chip.name), offerTopway = true)
                return
            }
            TopwaySound.Chip.NORMAL -> hideNotice()
        }
        val s = sound.state
        presetViews.forEachIndexed { index, view -> view.isSelected = s.preset == index + 1 }
        for (band in 0..2) {
            if (!dragging) bandBars[band].progress = s.bands[band]
            bandValues[band].text = signed(s.bands[band] - TopwaySound.FLAT)
            frequencyButtons[band].text = TopwaySound.BAND_FREQUENCIES[band][s.frequencies[band]]
        }
        if (!dragging) {
            balanceBar.progress = s.balance
            faderBar.progress = s.fader
            subwooferBar.progress = s.subwoofer
        }
        balanceValue.text = zoneText(s.balance, 'L', 'R')
        faderValue.text = zoneText(s.fader, 'F', 'R')
        subwooferValue.text = s.subwoofer.toString()
        loudnessViews.forEachIndexed { level, view -> view.isSelected = s.loudness == level }
    }

    private fun showNotice(text: String, offerTopway: Boolean) {
        findViewById<View>(R.id.sound_notice).visibility = View.VISIBLE
        findViewById<TextView>(R.id.sound_notice_text).text = text
        findViewById<View>(R.id.sound_open_topway).visibility = if (offerTopway) View.VISIBLE else View.GONE
    }

    private fun hideNotice() {
        main.removeCallbacks(noAnswer)
        findViewById<View>(R.id.sound_notice).visibility = View.GONE
    }

    private fun openTopwayEq() {
        val intent = packageManager.getLaunchIntentForPackage(TOPWAY_EQ)
        if (intent == null) {
            ToastUtils.showToast(this, getString(R.string.bento_app_missing, getString(R.string.sw_app_eq)), Toast.LENGTH_SHORT, force = true)
            return
        }
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { AppLog.w("Sound: could not open TopWay's EQ: ${it.message}") }
    }

    // --- Pieces --------------------------------------------------------------------------------

    private fun row(container: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        container.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        typeface = UiTheme.font(this@SoundActivity, R.attr.uiFontDisplay)
        isAllCaps = UiTheme.caps(this@SoundActivity)
        textSize = 16f
        setTextColor(UiTheme.color(this@SoundActivity, R.attr.uiTextTitle))
        setShadowLayer(1f, 2f, 2f, UiTheme.color(this@SoundActivity, R.attr.uiShadow))
        includeFontPadding = false
    }

    private fun valueText() = TextView(this).apply {
        typeface = UiTheme.font(this@SoundActivity, R.attr.uiFontDigitsBold)
        textSize = 15f
        gravity = Gravity.END
        setTextColor(UiTheme.color(this@SoundActivity, R.attr.uiTextIce))
        includeFontPadding = false
    }

    private fun glassButton(text: String, size: Float) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        background = UiTheme.drawable(context, R.attr.uiBtnBg)
        typeface = UiTheme.font(this@SoundActivity, R.attr.uiFontLabel)
        textSize = size
        isAllCaps = UiTheme.caps(this@SoundActivity)
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
        maxLines = 1
    }

    private fun slider(max: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progressDrawable = UiTheme.drawable(this@SoundActivity, R.attr.uiSeek)
        thumb = UiTheme.drawable(this@SoundActivity, R.attr.uiSeekThumb)
        splitTrack = false
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                render()
            }
        })
    }

    private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

    /** 9 is the middle; below it toward [low], above it toward [high]: "L3", "0", "R2". */
    private fun zoneText(value: Int, low: Char, high: Char): String = when {
        value < TopwaySound.CENTRE -> "$low${TopwaySound.CENTRE - value}"
        value > TopwaySound.CENTRE -> "$high${value - TopwaySound.CENTRE}"
        else -> "0"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
