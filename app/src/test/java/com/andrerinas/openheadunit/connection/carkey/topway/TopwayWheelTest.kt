package com.andrerinas.openheadunit.connection.carkey.topway

import android.view.KeyEvent
import com.andrerinas.openheadunit.connection.carkey.topway.TopwayWheel.Reply
import com.andrerinas.openheadunit.input.TopwayKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopwayWheelTest {

    @Test
    fun `commands match the stock learning app`() {
        assertArrayEquals(intArrayOf(0x103, 0xFF), TopwayWheel.QUERY)
        assertArrayEquals(intArrayOf(0x103, 0, 0), TopwayWheel.START_LEARNING)
        assertArrayEquals(intArrayOf(0x103, 0, 2), TopwayWheel.END_LEARNING)
        assertArrayEquals(intArrayOf(0x103, 2), TopwayWheel.CLEAR_ALL)
        assertArrayEquals(intArrayOf(0x103, 1, 0x13), TopwayWheel.learn(0x13))
        assertArrayEquals(intArrayOf(0x10B, 0xFF), TopwayWheel.QUERY_IMPEDANCE)
        assertArrayEquals(intArrayOf(0x10B, 9, 1), TopwayWheel.setImpedance(TopwayWheel.Impedance.LOW))
        assertArrayEquals(intArrayOf(0x10B, 9, 2), TopwayWheel.setImpedance(TopwayWheel.Impedance.HIGH))
    }

    @Test
    fun `learning state - zero means learning`() {
        assertEquals(Reply.LearningState(true), TopwayWheel.decode(0x103, 0, 0, null))
        assertEquals(Reply.LearningState(false), TopwayWheel.decode(0x103, 0, 2, null))
    }

    @Test
    fun `learned entries carry the function, a second function and flags`() {
        assertEquals(Reply.Learned(0x13, 0, 0), TopwayWheel.decode(0x103, 5, 0x13, null))
        assertEquals(Reply.Learned(0x18, 0x5A, 0x03), TopwayWheel.decode(0x103, 1, 0x035A18, null))
        assertEquals(Reply.Other, TopwayWheel.decode(0x103, 1, 0, null))
    }

    @Test
    fun `read quality warnings and long-press settings`() {
        assertEquals(Reply.ReadQuality(2), TopwayWheel.decode(0x103, 130, 2, null))
        assertEquals(Reply.ReadQuality(0), TopwayWheel.decode(0x103, 130, 0, null))
        assertEquals(Reply.Other, TopwayWheel.decode(0x103, 144, 0x0100, null))
    }

    @Test
    fun `impedance comes from byte 9`() {
        val frame = ByteArray(12).also { it[9] = 2 }
        assertEquals(Reply.ImpedanceState(TopwayWheel.Impedance.HIGH), TopwayWheel.decode(0x10B, 0, 0, frame))
        assertEquals(Reply.Other, TopwayWheel.decode(0x10B, 0, 0, ByteArray(4)))
        assertEquals(Reply.Other, TopwayWheel.decode(0x10B, 0, 0, null))
    }

    @Test
    fun `learned buttons have an Android Auto action by default`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, TopwayWheel.defaultKeyCodeFor(TopwayKey.toKeyCode(0x13)))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, TopwayWheel.defaultKeyCodeFor(TopwayKey.toKeyCode(0x15)))
        assertEquals(KeyEvent.KEYCODE_CALL, TopwayWheel.defaultKeyCodeFor(TopwayKey.toKeyCode(0x18)))
        assertEquals(KeyEvent.KEYCODE_ENDCALL, TopwayWheel.defaultKeyCodeFor(TopwayKey.toKeyCode(0x19)))
        assertEquals(KeyEvent.KEYCODE_SEARCH, TopwayWheel.defaultKeyCodeFor(TopwayKey.toKeyCode(0x5A)))
    }

    @Test
    fun `buttons the unit acts on itself get no second action`() {
        for (id in listOf(0x01, 0x02, 0x03, 0x10, 0x25)) {
            assertNull("0x%02X".format(id), TopwayWheel.defaultKeyCodeFor(TopwayKey.toKeyCode(id)))
        }
        assertNull(TopwayWheel.defaultKeyCodeFor(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun `every offered function is a known TopWay id`() {
        assertEquals(TopwayWheel.FUNCTIONS.size, TopwayWheel.FUNCTIONS.map { it.id }.toSet().size)
        for (f in TopwayWheel.FUNCTIONS) assertTrue(f.label, TopwayKey.nameOf(f.id) != null)
    }
}
