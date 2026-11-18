package com.andrerinas.openheadunit.carplay

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.theme.UiTheme
import com.andrerinas.openheadunit.main.synthwave.ScopeDrawable
import com.andrerinas.openheadunit.main.synthwave.Synthwave
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.shilapi.xcertplay.OpenHeadunitBridge

/**
 * The home's Apple CarPlay card (view_sw_carplay_card), the Android Auto card's twin. The whole card
 * is the button; the status reads under the logo:
 *  - no CarPlay: "Tap to connect over USB" (a tap opens CarPlay for a plugged-in iPhone, else says
 *    "No device found");
 *  - connecting: "Connecting to iPhone…" (a tap shows the CarPlay screen);
 *  - running: a cyan edge, the logo pulsing, "iPhone · Connected · tap to open".
 */
object CarPlayHomeCard {

    enum class State { IDLE, CONNECTING, RUNNING }

    fun state(): State = when {
        CarPlaySession.active.value || OpenHeadunitBridge.sessionActive() -> State.RUNNING
        OpenHeadunitBridge.screenOpen() -> State.CONNECTING
        else -> State.IDLE
    }

    fun bind(root: View, onTap: () -> Unit) {
        root.findViewById<View>(R.id.carplay_card)?.setOnClickListener { onTap() }
    }

    fun paint(context: Context, root: View) {
        val card = root.findViewById<View>(R.id.carplay_card) ?: return
        val scope = root.findViewById<View>(R.id.carplay_scope)
        if (scope != null && scope.background !is ScopeDrawable) Synthwave.scope(scope)
        val state = state()
        val live = state == State.RUNNING
        card.background = UiTheme.drawable(card.context, if (live) R.attr.uiCardLiveBg else R.attr.uiCardBg)
        root.findViewById<ImageView>(R.id.carplay_logo)?.setColorFilter(
            UiTheme.color(context, if (live) R.attr.uiLive else R.attr.uiIconIdle)
        )
        HomeUiHelper.setPulse(root.findViewById(R.id.carplay_pulse), live)

        val title = root.findViewById<TextView>(R.id.carplay_title)
        val subtitle = root.findViewById<TextView>(R.id.carplay_subtitle)
        val ice = UiTheme.color(context, R.attr.uiTextIce)
        val cyan = UiTheme.color(context, R.attr.uiLive)
        when (state) {
            State.IDLE -> {
                // The tile is the button: nothing under the logo until an iPhone connects.
                title?.visibility = View.GONE
            }
            State.CONNECTING -> {
                title?.visibility = View.VISIBLE
                title?.setText(R.string.sw_carplay_connecting)
                subtitle?.setText(R.string.sw_carplay_open)
                subtitle?.setTextColor(ice)
            }
            State.RUNNING -> {
                title?.visibility = View.VISIBLE
                title?.setText(R.string.carplay_phone)
                subtitle?.setText(R.string.sw_connected_tap)
                subtitle?.setTextColor(cyan)
            }
        }
        subtitle?.visibility = if (state == State.IDLE) View.GONE else View.VISIBLE
    }
}
