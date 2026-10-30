package com.andrerinas.openheadunit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopwayKeyTest {

    @Test
    fun `key message yields the id in the low byte of arg2`() {
        assertEquals(0x13, TopwayKey.keyId(TopwayKey.MSG_KEY, 0x13))
        assertEquals(0x18, TopwayKey.keyId(TopwayKey.MSG_KEY, 0x0118))
    }

    @Test
    fun `other message ids and id zero are not keys`() {
        assertEquals(TopwayKey.NO_KEY, TopwayKey.keyId(0x103, 0x13))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.keyId(TopwayKey.MSG_KEY, 0))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.keyId(TopwayKey.MSG_KEY, 0x100))
    }

    @Test
    fun `virtual codes round-trip and stay clear of the BYD range`() {
        for (id in 1..0xFF) {
            val code = TopwayKey.toKeyCode(id)
            assertEquals(id, TopwayKey.keyIdOf(code))
            assertNull("must not collide with BYD", BydPanelKey.panelCodeOf(code))
            assertTrue("must be proprietary so unmapped keys are dropped", code >= 1000)
        }
        assertNull(TopwayKey.keyIdOf(BydPanelKey.toKeyCode(0x13)))
        assertNull(TopwayKey.keyIdOf(87))
    }

    @Test
    fun `ids match the stock app's key table`() {
        assertEquals("Vol+", TopwayKey.nameOf(0x01))
        assertEquals("Vol-", TopwayKey.nameOf(0x02))
        assertEquals("Next", TopwayKey.nameOf(0x13))
        assertEquals("Prev", TopwayKey.nameOf(0x15))
        assertEquals("Answer", TopwayKey.nameOf(0x18))
        assertEquals("HangUp", TopwayKey.nameOf(0x19))
        assertEquals("Voice", TopwayKey.nameOf(0x5A))
    }

    @Test
    fun `labels name the button and its hex, and only for our codes`() {
        assertEquals("TopWay Answer (0x18)", TopwayKey.label(TopwayKey.toKeyCode(0x18)))
        assertEquals("TopWay key (0xEE)", TopwayKey.label(TopwayKey.toKeyCode(0xEE)))
        assertNull(TopwayKey.label(87))
    }

    @Test
    fun `describe keeps every raw field`() {
        assertEquals(
            "what=0x201 arg1=1 (0x1) arg2=19 (0x13) obj=[0A FF]",
            TopwayKey.describe(0x201, 1, 0x13, byteArrayOf(0x0A, 0xFF.toByte()))
        )
        assertEquals("what=0x201 arg1=0 (0x0) arg2=2 (0x2)", TopwayKey.describe(0x201, 0, 2, null))
    }
}
