package com.andrerinas.openheadunit.main.bento

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/** Small pieces every bento screen shares. */
object BentoUi {

    /** A slight press-in under a finger and a lift under a D-pad or rotary knob, the way a TV home answers. */
    @SuppressLint("ClickableViewAccessibility")
    fun addPressFeedback(view: View, focusScale: Float = 1.04f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                // A view that is not pressable right now (the Now Playing card on Bluetooth) stays still.
                MotionEvent.ACTION_DOWN -> if (v.isClickable) v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) focusScale else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        }
    }
}
