package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDraftBufferTest {
    @Test
    fun projectsCompositionAtTheDraftCursor() {
        val buffer = AgentDraftBuffer()
        buffer.insert("你好世界")
        buffer.moveCursor(-2)

        assertEquals("你好pinyin世界", buffer.displayText("pinyin"))
        assertEquals("你好", buffer.contextBeforeCursor(8))
    }

    @Test
    fun deletesOneUnicodeCodePointAndSupportsUndoRedo() {
        val buffer = AgentDraftBuffer()
        buffer.insert("A😀B")
        buffer.moveCursor(-1)

        assertTrue(buffer.deleteBackward())
        assertEquals("AB", buffer.text)
        assertTrue(buffer.undo())
        assertEquals("A😀B", buffer.text)
        assertTrue(buffer.redo())
        assertEquals("AB", buffer.text)
    }

    @Test
    fun enforcesTheDraftLimitWithoutCorruptingTheCursor() {
        val buffer = AgentDraftBuffer(maxChars = 4)

        assertTrue(buffer.insert("abc"))
        assertFalse(buffer.insert("de"))
        assertEquals("abc", buffer.text)
        assertEquals(3, buffer.cursor)
    }
}
