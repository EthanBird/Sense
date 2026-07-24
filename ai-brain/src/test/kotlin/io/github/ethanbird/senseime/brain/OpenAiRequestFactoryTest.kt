package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ReasoningEffort
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestFactoryTest {
    @Test
    fun `Responses request includes schema reasoning stream and frozen snapshot`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.Bearer("secret-token"),
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals("https://provider.test/v1/responses", wire.url)
        assertEquals("Bearer secret-token", wire.headers["Authorization"])
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"anyOf\""))
        assertFalse(body.contains("\"oneOf\""))
        assertFalse(body.contains("\"const\""))
        assertTrue(body.contains("\"reasoning\":{\"effort\":\"medium\"}"))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"store\":false"))
        assertTrue(body.contains("\\\"text\\\":\\\"原始内容\\\""))
        assertFalse(body.contains("secret-token"))
        assertFalse(body.contains("field_identity"))
        assertFalse(body.contains("field-1"))
        assertFalse(body.contains("editor_generation"))
        assertFalse(body.contains("captured_at_monotonic_ms"))
    }

    @Test
    fun `Chat request uses compatible endpoint and response format`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals("https://provider.test/v1/chat/completions", wire.url)
        assertFalse(wire.headers.containsKey("Authorization"))
        assertTrue(body.contains("\"messages\""))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_schema\""))
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test
    fun `Responses JSON object mode uses text format and disables storage`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_RESPONSES,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"text\":{\"format\":{\"type\":\"json_object\"}}"))
        assertTrue(body.contains("\"store\":false"))
        assertFalse(body.contains("\"response_format\""))
        assertTrue(body.contains("Closed output JSON contract"))
        assertTrue(body.contains("Valid no_change example"))
    }

    @Test
    fun `DeepSeek chat JSON object request uses compatible endpoint and inline patch contract`() {
        val wire = OpenAiRequestFactory.create(
            profile = ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals("https://api.deepseek.com/v1/chat/completions", wire.url)
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertFalse(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("Closed output JSON contract"))
        assertTrue(body.contains("Root keys are exactly protocol, request_id, snapshot_id"))
        assertTrue(body.contains("Valid replace example for this request"))
        assertTrue(body.contains("Valid no_change example"))
        assertTrue(body.contains("\\\"selection_after\\\":\\\"end\\\""))
        assertTrue(body.contains("\\\"operation\\\":{\\\"type\\\":\\\"no_change\\\"}"))
    }

    @Test
    fun `prompt-only request also receives closed inline patch contract`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                structuredOutput = StructuredOutputMode.PROMPT_ONLY,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertFalse(body.contains("\"response_format\""))
        assertTrue(body.contains("Closed output JSON contract"))
        assertTrue(body.contains("no Markdown, comments, or extra keys"))
    }

    @Test
    fun `native schema mode does not duplicate schema inside prompt`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertFalse(body.contains("Closed output JSON contract"))
    }

    @Test
    fun `repair request is explicitly one shot and includes rejected document`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 1,
            repair = RepairContext("{bad}", "$.protocol: wrong"),
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals(1, wire.attempt)
        assertTrue(body.contains("only repair attempt"))
        assertTrue(body.contains("{bad}"))
        assertTrue(body.contains("$.protocol: wrong"))
        assertTrue(body.contains("Closed output JSON contract"))
    }

    private fun profile(
        style: ProviderApiStyle,
        structuredOutput: StructuredOutputMode = StructuredOutputMode.JSON_SCHEMA,
    ) = ProviderProfile(
        id = "test",
        displayName = "Test",
        apiStyle = style,
        baseUrl = "https://provider.test/v1",
        model = "sense-model",
        reasoningEffort = ReasoningEffort.MEDIUM,
        structuredOutput = structuredOutput,
    )

    private fun harness(): HarnessRequestV1 {
        val text = "原始内容"
        val snapshot = EditorSnapshotV1(
            requestId = "request-1",
            snapshotId = "snapshot-1",
            editorGeneration = 1,
            fieldIdentity = "field-1",
            capability = SnapshotCapability.FULL_DOCUMENT,
            text = text,
            selection = TextSelectionV1(text.length, text.length),
            target = PatchTarget.WHOLE_FIELD,
            baseSha256 = EditorTextDigest.sha256Utf8(text),
            capturedAtMonotonicMs = 0,
            truncated = false,
        )
        return HarnessRequestV1(
            requestId = snapshot.requestId,
            runGeneration = 1,
            skill = EditorIntent.REWRITE,
            snapshot = snapshot,
        )
    }
}
