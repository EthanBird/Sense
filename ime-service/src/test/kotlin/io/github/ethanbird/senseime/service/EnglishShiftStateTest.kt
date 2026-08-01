package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishShiftStateTest {
    @Test
    fun `shift cycles from one-shot to caps lock and back to lowercase`() {
        val oneShot = EnglishShiftState.LOWERCASE.onShiftPressed()
        val capsLock = oneShot.onShiftPressed()
        val lowercase = capsLock.onShiftPressed()

        assertEquals(EnglishShiftState.ONE_SHOT, oneShot)
        assertTrue(oneShot.uppercase)
        assertFalse(oneShot.capsLocked)
        assertEquals(EnglishShiftState.CAPS_LOCK, capsLock)
        assertTrue(capsLock.uppercase)
        assertTrue(capsLock.capsLocked)
        assertEquals(EnglishShiftState.LOWERCASE, lowercase)
        assertFalse(lowercase.uppercase)
    }

    @Test
    fun `only one-shot shift is consumed by an accepted letter`() {
        assertEquals(
            EnglishShiftState.LOWERCASE,
            EnglishShiftState.ONE_SHOT.afterAcceptedLetter(),
        )
        assertEquals(
            EnglishShiftState.CAPS_LOCK,
            EnglishShiftState.CAPS_LOCK.afterAcceptedLetter(),
        )
        assertEquals(
            EnglishShiftState.LOWERCASE,
            EnglishShiftState.LOWERCASE.afterAcceptedLetter(),
        )
    }

    @Test
    fun `one-shot and caps lock both project uppercase letters`() {
        assertEquals('a', EnglishShiftState.LOWERCASE.applyTo('a'))
        assertEquals('A', EnglishShiftState.ONE_SHOT.applyTo('a'))
        assertEquals('A', EnglishShiftState.CAPS_LOCK.applyTo('a'))
    }
}
