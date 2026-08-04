package io.github.ethanbird.senseime.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleMarkdownParserTest {
    @Test
    fun parsesHeadingsListsQuotesAndCodeWithoutFlatteningThem() {
        val blocks = SimpleMarkdownParser.parse(
            """
            ## 系统概览

            - Android
            2. Browser

            > 保持会话

            ```sh
            pwd
            ```
            """.trimIndent(),
        )

        assertEquals(MarkdownBlock.Heading(2, "系统概览"), blocks[0])
        assertEquals(MarkdownBlock.Bullet("Android"), blocks[1])
        assertEquals(MarkdownBlock.Bullet("Browser", 2), blocks[2])
        assertEquals(MarkdownBlock.Quote("保持会话"), blocks[3])
        assertEquals(MarkdownBlock.Code("sh", "pwd"), blocks[4])
    }

    @Test
    fun keepsAnOpenStreamingCodeFenceAsACodeBlock() {
        val blocks = SimpleMarkdownParser.parse("```kotlin\nval answer = 42")

        assertTrue(blocks.single() is MarkdownBlock.Code)
        assertEquals("val answer = 42", (blocks.single() as MarkdownBlock.Code).text)
    }
}
