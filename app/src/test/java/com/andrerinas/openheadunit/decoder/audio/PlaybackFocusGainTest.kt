package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFocusGainTest {

    @Test
    fun `TopWay units take permanent focus so the unit hands its source and wheel keys to Android`() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, PlaybackFocusPolicy.playbackFocusGain(topwayUnit = true))
    }

    @Test
    fun `every other unit keeps transient focus so the radio resumes afterwards`() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, PlaybackFocusPolicy.playbackFocusGain(topwayUnit = false))
    }
}
