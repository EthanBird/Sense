package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class T9CompositionTest {
    @Test
    fun typingKeepsRawDigitsAndAdvancesTheRevision() {
        val composition = T9Composition()
            .typeDigit('4')
            .typeDigit('8')
            .typeDigit('6')

        assertEquals("486", composition.rawDigits)
        assertEquals(3L, composition.revision)
        assertEquals(emptySet<Int>(), composition.forcedJoints)
        assertEquals(emptyList<T9LockedEdge>(), composition.lockedEdges)
    }

    @Test
    fun separatorCreatesOneForcedJointWithoutChangingRawDigits() {
        val composition = T9Composition()
            .typeDigit('4')
            .typeDigit('8')
            .forceJoint()
            .forceJoint()
            .typeDigit('6')

        assertEquals("486", composition.rawDigits)
        assertEquals(setOf(2), composition.forcedJoints)
        assertEquals(4L, composition.revision)
    }

    @Test
    fun backspaceUnlocksSelectedSpellingsBeforeDeletingDigits() {
        val selected = "4867436".fold(T9Composition()) { state, digit ->
            state.typeDigit(digit)
        }
            .lockEdge(T9LockedEdge(0, 3, "hun"))
            .lockEdge(T9LockedEdge(3, 7, "shen"))

        val firstBackspace = selected.backspace()
        val secondBackspace = firstBackspace.backspace()
        val thirdBackspace = secondBackspace.backspace()

        assertEquals("4867436", firstBackspace.rawDigits)
        assertEquals(listOf(T9LockedEdge(0, 3, "hun")), firstBackspace.lockedEdges)
        assertEquals("4867436", secondBackspace.rawDigits)
        assertEquals(emptyList<T9LockedEdge>(), secondBackspace.lockedEdges)
        assertEquals("486743", thirdBackspace.rawDigits)
        assertEquals(selected.revision + 3, thirdBackspace.revision)
    }

    @Test
    fun backspaceRemovesATrailingSeparatorBeforeItsPreviousDigit() {
        val separated = T9Composition()
            .typeDigit('4')
            .typeDigit('8')
            .forceJoint()

        val withoutSeparator = separated.backspace()
        val withoutDigit = withoutSeparator.backspace()

        assertEquals("48", withoutSeparator.rawDigits)
        assertEquals(emptySet<Int>(), withoutSeparator.forcedJoints)
        assertEquals("4", withoutDigit.rawDigits)
    }

    @Test
    fun backspaceRemovesADigitTypedAfterAnEarlierSpellingLock() {
        val selectedThenTyped = "486".fold(T9Composition()) { state, digit ->
            state.typeDigit(digit)
        }
            .lockEdge(T9LockedEdge(0, 3, "hun"))
            .typeDigit('7')

        val withoutLatestDigit = selectedThenTyped.backspace()
        val withoutEarlierSelection = withoutLatestDigit.backspace()

        assertEquals("486", withoutLatestDigit.rawDigits)
        assertEquals(listOf(T9LockedEdge(0, 3, "hun")), withoutLatestDigit.lockedEdges)
        assertEquals("486", withoutEarlierSelection.rawDigits)
        assertEquals(emptyList<T9LockedEdge>(), withoutEarlierSelection.lockedEdges)
    }

    @Test
    fun composingLengthIsBoundedAndRevisionWrapsWithoutOverflow() {
        val maximum = "2".repeat(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH)
            .fold(T9Composition()) { state, digit -> state.typeDigit(digit) }

        assertSame(maximum, maximum.typeDigit('2'))
        assertEquals(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH, maximum.rawDigits.length)

        val wrapped = T9Composition(revision = Long.MAX_VALUE).typeDigit('2')
        assertEquals(1L, wrapped.revision)
    }
}
