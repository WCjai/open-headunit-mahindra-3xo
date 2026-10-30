package com.andrerinas.openheadunit.main.bento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayRadio
import com.andrerinas.openheadunit.utils.ToastUtils

/**
 * The car's FM/AM radio inside this app, in the bento style. Drives the unit's own tuner through
 * [TopwayRadio], the same MCU commands TopWay's radio app sends; opening this screen puts the
 * car's speakers on the tuner, as opening that app does.
 *
 * Stations: tap to tune, hold to store the playing frequency there. Eighteen per band, six a page.
 */
class BentoRadioActivity : BaseActivity() {

    companion object {
        private const val PER_PAGE = 6
        private const val PAGES = TopwayRadio.PRESET_SLOTS / PER_PAGE

        fun open(context: Context) {
            context.startActivity(Intent(context, BentoRadioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private lateinit var radio: TopwayRadio
    private lateinit var title: TextView
    private lateinit var station: TextView
    private lateinit var power: View
    private lateinit var tuner: View
    private lateinit var kicker: TextView
    private lateinit var frequency: TextView
    private lateinit var unit: TextView
    private lateinit var radioText: TextView
    private lateinit var dial: TunerDialView
    private lateinit var bandFm: TextView
    private lateinit var bandAm: TextView
    private lateinit var af: TextView
    private lateinit var ta: TextView
    private lateinit var scan: TextView
    private lateinit var page: TextView
    private lateinit var hint: TextView
    private val presetViews = ArrayList<View>()

    private var currentPage = 0
    private var pageChosen = false
    /** Shown while the dial is dragged, until the tuner reports where it went. */
    private var previewFrequency = 0

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bento_radio)
        radio = TopwayRadio.get(this)

        title = findViewById(R.id.radio_title)
        station = findViewById(R.id.radio_station)
        power = findViewById(R.id.radio_power)
        tuner = findViewById(R.id.radio_tuner)
        kicker = findViewById(R.id.radio_kicker)
        frequency = findViewById(R.id.radio_frequency)
        unit = findViewById(R.id.radio_unit)
        radioText = findViewById(R.id.radio_text)
        dial = findViewById(R.id.radio_dial)
        bandFm = findViewById(R.id.radio_band_fm)
        bandAm = findViewById(R.id.radio_band_am)
        af = findViewById(R.id.radio_af)
        ta = findViewById(R.id.radio_ta)
        scan = findViewById(R.id.radio_scan)
        page = findViewById(R.id.radio_page)
        hint = findViewById(R.id.radio_hint)
        radioText.isSelected = true // marquee

        findViewById<View>(R.id.bento_back).setOnClickListener { finish() }
        bandFm.setOnClickListener { radio.selectFm() }
        bandAm.setOnClickListener { radio.selectAm() }
        af.setOnClickListener { radio.setAf(!radio.af) }
        ta.setOnClickListener { radio.setTa(!radio.ta) }
        scan.setOnClickListener { radio.autoStore() }
        // Off gives the speakers back; on takes them again.
        power.setOnClickListener {
            if (radio.isPlaying) {
                radio.stop()
                com.andrerinas.openheadunit.App.provide(this).topwayCallBridge.returnToBluetoothMusic()
            } else {
                radio.enterForeground()
            }
            radio.refresh()
        }
        findViewById<View>(R.id.radio_seek_down).setOnClickListener { radio.seekDown() }
        findViewById<View>(R.id.radio_seek_up).setOnClickListener { radio.seekUp() }
        findViewById<View>(R.id.radio_step_down).setOnClickListener { radio.stepDown() }
        findViewById<View>(R.id.radio_step_up).setOnClickListener { radio.stepUp() }
        findViewById<View>(R.id.radio_page_prev).setOnClickListener { turnPage(-1) }
        findViewById<View>(R.id.radio_page_next).setOnClickListener { turnPage(1) }
        dial.listener = object : TunerDialView.Listener {
            override fun onPreview(frequency: Int) {
                previewFrequency = frequency
                showFrequency(frequency)
            }
            override fun onTune(frequency: Int) {
                previewFrequency = frequency
                radio.tune(frequency)
            }
        }

        buildPresets()
        listOf(R.id.bento_back, R.id.radio_band_fm, R.id.radio_band_am, R.id.radio_af, R.id.radio_ta,
            R.id.radio_scan, R.id.radio_seek_down, R.id.radio_seek_up, R.id.radio_step_down,
            R.id.radio_step_up, R.id.radio_page_prev, R.id.radio_page_next, R.id.radio_power,
        ).forEach { BentoUi.addPressFeedback(findViewById(it)) }

        radio.attach(this, listener)
    }

    override fun onResume() {
        super.onResume()
        TopwayForegroundKeeper.onResumed(TopwayForegroundKeeper.Screen.RADIO)
        radio.enterForeground()
        radio.refresh()
        render()
    }

    override fun onPause() {
        super.onPause()
        TopwayForegroundKeeper.onPaused(TopwayForegroundKeeper.Screen.RADIO)
    }

    /**
     * Leaving the screen (Home, Back or another app) keeps the tuner playing, as TopWay's own
     * radio does; the home's Now Playing card turns it off. Coming back claims it again (onResume).
     */
    override fun onStop() {
        super.onStop()
        radio.enterBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        radio.detach(this, listener)
    }

    /** Three rows of two station buttons. */
    private fun buildPresets() {
        val container = findViewById<LinearLayout>(R.id.radio_presets)
        val gap = resources.getDimensionPixelSize(R.dimen.bento_gap) * 2 / 3
        for (row in 0 until PER_PAGE / 2) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (row > 0) topMargin = gap
                }
            }
            for (col in 0 until 2) {
                val index = row * 2 + col
                val button = layoutInflater.inflate(R.layout.view_bento_preset, line, false).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (col > 0) leftMargin = gap
                    }
                    setOnClickListener { radio.selectPreset(currentPage * PER_PAGE + index) }
                    setOnLongClickListener {
                        val slot = currentPage * PER_PAGE + index
                        radio.storePreset(slot)
                        ToastUtils.showToast(this@BentoRadioActivity, getString(R.string.bento_radio_saved,
                            TopwayRadio.format(radio.band, radio.frequency), TopwayRadio.unitOf(radio.band)),
                            Toast.LENGTH_SHORT, force = true)
                        radio.refresh()
                        true
                    }
                }
                BentoUi.addPressFeedback(button, focusScale = 1.03f)
                line.addView(button)
                presetViews.add(button)
            }
            container.addView(line)
        }
    }

    private fun turnPage(delta: Int) {
        pageChosen = true
        currentPage = (currentPage + delta + PAGES) % PAGES
        renderPresets()
    }

    private fun showFrequency(value: Int) {
        frequency.text = TopwayRadio.format(radio.band, value)
        unit.text = TopwayRadio.unitOf(radio.band)
    }

    private fun render() {
        val err = radio.error
        if (err != null) {
            kicker.text = getString(R.string.bento_radio_unavailable)
            return
        }
        // The tuner's report wins over a drag preview once it has caught up.
        if (previewFrequency != 0 && previewFrequency == radio.frequency) previewFrequency = 0
        showFrequency(if (previewFrequency != 0) previewFrequency else radio.frequency)

        val bandName = TopwayRadio.bandName(radio.band)
        val playing = radio.isPlaying
        title.setText(if (radio.isFm) R.string.tv_radio_fm_title else R.string.tv_radio_am_title)
        kicker.text = "$bandName  ·  ${getString(R.string.tv_radio_tuning)}"
        station.text = if (playing) radio.stationName else getString(R.string.tv_radio_off)
        radioText.text = radio.radioText
        radioText.visibility = if (radio.radioText.isBlank() || !playing) View.INVISIBLE else View.VISIBLE
        power.isSelected = playing
        tuner.alpha = if (playing) 1f else 0.45f
        val range = radio.range
        dial.setTuning(range.min, range.max, range.step, !radio.isFm, radio.frequency)

        bandFm.isSelected = radio.isFm
        bandAm.isSelected = !radio.isFm
        af.visibility = if (radio.isFm) View.VISIBLE else View.GONE
        ta.visibility = af.visibility
        af.isSelected = radio.af
        ta.isSelected = radio.ta
        scan.isSelected = radio.autoStoring
        scan.setText(if (radio.autoStoring) R.string.bento_radio_scanning else R.string.bento_radio_scan)

        // Open on the page holding the playing station, until the user turns pages.
        if (!pageChosen && radio.currentSlot in 0 until TopwayRadio.PRESET_SLOTS) currentPage = radio.currentSlot / PER_PAGE
        renderPresets()
    }

    private fun renderPresets() {
        val presets = radio.presets
        page.text = getString(R.string.bento_radio_page, currentPage + 1, PAGES)
        presetViews.forEachIndexed { index, view ->
            val preset = presets[currentPage * PER_PAGE + index]
            val freqView = view.findViewById<TextView>(R.id.preset_frequency)
            val nameView = view.findViewById<TextView>(R.id.preset_name)
            if (preset.isEmpty) {
                freqView.text = "+"
                freqView.alpha = 0.45f
                nameView.setText(R.string.bento_radio_preset_empty)
                view.isSelected = false
            } else {
                freqView.text = TopwayRadio.format(radio.band, preset.frequency)
                freqView.alpha = 1f
                nameView.text = preset.name.ifBlank { TopwayRadio.unitOf(radio.band) }
                view.isSelected = preset.frequency == radio.frequency
            }
        }
        hint.setText(if (presets.all { it.isEmpty }) R.string.bento_radio_scan_hint else R.string.tv_radio_hint)
    }
}
