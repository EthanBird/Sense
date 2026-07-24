package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCodesTest {
    @Test
    fun voiceControlCodesArePrivateNegativeAndDistinct() {
        val voiceCodes = listOf(
            KeyCodes.VOICE,
            KeyCodes.VOICE_DONE,
            KeyCodes.VOICE_CANCEL,
            KeyCodes.VOICE_RETRY,
        )

        assertTrue(voiceCodes.all { it < 0 })
        assertEquals(voiceCodes.size, voiceCodes.distinct().size)
    }
}
