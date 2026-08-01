package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeCharacterMapTest {
    @Test
    fun everyLetterHasAnUpSwipeCharacter() {
        assertEquals(26, SwipeCharacterMap.size)
        ('a'..'z').forEach { letter ->
            checkNotNull(SwipeCharacterMap.forKey(letter.code)) { "missing mapping for $letter" }
        }
    }

    @Test
    fun requiredMappingsMatchTheKeyboardHints() {
        assertEquals("1", SwipeCharacterMap.forKey('q'.code))
        assertEquals("2", SwipeCharacterMap.forKey('w'.code))
        assertEquals("、", SwipeCharacterMap.forKey('m'.code))
        assertEquals("、", SwipeCharacterMap.forKey('M'.code))
        assertEquals("！", SwipeCharacterMap.forKey('s'.code))
        assertEquals("？", SwipeCharacterMap.forKey('l'.code))
        assertEquals(
            "!",
            SwipeCharacterMap.forKey('s'.code, SwipeCharacterMode.ENGLISH),
        )
        assertEquals(
            "?",
            SwipeCharacterMap.forKey('l'.code, SwipeCharacterMode.ENGLISH),
        )
        assertEquals(
            "/",
            SwipeCharacterMap.forKey('m'.code, SwipeCharacterMode.ENGLISH),
        )
        assertEquals(
            ":",
            SwipeCharacterMap.forKey('b'.code, SwipeCharacterMode.ENGLISH),
        )
        assertEquals(
            ";",
            SwipeCharacterMap.forKey('n'.code, SwipeCharacterMode.ENGLISH),
        )
        assertNull(SwipeCharacterMap.forKey(KeyCodes.DELETE))
    }

    @Test
    fun englishSwipeCharactersAreAlwaysAscii() {
        ('a'..'z').forEach { letter ->
            val value = checkNotNull(
                SwipeCharacterMap.forKey(letter.code, SwipeCharacterMode.ENGLISH),
            )
            check(value.all { it.code in 0x20..0x7e }) {
                "English mapping for $letter contains non-ASCII punctuation: $value"
            }
        }
    }
}
