package io.github.ethanbird.senseime.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableRetryVisibleStreamTest {
    @Test
    fun `matching regenerated prefix is suppressed and only new suffix is appended`() {
        val stream = StableRetryVisibleStream(
            firstDescription = "正在润色",
            firstPreview = "稳定前缀",
        )

        assertEquals(StableRetryVisibleUpdate(), stream.appendPreview("稳定"))
        assertEquals(StableRetryVisibleUpdate(), stream.appendPreview("前缀"))
        assertEquals(
            StableRetryVisibleUpdate(preview = "继续"),
            stream.appendPreview("继续"),
        )
    }

    @Test
    fun `divergent description never rewinds a stable preview`() {
        val stream = StableRetryVisibleStream(
            firstDescription = "旧说明",
            firstPreview = "稳定结果",
        )

        assertEquals(StableRetryVisibleUpdate(), stream.appendDescription("新说明"))
        val preview = stream.appendPreview("稳定结果继续")

        assertFalse(preview.replace)
        assertEquals("继续", preview.preview)
        assertEquals("", preview.description)
    }

    @Test
    fun `divergent first character is buffered then replaced without an empty frame`() {
        val stream = StableRetryVisibleStream(
            firstDescription = "正在处理",
            firstPreview = "原来的输出内容",
            divergenceBufferChars = 4,
        )

        val first = stream.appendPreview("新")
        assertFalse(first.replace)
        assertEquals("", first.preview)

        val replacement = stream.appendPreview("结果内容")
        assertTrue(replacement.replace)
        assertEquals("新结果内容", replacement.preview)
        assertTrue(replacement.preview.isNotEmpty())
        assertEquals("正在处理", replacement.description)
    }

    @Test
    fun `shorter completed retry atomically replaces with its nonempty value`() {
        val stream = StableRetryVisibleStream(
            firstDescription = "",
            firstPreview = "abcdef",
        )

        assertEquals(StableRetryVisibleUpdate(), stream.appendPreview("abc"))
        val completed = stream.finish()

        assertTrue(completed.replace)
        assertEquals("abc", completed.preview)
    }

    @Test
    fun `empty completed retry waits for validated final patch instead of blanking surface`() {
        val stream = StableRetryVisibleStream(
            firstDescription = "正在处理",
            firstPreview = "旧结果",
        )

        assertEquals(StableRetryVisibleUpdate(), stream.finish())
    }

    @Test
    fun `one character fragments compare every stable prefix character only once`() {
        val prefix = "稳定".repeat(2_048)
        val stream = StableRetryVisibleStream(firstDescription = "", firstPreview = prefix)

        prefix.forEach { character ->
            assertEquals(
                StableRetryVisibleUpdate(),
                stream.appendPreview(character.toString()),
            )
        }

        assertEquals(prefix.length.toLong(), stream.comparedCharCount)
        assertEquals(
            StableRetryVisibleUpdate(preview = "续"),
            stream.appendPreview("续"),
        )
        assertEquals(prefix.length.toLong(), stream.comparedCharCount)
    }
}
