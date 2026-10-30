package com.andrerinas.openheadunit.connection.carkey.topway

import android.view.KeyEvent
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth.CallKeyRoute
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth.CallState
import com.andrerinas.openheadunit.input.TopwayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopwayBluetoothTest {

    private val wheelAnswer = TopwayKey.toKeyCode(0x18)
    private val wheelHangUp = TopwayKey.toKeyCode(0x19)
    private val otherButton = TopwayKey.toKeyCode(0x5A)

    @Test
    fun `call states follow com tw bt's field`() {
        assertEquals(CallState.IDLE, TopwayBluetooth.callStateOf(0))
        assertEquals(CallState.INCOMING, TopwayBluetooth.callStateOf(1))
        assertEquals(CallState.ACTIVE, TopwayBluetooth.callStateOf(2))
        assertEquals(CallState.OUTGOING, TopwayBluetooth.callStateOf(3))
        assertEquals(CallState.OUTGOING, TopwayBluetooth.callStateOf(4))
        assertEquals(CallState.IDLE, TopwayBluetooth.callStateOf(99))
    }

    @Test
    fun `bluetooth music is claimed once at the first start after a boot`() {
        val minute = 60_000L
        assertEquals(true, TopwayBluetooth.acquireMusicAtStart(minute, alreadyDone = false, projecting = false, radioPlaying = false))
        // Once only.
        assertEquals(false, TopwayBluetooth.acquireMusicAtStart(minute, alreadyDone = true, projecting = false, radioPlaying = false))
        // Never over a source the driver chose.
        assertEquals(false, TopwayBluetooth.acquireMusicAtStart(minute, alreadyDone = false, projecting = true, radioPlaying = false))
        assertEquals(false, TopwayBluetooth.acquireMusicAtStart(minute, alreadyDone = false, projecting = false, radioPlaying = true))
        // A restart of the app long after the boot is not the unit's first start.
        assertEquals(false, TopwayBluetooth.acquireMusicAtStart(TopwayBluetooth.START_ACQUIRE_WINDOW_MS, alreadyDone = false, projecting = false, radioPlaying = false))
    }

    @Test
    fun `link reports decode, unknown ones are ignored`() {
        assertEquals(true, TopwayBluetooth.linkConnectedOf(1))
        assertEquals(false, TopwayBluetooth.linkConnectedOf(2))
        assertNull(TopwayBluetooth.linkConnectedOf(0))
    }

    @Test
    fun `no call on the module leaves every key to Android Auto`() {
        for (logical in listOf(KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_MEDIA_NEXT)) {
            assertEquals(CallKeyRoute.ANDROID_AUTO,
                TopwayBluetooth.routeCallKey(otherButton, logical, CallState.IDLE, moduleConnected = true))
        }
    }

    @Test
    fun `a ringing call is answered or rejected on the module`() {
        assertEquals(CallKeyRoute.UNIT_ANSWER,
            TopwayBluetooth.routeCallKey(otherButton, KeyEvent.KEYCODE_CALL, CallState.INCOMING, true))
        assertEquals(CallKeyRoute.UNIT_END,
            TopwayBluetooth.routeCallKey(otherButton, KeyEvent.KEYCODE_ENDCALL, CallState.INCOMING, true))
    }

    @Test
    fun `an active or outgoing call is ended on the module, answer goes on to Android Auto`() {
        for (state in listOf(CallState.ACTIVE, CallState.OUTGOING)) {
            assertEquals(CallKeyRoute.UNIT_END,
                TopwayBluetooth.routeCallKey(otherButton, KeyEvent.KEYCODE_ENDCALL, state, true))
            assertEquals(CallKeyRoute.ANDROID_AUTO,
                TopwayBluetooth.routeCallKey(otherButton, KeyEvent.KEYCODE_CALL, state, true))
        }
    }

    @Test
    fun `the wheel's call keys are left to the unit only for a call on its own module`() {
        assertEquals(CallKeyRoute.ALREADY_HANDLED,
            TopwayBluetooth.routeCallKey(wheelAnswer, KeyEvent.KEYCODE_CALL, CallState.INCOMING, true))
        assertEquals(CallKeyRoute.ALREADY_HANDLED,
            TopwayBluetooth.routeCallKey(wheelHangUp, KeyEvent.KEYCODE_ENDCALL, CallState.ACTIVE, true))
    }

    @Test
    fun `the wheel's call keys reach Android Auto when the call is not on the module`() {
        // The unit then only opens its phone screen; without this the call can't be answered.
        assertEquals(CallKeyRoute.ANDROID_AUTO,
            TopwayBluetooth.routeCallKey(wheelAnswer, KeyEvent.KEYCODE_CALL, CallState.IDLE, false))
        assertEquals(CallKeyRoute.ANDROID_AUTO,
            TopwayBluetooth.routeCallKey(wheelHangUp, KeyEvent.KEYCODE_ENDCALL, CallState.IDLE, true))
        assertEquals(CallKeyRoute.ANDROID_AUTO,
            TopwayBluetooth.routeCallKey(wheelAnswer, KeyEvent.KEYCODE_CALL, CallState.INCOMING, false))
    }

    @Test
    fun `a wheel call key explains a cover for four seconds`() {
        assertEquals(false, TopwayBluetooth.wheelCallKeyRecent(nowMs = 10_000L, lastAtMs = 0L))
        assertEquals(true, TopwayBluetooth.wheelCallKeyRecent(nowMs = 10_000L, lastAtMs = 9_500L))
        assertEquals(true, TopwayBluetooth.wheelCallKeyRecent(nowMs = 13_999L, lastAtMs = 10_000L))
        assertEquals(false, TopwayBluetooth.wheelCallKeyRecent(nowMs = 14_000L, lastAtMs = 10_000L))
    }

    @Test
    fun `a non-wheel button mapped to answer still routes through the module during a call`() {
        assertEquals(CallKeyRoute.UNIT_ANSWER,
            TopwayBluetooth.routeCallKey(otherButton, KeyEvent.KEYCODE_CALL, CallState.INCOMING, false))
    }

    @Test
    fun `non-call keys are never touched`() {
        assertEquals(CallKeyRoute.ANDROID_AUTO,
            TopwayBluetooth.routeCallKey(wheelAnswer, KeyEvent.KEYCODE_MEDIA_NEXT, CallState.INCOMING, true))
    }
}
