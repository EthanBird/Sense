package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InputReducerTest {
    private val reducer = InputReducer()

    @Test
    fun typingBuildsLowercaseCompositionAndRevision() {
        val first = reducer.reduce(InputState(), InputAction.Type('N'))
        val second = reducer.reduce(first, InputAction.Type('i'))

        assertEquals("ni", second.composing)
        assertEquals(2L, second.revision)
    }

    @Test
    fun backspaceRemovesOneCharacter() {
        val result = reducer.reduce(InputState(composing = "ni", revision = 2), InputAction.Backspace)

        assertEquals("n", result.composing)
        assertEquals(3L, result.revision)
    }

    @Test
    fun backspaceOnEmptyStateDoesNotCreateRevision() {
        val state = InputState(revision = 9)

        assertSame(state, reducer.reduce(state, InputAction.Backspace))
    }

    @Test
    fun commitClearsCompositionAndKeepsAuditValue() {
        val state = InputState(composing = "nihao", revision = 6)
        val result = reducer.reduce(state, InputAction.Commit("你好"))

        assertEquals("", result.composing)
        assertEquals(listOf("你好"), result.committed)
        assertEquals(7L, result.revision)
    }

    @Test
    fun resetClearsSessionStateButKeepsMonotonicRevision() {
        val state = InputState(composing = "sense", revision = 5, committed = listOf("先思"))
        val result = reducer.reduce(state, InputAction.Reset)

        assertEquals(InputState(revision = 6), result)
    }
}

