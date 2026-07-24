package io.github.ethanbird.senseime.service.ai.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContextWindowPolicyTest {
    @Test
    fun collapsedCursorAuthorizesOnlyItsCurrentParagraphAndKeepsAbsoluteSelection() {
        val text = "上一段\n当前段落\n下一段"
        val cursor = text.indexOf("段落") + 1
        val original = context(
            text = text,
            startOffset = 100,
            selectionStart = cursor,
            selectionEnd = cursor,
        )

        val constrained = EditorContextWindowPolicy.constrain(original)

        assertEquals("当前段落", constrained.text)
        assertEquals(104, constrained.startOffset)
        assertEquals(
            original.startOffset + cursor,
            constrained.startOffset + constrained.selectionStartInText,
        )
        assertEquals(
            constrained.selectionStartInText,
            constrained.selectionEndInText,
        )
    }

    @Test
    fun realSelectionAndCompleteDocumentRemainUnchanged() {
        val selected = context(
            text = "before\nselected\nafter",
            startOffset = 20,
            selectionStart = 7,
            selectionEnd = 15,
        )
        val complete = selected.copy(
            selectionEndInText = selected.selectionStartInText,
            completeDocument = true,
        )

        assertSame(selected, EditorContextWindowPolicy.constrain(selected))
        assertSame(complete, EditorContextWindowPolicy.constrain(complete))
    }

    @Test
    fun longParagraphUsesFixedCursorWindowAndAdvancesAbsoluteOffset() {
        val text = "0123456789abcdefghijklmnop"
        val originalCursor = 18
        val original = context(
            text = text,
            startOffset = 500,
            selectionStart = originalCursor,
            selectionEnd = originalCursor,
        )

        val constrained = EditorContextWindowPolicy.constrain(
            context = original,
            maxContextChars = 8,
        )

        assertEquals(8, constrained.text.length)
        assertEquals(514, constrained.startOffset)
        assertEquals("efghijkl", constrained.text)
        assertEquals(
            original.startOffset + originalCursor,
            constrained.startOffset + constrained.selectionStartInText,
        )
    }

    @Test
    fun longParagraphNeverSplitsUtf16SurrogatePairs() {
        val text = "0123\uD83D\uDC14abcdefghij\uD83D\uDC8Aklmnop"
        // These cursors make the nominal start split 🐔 and the nominal end split 💊.
        listOf(9, 12).forEach { cursor ->
            val original = context(
                text = text,
                startOffset = 40,
                selectionStart = cursor,
                selectionEnd = cursor,
            )

            val constrained = EditorContextWindowPolicy.constrain(
                context = original,
                maxContextChars = 9,
            )

            assertTrue(constrained.text.length <= 9)
            assertFalse(constrained.text.firstOrNull()?.isLowSurrogate() == true)
            assertFalse(constrained.text.lastOrNull()?.isHighSurrogate() == true)
            assertTrue(constrained.text.hasValidSurrogatePairs())
            assertEquals(
                original.startOffset + cursor,
                constrained.startOffset + constrained.selectionStartInText,
            )
        }
    }

    private fun context(
        text: String,
        startOffset: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): ExtractedEditorText = ExtractedEditorText(
        text = text,
        startOffset = startOffset,
        selectionStartInText = selectionStart,
        selectionEndInText = selectionEnd,
        completeDocument = false,
    )

    private fun String.hasValidSurrogatePairs(): Boolean {
        indices.forEach { index ->
            when {
                this[index].isHighSurrogate() ->
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                this[index].isLowSurrogate() ->
                    if (index == 0 || !this[index - 1].isHighSurrogate()) return false
            }
        }
        return true
    }
}
