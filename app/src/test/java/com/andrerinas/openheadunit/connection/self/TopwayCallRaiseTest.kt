package com.andrerinas.openheadunit.connection.self

import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayBluetooth.CallState
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.CoverVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopwayCallRaiseTest {

    private fun verdict(selfMode: Boolean, topway: Boolean, userLeft: Boolean = false, enabled: Boolean = true) =
        SelfModeCallRaisePolicy.coverVerdict(
            userLeft = userLeft, selfMode = selfMode, pipActive = false,
            enabled = enabled, episodeOpen = false, topwayCallScreen = topway,
        )

    @Test
    fun `a TopWay unit watches covers for a call outside Self Mode`() {
        assertEquals(CoverVerdict.OPEN, verdict(selfMode = false, topway = true))
    }

    @Test
    fun `other units outside Self Mode still leave covers alone`() {
        assertEquals(CoverVerdict.NOT_SELF_MODE, verdict(selfMode = false, topway = false))
    }

    @Test
    fun `leaving deliberately and the setting still win on TopWay`() {
        assertEquals(CoverVerdict.USER_LEFT, verdict(selfMode = false, topway = true, userLeft = true))
        assertEquals(CoverVerdict.DISABLED, verdict(selfMode = false, topway = true, enabled = false))
    }

    @Test
    fun `a TopWay call raises the projection once its screen has settled`() {
        val episode = SelfModeCallRaisePolicy.Episode(startedAtMs = 0L)
        val observed = SelfModeCallRaisePolicy.observe(episode, 100L, callActive = true)
        assertEquals(SelfModeCallRaisePolicy.Action.WAIT,
            SelfModeCallRaisePolicy.decide(100L, observed, callActive = true, isForeground = false, pipActive = false))
        assertEquals(SelfModeCallRaisePolicy.Action.RAISE,
            SelfModeCallRaisePolicy.decide(SelfModeCallRaisePolicy.FIRST_ATTEMPT_DELAY_MS, observed,
                callActive = true, isForeground = false, pipActive = false))
    }

    @Test
    fun `TopWay timing raises at 200ms instead of 600ms`() {
        val timing = SelfModeCallRaisePolicy.Timing.TOPWAY
        val episode = SelfModeCallRaisePolicy.observe(SelfModeCallRaisePolicy.Episode(startedAtMs = 0L), 100L, true)
        assertEquals(SelfModeCallRaisePolicy.Action.WAIT,
            SelfModeCallRaisePolicy.decide(199L, episode, true, isForeground = false, pipActive = false, timing = timing))
        assertEquals(SelfModeCallRaisePolicy.Action.RAISE,
            SelfModeCallRaisePolicy.decide(200L, episode, true, isForeground = false, pipActive = false, timing = timing))
        assertEquals(100L, SelfModeCallRaisePolicy.nextTickDelayMs(episode, timing))
    }

    @Test
    fun `assistant call broadcasts map to call states`() {
        assertEquals(CallState.INCOMING, TopwayBluetooth.callStateOfAction("action.bt.AIOS_INCOMING_RINGING"))
        assertEquals(CallState.ACTIVE, TopwayBluetooth.callStateOfAction("action.bt.AIOS_INCOMING_OFFHOOK"))
        assertEquals(CallState.OUTGOING, TopwayBluetooth.callStateOfAction("action.bt.AIOS_OUTGOING_RINGING"))
        assertEquals(CallState.IDLE, TopwayBluetooth.callStateOfAction("action.bt.AIOS_INCOMING_IDLE"))
        assertNull(TopwayBluetooth.callStateOfAction("android.intent.action.MEDIA_BUTTON"))
        assertNull(TopwayBluetooth.callStateOfAction(null))
    }
}
