package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVolumeKeyPolicyTest {

    @Test
    fun `a button bound to a volume action changes the local volume`() {
        val wheelVolUp = TopwayKey.toKeyCode(0x01)
        val wheelVolDown = TopwayKey.toKeyCode(0x02)
        val wheelMute = TopwayKey.toKeyCode(0x03)
        assertEquals(LocalVolumeKeyPolicy.Action.RAISE, LocalVolumeKeyPolicy.actionFor(wheelVolUp, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(LocalVolumeKeyPolicy.Action.LOWER, LocalVolumeKeyPolicy.actionFor(wheelVolDown, KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(LocalVolumeKeyPolicy.Action.TOGGLE_MUTE, LocalVolumeKeyPolicy.actionFor(wheelMute, KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test
    fun `an unmapped volume code keeps its old path`() {
        assertNull(LocalVolumeKeyPolicy.actionFor(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `other bindings go to Android Auto`() {
        assertNull(LocalVolumeKeyPolicy.actionFor(TopwayKey.toKeyCode(0x18), KeyEvent.KEYCODE_CALL))
        assertNull(LocalVolumeKeyPolicy.actionFor(TopwayKey.toKeyCode(0x13), KeyEvent.KEYCODE_MEDIA_NEXT))
    }
}
