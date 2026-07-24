package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.ai.protocol.isTerminal
import io.github.ethanbird.senseime.brain.AiBrainEngine
import io.github.ethanbird.senseime.brain.api.BrainEventSink
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderTimeouts
import io.github.ethanbird.senseime.brain.api.ReasoningEffort
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicitly opt-in production-path probe for DeepSeek's current multi-turn tool protocol.
 *
 * The test is skipped in ordinary CI. It reads the credential only from process environment,
 * never logs request bodies, model text or the key, and exercises the same transport, decoder,
 * state machine and terminal patch gate used by the private Android Brain process.
 */
class LiveDeepSeekAgentProbeTest {
    private var transport: HttpUrlConnectionProviderTransport? = null

    @After
    fun closeTransport() {
        transport?.close()
    }

    @Test
    fun deepSeekThinkingModeCompletesPublicProgressAndTerminalPatchTurns() {
        val enabled = System.getenv(ENABLE_ENV) == "1"
        val apiKey = System.getenv(KEY_ENV).orEmpty()
        assumeTrue(
            "Set $ENABLE_ENV=1 and provide $KEY_ENV to run the live provider probe.",
            enabled && apiKey.isNotBlank(),
        )

        val text = "请帮我把这句话改得更自然：我明天打算去到公园散步。"
        val requestId = "live-provider-probe"
        val request = HarnessRequestV1(
            requestId = requestId,
            runGeneration = 1L,
            skill = EditorIntent.SMART_EDIT,
            snapshot = EditorSnapshotV1(
                requestId = requestId,
                snapshotId = "live-snapshot",
                editorGeneration = 1L,
                fieldIdentity = "live-probe-field",
                capability = SnapshotCapability.FULL_DOCUMENT,
                text = text,
                selection = TextSelectionV1(text.length, text.length),
                target = PatchTarget.WHOLE_FIELD,
                baseSha256 = EditorTextDigest.sha256Utf8(text),
                capturedAtMonotonicMs = 1L,
                truncated = false,
            ),
        )
        val profile = ProviderProfile(
            id = "live-deepseek",
            displayName = "DeepSeek live probe",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            baseUrl = System.getenv(BASE_URL_ENV)
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_BASE_URL,
            model = System.getenv(MODEL_ENV)
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_MODEL,
            thinkingMode = ThinkingMode.ENABLED,
            reasoningEffort = ReasoningEffort.DEFAULT,
            streaming = true,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
            timeouts = ProviderTimeouts(
                connectTimeoutMs = 15_000,
                firstEventTimeoutMs = 45_000,
                streamIdleTimeoutMs = 120_000,
                totalTimeoutMs = LIVE_DEADLINE_MS,
            ),
        )
        val events = CopyOnWriteArrayList<AiEvent>()
        val liveTransport = HttpUrlConnectionProviderTransport().also { transport = it }
        val startedAt = System.nanoTime()
        val handle = AiBrainEngine(liveTransport).start(
            BrainRunSpec(
                harnessRequest = request,
                provider = profile,
                credential = ProviderCredential.Bearer(apiKey),
            ),
            BrainEventSink(events::add),
        )

        val deadline = System.nanoTime() + LIVE_DEADLINE_MS * 1_000_000L
        try {
            while (!handle.isTerminal && System.nanoTime() < deadline) {
                Thread.sleep(TICK_INTERVAL_MS)
                handle.tick()
            }
        } finally {
            if (!handle.isTerminal) {
                handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
            }
        }

        assertTrue("live run did not reach a terminal event", events.any(AiEvent::isTerminal))
        val failures = events.filterIsInstance<AiEvent.Failed>()
        assertTrue("live run failed with ${failures.map { it.code }}", failures.isEmpty())
        assertEquals(1, events.filterIsInstance<AiEvent.FinalPatch>().size)

        val progress = events.filterIsInstance<AiEvent.AgentProgress>()
        assertTrue(
            "provider did not complete a public Agent update turn",
            progress.any { it.kind == AgentProgressKind.ASSISTANT_UPDATE },
        )
        val toolEvents = progress.filter { it.kind == AgentProgressKind.TOOL }
        assertTrue(toolEvents.any { it.state == AgentProgressState.RUNNING })
        assertTrue(toolEvents.any { it.state == AgentProgressState.COMPLETED })
        assertFalse(
            "private reasoning must never be published as an Agent progress title",
            progress.any { it.title.contains("reasoning_content", ignoreCase = true) },
        )

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        println(
            "live-provider-probe passed: elapsed_ms=$elapsedMs, " +
                "events=${events.size}, progress_updates=${progress.size}, " +
                "tool_updates=${toolEvents.size}",
        )
    }

    private companion object {
        const val ENABLE_ENV = "SENSE_RUN_LIVE_PROVIDER_TEST"
        const val KEY_ENV = "SENSE_TEST_API_KEY"
        const val BASE_URL_ENV = "SENSE_TEST_API_BASE"
        const val MODEL_ENV = "SENSE_TEST_MODEL"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-v4-pro"
        const val TICK_INTERVAL_MS = 100L
        const val LIVE_DEADLINE_MS = 240_000L
    }
}
