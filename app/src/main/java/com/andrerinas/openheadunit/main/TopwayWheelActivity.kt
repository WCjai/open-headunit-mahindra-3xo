package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayWheel
import com.andrerinas.openheadunit.connection.carkey.topway.TwUtilChannel
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Learning a TopWay unit's steering-wheel buttons, inside this app.
 *
 * Does what the stock "Steering Wheel" app does, with the same commands (see [TopwayWheel]): start
 * learning, pick a function, press the button, end learning. The difference is the list: it offers
 * the functions that mean something in Android Auto, and a button learned here works there straight
 * away through [TopwayWheel.defaultKeyCodeFor]. Learning lives in the MCU, so a button learned here
 * also works in the unit's own apps, exactly as one learned in the stock app.
 *
 * Everything the screen shows comes from the MCU's answers, not from what was sent, so a unit that
 * ignores a command is visible as one.
 */
class TopwayWheelActivity : BaseActivity() {

    private companion object {
        const val ANSWER_TIMEOUT_MS = 2_500L
        const val PROBE_WAIT_MS = 1_800L
    }

    private var channel: TwUtilChannel? = null
    private var variant = TwUtilChannel.CTOR_VARIANTS[0]
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var probing = false
    private val messagesSeen = AtomicInteger(0)
    @Volatile private var lastRaw = ""

    private var learning = false
    private var answered = false
    private var waitingFor: TopwayWheel.Function? = null
    private var impedance: TopwayWheel.Impedance? = null
    private val learned = mutableSetOf<Int>()

    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var toggle: MaterialButton
    private lateinit var low: MaterialButton
    private lateinit var high: MaterialButton
    private lateinit var clear: MaterialButton
    private lateinit var adapter: FunctionAdapter

    /** Nothing came back at all: find the TWUtil constructor this firmware delivers messages on. */
    private val noAnswer = Runnable {
        if (messagesSeen.get() == 0 && !probing) startProbe()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_topway_wheel)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        status = findViewById(R.id.topway_status)
        diag = findViewById(R.id.topway_diag)
        toggle = findViewById(R.id.topway_learn_toggle)
        low = findViewById(R.id.topway_impedance_low)
        high = findViewById(R.id.topway_impedance_high)
        clear = findViewById(R.id.topway_clear)

        adapter = FunctionAdapter()
        findViewById<RecyclerView>(R.id.topway_functions).adapter = adapter

        toggle.setOnClickListener {
            send(if (learning) TopwayWheel.END_LEARNING else TopwayWheel.START_LEARNING)
            // The screen changes only when the MCU confirms; say that the command went out.
            status.setText(R.string.topway_wheel_status_sent)
        }
        low.setOnClickListener { setImpedance(TopwayWheel.Impedance.LOW) }
        high.setOnClickListener { setImpedance(TopwayWheel.Impedance.HIGH) }
        clear.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.topway_wheel_clear)
                .setMessage(R.string.topway_wheel_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    send(TopwayWheel.CLEAR_ALL)
                    learned.clear()
                    waitingFor = null
                    adapter.notifyDataSetChanged()
                    send(TopwayWheel.QUERY)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        variant = TwUtilChannel.workingCtor(this) ?: TwUtilChannel.CTOR_VARIANTS[0]
        if (!openChannel(variant)) return
        answered = false
        status.setText(R.string.topway_wheel_status_idle)
        send(TopwayWheel.QUERY)
        send(TopwayWheel.QUERY_IMPEDANCE)
        main.postDelayed(noAnswer, ANSWER_TIMEOUT_MS)
    }

    override fun onPause() {
        resumed = false
        // The stock app ends learning when it leaves the screen; a unit left learning would take the
        // next button pressed while driving as a new mapping. Sent whatever the screen believes,
        // since on a unit whose answers never arrive the screen cannot know.
        send(TopwayWheel.END_LEARNING)
        main.removeCallbacksAndMessages(null)
        closeChannel()
        probing = false
        learning = false
        waitingFor = null
        super.onPause()
    }

    /** Opens the learning channel with one TWUtil constructor. Main thread. */
    private fun openChannel(ctorVariant: Int): Boolean {
        closeChannel()
        val ch = TwUtilChannel("learn", TopwayWheel.CHANNEL_IDS, TwUtilChannel.ctorArgOf(ctorVariant))
        val error = ch.open(this) { what, arg1, arg2, obj ->
            val reply = TopwayWheel.decode(what, arg1, arg2, obj)
            AppLog.i("TopwayWheel: 0x%X arg1=%d arg2=0x%X -> %s".format(what, arg1, arg2, reply))
            noteMessage(what, arg1, arg2)
            main.post { apply(reply) }
        }
        if (error != null) {
            status.text = getString(R.string.topway_wheel_status_unavailable, error)
            setControlsEnabled(false)
            return false
        }
        channel = ch
        variant = ctorVariant
        setControlsEnabled(true)
        showDiag()
        return true
    }

    private fun closeChannel() {
        channel?.close()
        channel = null
    }

    private fun noteMessage(what: Int, arg1: Int, arg2: Int) {
        messagesSeen.incrementAndGet()
        lastRaw = "0x%X/%d/0x%X".format(what, arg1, arg2)
        main.post { showDiag() }
    }

    private fun showDiag() {
        diag.text = getString(
            R.string.topway_wheel_diag, TwUtilChannel.describeCtor(variant), messagesSeen.get(),
            lastRaw.ifEmpty { "-" }
        )
    }

    /**
     * Tries each constructor TopWay's own apps use, with the harmless state query, and keeps the
     * first one the MCU answers on. Each try is opened on the main thread, like the real channel.
     */
    private fun startProbe() {
        probing = true
        closeChannel()
        probeStep(0, mutableListOf())
    }

    private fun probeStep(index: Int, results: MutableList<String>) {
        if (!resumed) return
        val variants = TwUtilChannel.CTOR_VARIANTS
        if (index >= variants.size) {
            probing = false
            // Sending still works on these units, so keep a channel open for the buttons.
            openChannel(TwUtilChannel.workingCtor(this) ?: variants[0])
            status.setText(R.string.topway_wheel_status_no_answer)
            diag.text = getString(R.string.topway_wheel_probe_none, results.joinToString("; "))
            return
        }
        val v = variants[index]
        val count = AtomicInteger(0)
        val probe = TwUtilChannel("probe", TopwayWheel.CHANNEL_IDS, TwUtilChannel.ctorArgOf(v))
        val error = probe.open(this) { what, arg1, arg2, _ ->
            count.incrementAndGet()
            AppLog.i("TopwayWheel probe ${TwUtilChannel.describeCtor(v)}: 0x%X arg1=%d arg2=0x%X".format(what, arg1, arg2))
        }
        if (error != null) {
            results += "${TwUtilChannel.describeCtor(v)}: $error"
            probeStep(index + 1, results)
            return
        }
        diag.text = getString(R.string.topway_wheel_probe_trying, TwUtilChannel.describeCtor(v))
        probe.write(*TopwayWheel.QUERY)
        probe.write(*TopwayWheel.QUERY_IMPEDANCE)
        main.postDelayed({
            probe.close()
            val n = count.get()
            results += "${TwUtilChannel.describeCtor(v)}: $n"
            AppLog.i("TopwayWheel probe: ${TwUtilChannel.describeCtor(v)} received $n messages")
            if (n > 0 && resumed) {
                probing = false
                TwUtilChannel.rememberWorkingCtor(this, v)
                if (openChannel(v)) {
                    status.text = getString(R.string.topway_wheel_probe_found, TwUtilChannel.describeCtor(v))
                    send(TopwayWheel.QUERY)
                    send(TopwayWheel.QUERY_IMPEDANCE)
                }
            } else {
                probeStep(index + 1, results)
            }
        }, PROBE_WAIT_MS)
    }

    private fun apply(reply: TopwayWheel.Reply) {
        if (reply !is TopwayWheel.Reply.Other) answered = true
        when (reply) {
            is TopwayWheel.Reply.LearningState -> {
                learning = reply.learning
                if (!learning) waitingFor = null
                status.setText(if (learning) R.string.topway_wheel_status_learning else R.string.topway_wheel_status_idle)
            }
            is TopwayWheel.Reply.Learned -> {
                learned.add(reply.functionId)
                val done = waitingFor
                if (done != null && done.id == reply.functionId) {
                    waitingFor = null
                    status.text = getString(R.string.topway_wheel_status_learned, done.label)
                }
            }
            is TopwayWheel.Reply.ReadQuality -> {
                if (reply.warning > 0) status.text = getString(R.string.topway_wheel_quality, reply.warning)
            }
            is TopwayWheel.Reply.ImpedanceState -> impedance = reply.level
            TopwayWheel.Reply.Other -> return
        }
        refresh()
    }

    private fun onFunctionTapped(function: TopwayWheel.Function) {
        if (!learning) {
            ToastUtils.showToast(this, R.string.topway_wheel_start_first, Toast.LENGTH_SHORT, force = true)
            return
        }
        waitingFor = function
        send(TopwayWheel.learn(function.id))
        status.text = getString(R.string.topway_wheel_status_waiting, function.label)
        refresh()
    }

    private fun setImpedance(level: TopwayWheel.Impedance) {
        send(TopwayWheel.setImpedance(level))
        send(TopwayWheel.QUERY_IMPEDANCE)
    }

    private fun send(command: IntArray) {
        AppLog.i("TopwayWheel: write(${command.joinToString { "0x%X".format(it) }})")
        channel?.write(*command)
    }

    private fun refresh() {
        toggle.setText(if (learning) R.string.topway_wheel_end else R.string.topway_wheel_start)
        markSelected(low, impedance == TopwayWheel.Impedance.LOW)
        markSelected(high, impedance == TopwayWheel.Impedance.HIGH)
        adapter.notifyDataSetChanged()
    }

    private fun markSelected(button: MaterialButton, selected: Boolean) {
        button.alpha = if (selected) 1f else 0.6f
        button.strokeWidth = if (selected) 6 else 2
    }

    private fun setControlsEnabled(enabled: Boolean) {
        toggle.isEnabled = enabled
        low.isEnabled = enabled
        high.isEnabled = enabled
        clear.isEnabled = enabled
    }

    private inner class FunctionAdapter : RecyclerView.Adapter<FunctionAdapter.Row>() {

        inner class Row(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.key_name)
            val value: TextView = view.findViewById(R.id.key_value)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(LayoutInflater.from(parent.context).inflate(R.layout.item_keymap, parent, false))

        override fun onBindViewHolder(holder: Row, position: Int) {
            val function = TopwayWheel.FUNCTIONS[position]
            holder.name.text = function.label
            val (text, color) = when {
                waitingFor?.id == function.id -> getString(R.string.topway_wheel_press_now) to R.color.brand_teal
                function.id in learned -> getString(R.string.topway_wheel_learned) to R.color.brand_teal
                else -> getString(R.string.topway_wheel_not_learned) to android.R.color.darker_gray
            }
            holder.value.text = text
            holder.value.setTextColor(ContextCompat.getColor(this@TopwayWheelActivity, color))
            holder.itemView.setOnClickListener { onFunctionTapped(function) }
        }

        override fun getItemCount() = TopwayWheel.FUNCTIONS.size
    }
}
