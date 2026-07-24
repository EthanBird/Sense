package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionStateTest {
    @Test
    fun `one sequence stays monotonic across alternating recognition backends`() {
        val ids = SpeechSessionIdSequence()

        assertEquals(1L, ids.next()) // system
        assertEquals(2L, ids.next()) // cloud
        assertEquals(3L, ids.next()) // system retry
    }

    @Test
    fun `explicit reservation rejects reuse and advances legacy allocation`() {
        val ids = SpeechSessionIdSequence()

        ids.reserve(7L)

        assertThrows(IllegalArgumentException::class.java) { ids.reserve(7L) }
        assertThrows(IllegalArgumentException::class.java) { ids.reserve(6L) }
        assertEquals(8L, ids.next())
    }

    @Test
    fun `callback cannot create a session before started event`() {
        val idle = SpeechRecognitionState()
        assertSame(idle, reduce(idle, SpeechRecognitionEvent.PartialResult(0L, "错误")))
        assertSame(idle, reduce(idle, SpeechRecognitionEvent.Ready(1L)))
    }

    @Test
    fun `partial results replace instead of append and revision is monotonic`() {
        val started = reduce(
            SpeechRecognitionState(),
            SpeechRecognitionEvent.Started(1L, usingOnDeviceRecognizer = true),
        )
        val ready = reduce(started, SpeechRecognitionEvent.Ready(1L))
        val first = reduce(ready, SpeechRecognitionEvent.PartialResult(1L, "先"))
        val revised = reduce(first, SpeechRecognitionEvent.PartialResult(1L, "先思输入法"))

        assertEquals(SpeechRecognitionPhase.LISTENING, revised.phase)
        assertEquals("先思输入法", revised.partialText)
        assertEquals("先思输入法", revised.visibleText)
        assertTrue(revised.usingOnDeviceRecognizer)
        assertTrue(started.revision < ready.revision)
        assertTrue(ready.revision < first.revision)
        assertTrue(first.revision < revised.revision)
    }

    @Test
    fun `rms is bounded and non-finite callbacks are ignored`() {
        val started = reduce(
            SpeechRecognitionState(),
            SpeechRecognitionEvent.Started(4L, usingOnDeviceRecognizer = false),
        )
        val loud = reduce(started, SpeechRecognitionEvent.RmsChanged(4L, 200f))
        assertEquals(1f, loud.waveformLevel)

        val ignored = reduce(loud, SpeechRecognitionEvent.RmsChanged(4L, Float.NaN))
        assertSame(loud, ignored)
    }

    @Test
    fun `final result clears partial and freezes terminal state`() {
        val started = reduce(
            SpeechRecognitionState(),
            SpeechRecognitionEvent.Started(9L, usingOnDeviceRecognizer = false),
        )
        val partial = reduce(
            started,
            SpeechRecognitionEvent.PartialResult(9L, "测试"),
        )
        val final = reduce(
            partial,
            SpeechRecognitionEvent.FinalResult(
                sessionId = 9L,
                text = "测试完成",
                alternatives = listOf("测试完"),
            ),
        )
        val late = reduce(
            final,
            SpeechRecognitionEvent.PartialResult(9L, "迟到回调"),
        )

        assertEquals(SpeechRecognitionPhase.COMPLETED, final.phase)
        assertEquals("", final.partialText)
        assertEquals("测试完成", final.finalText)
        assertEquals(listOf("测试完"), final.alternatives)
        assertSame(final, late)
    }

    @Test
    fun `stale callbacks cannot mutate a newer session`() {
        val first = reduce(
            SpeechRecognitionState(),
            SpeechRecognitionEvent.Started(1L, usingOnDeviceRecognizer = false),
        )
        val cancelled = reduce(first, SpeechRecognitionEvent.Cancelled(1L))
        val second = reduce(
            cancelled,
            SpeechRecognitionEvent.Started(2L, usingOnDeviceRecognizer = true),
        )
        val stale = reduce(second, SpeechRecognitionEvent.PartialResult(1L, "错误"))

        assertSame(second, stale)
        assertEquals(2L, stale.sessionId)
        assertEquals("", stale.partialText)
    }

    @Test
    fun `late ready and rms callbacks cannot regress processing state`() {
        val started = reduce(
            SpeechRecognitionState(),
            SpeechRecognitionEvent.Started(8L, usingOnDeviceRecognizer = false),
        )
        val processing = reduce(
            started,
            SpeechRecognitionEvent.ProcessingRequested(8L),
        )

        assertSame(processing, reduce(processing, SpeechRecognitionEvent.Ready(8L)))
        assertSame(
            processing,
            reduce(processing, SpeechRecognitionEvent.RmsChanged(8L, 4f)),
        )
        val partial = reduce(
            processing,
            SpeechRecognitionEvent.PartialResult(8L, "最后的中间结果"),
        )
        assertEquals(SpeechRecognitionPhase.PROCESSING, partial.phase)
    }

    @Test
    fun `destroy is terminal and clears sensitive transient text`() {
        val started = reduce(
            SpeechRecognitionState(),
            SpeechRecognitionEvent.Started(3L, usingOnDeviceRecognizer = false),
        )
        val partial = reduce(
            started,
            SpeechRecognitionEvent.PartialResult(3L, "不应保留"),
        )
        val destroyed = reduce(partial, SpeechRecognitionEvent.Destroyed)
        val ignored = reduce(
            destroyed,
            SpeechRecognitionEvent.Started(4L, usingOnDeviceRecognizer = false),
        )

        assertEquals(SpeechRecognitionPhase.DESTROYED, destroyed.phase)
        assertEquals("", destroyed.partialText)
        assertNull(destroyed.finalText)
        assertSame(destroyed, ignored)
    }

    private fun reduce(
        state: SpeechRecognitionState,
        event: SpeechRecognitionEvent,
    ) = SpeechRecognitionReducer.reduce(state, event)
}
