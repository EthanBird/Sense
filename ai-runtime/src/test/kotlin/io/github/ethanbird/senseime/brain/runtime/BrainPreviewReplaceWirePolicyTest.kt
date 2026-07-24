package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessLimits
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class BrainPreviewReplaceWirePolicyTest {
    @Test
    fun validPayloadRoundTripsAllRequiredFields() {
        val payload = BrainPreviewReplaceWirePolicy.requirePayload(
            attempt = 2,
            text = "续接结果",
            description = "连接已恢复",
        )

        assertEquals(2, payload.attempt)
        assertEquals("续接结果", payload.text)
        assertEquals("连接已恢复", payload.description)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingAttemptIsRejected() {
        BrainPreviewReplaceWirePolicy.requirePayload(null, "结果", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingTextIsRejected() {
        BrainPreviewReplaceWirePolicy.requirePayload(2, null, "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingDescriptionIsRejected() {
        BrainPreviewReplaceWirePolicy.requirePayload(2, "结果", null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongAttemptIsRejected() {
        BrainPreviewReplaceWirePolicy.requirePayload(3, "结果", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedTextIsRejected() {
        BrainPreviewReplaceWirePolicy.requirePayload(
            2,
            "x".repeat(SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS + 1),
            "",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedDescriptionIsRejected() {
        BrainPreviewReplaceWirePolicy.requirePayload(
            2,
            "结果",
            "x".repeat(BoundedHarnessLimits.DEFAULT_MAX_DESCRIPTION_CHARS + 1),
        )
    }
}
