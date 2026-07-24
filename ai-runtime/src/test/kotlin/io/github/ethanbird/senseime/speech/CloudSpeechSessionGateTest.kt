package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechSessionGateTest {
    @Test
    fun `cancel invalidates late callbacks and session id cannot be reused`() {
        val gate = CloudSpeechSessionGate()
        val first = checkNotNull(gate.tryBegin(1L))
        assertTrue(gate.isCurrent(first))

        assertNotNull(gate.cancel(1L))
        assertFalse(gate.isCurrent(first))
        assertNull(gate.tryBegin(1L))

        val second = checkNotNull(gate.tryBegin(2L))
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun `complete accepts only active generation`() {
        val gate = CloudSpeechSessionGate()
        val token = checkNotNull(gate.tryBegin(5L))

        assertTrue(gate.complete(token))
        assertFalse(gate.complete(token))
        assertFalse(gate.isCurrent(token))
    }

    @Test
    fun `active session prevents overlapping microphone generation`() {
        val gate = CloudSpeechSessionGate()
        assertNotNull(gate.tryBegin(3L))
        assertNull(gate.tryBegin(4L))
        assertNotNull(gate.invalidateAll())
        assertNotNull(gate.tryBegin(4L))
    }
}
