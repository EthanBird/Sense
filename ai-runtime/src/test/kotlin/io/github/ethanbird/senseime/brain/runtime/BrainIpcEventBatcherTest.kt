package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainIpcEventBatcherTest {
    @Test
    fun oneCharacterProviderFragmentsBecomeOneBinderEvent() {
        val batcher = BrainIpcEventBatcher(flushAtChars = 16)
        "流式输出不会堵塞输入线程".forEach { character ->
            batcher.append(AiEvent.PreviewDelta("request", 7L, character.toString()))
        }

        val events = batcher.drain()

        assertEquals(1, events.size)
        assertEquals(
            "流式输出不会堵塞输入线程",
            (events.single() as AiEvent.PreviewDelta).text,
        )
        assertTrue(batcher.isEmpty)
    }

    @Test
    fun descriptionAndPreviewOrderingIsPreserved() {
        val batcher = BrainIpcEventBatcher(flushAtChars = 100)
        batcher.append(AiEvent.DescriptionDelta("request", 2L, "正在"))
        batcher.append(AiEvent.DescriptionDelta("request", 2L, "润色"))
        batcher.append(AiEvent.PreviewDelta("request", 2L, "结果"))
        batcher.append(AiEvent.DescriptionDelta("request", 2L, "完成"))

        val events = batcher.drain()

        assertEquals(3, events.size)
        assertEquals("正在润色", (events[0] as AiEvent.DescriptionDelta).text)
        assertEquals("结果", (events[1] as AiEvent.PreviewDelta).text)
        assertEquals("完成", (events[2] as AiEvent.DescriptionDelta).text)
    }

    @Test
    fun thresholdRequestsAnImmediateFlushWithoutLosingText() {
        val batcher = BrainIpcEventBatcher(flushAtChars = 4)

        assertFalse(batcher.append(AiEvent.PreviewDelta("request", 1L, "abc")))
        assertTrue(batcher.append(AiEvent.PreviewDelta("request", 1L, "d")))
        assertEquals("abcd", (batcher.drain().single() as AiEvent.PreviewDelta).text)
    }

    @Test
    fun terminalBoundaryDrainsVisibleTextFirst() {
        val batcher = BrainIpcEventBatcher(flushAtChars = 100)
        batcher.append(AiEvent.DescriptionDelta("request", 9L, "正在"))
        batcher.append(AiEvent.PreviewDelta("request", 9L, "结果"))

        val events = batcher.drainBefore(
            AiEvent.Cancelled(
                requestId = "request",
                runGeneration = 9L,
                reason = HarnessCancelReason.CALLER_REQUESTED,
            ),
        )

        assertEquals(3, events.size)
        assertTrue(events[0] is AiEvent.DescriptionDelta)
        assertTrue(events[1] is AiEvent.PreviewDelta)
        assertTrue(events[2] is AiEvent.Cancelled)
        assertTrue(batcher.isEmpty)
    }
}
