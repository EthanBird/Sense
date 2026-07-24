package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

/**
 * Safe, provider-neutral progress reported by [SenseAiProviderTestClient].
 *
 * These values deliberately do not expose provider response bodies, credentials, editor text, or
 * the internal AI protocol types to Settings UI consumers.
 */
enum class ProviderConnectionTestPhase {
    CONNECTING,
    UNDERSTANDING,
    GENERATING,
    VALIDATING,
}

/**
 * Stable failure categories suitable for a localized Settings message.
 *
 * A caller should not infer or display provider response bodies from these values. They are
 * intentionally coarse so authentication and transport diagnostics cannot leak secrets.
 */
enum class ProviderConnectionTestFailure {
    NOT_CONFIGURED,
    AUTHENTICATION,
    QUOTA,
    CONFIGURATION,
    RATE_LIMIT,
    UNAVAILABLE,
    NETWORK,
    TIMEOUT,
    PROTOCOL,
    INTERNAL,
}

sealed interface ProviderConnectionTestEvent {
    /** The private Brain service is being bound and the saved configuration will be loaded. */
    data object Starting : ProviderConnectionTestEvent

    data class Progress(
        val phase: ProviderConnectionTestPhase,
    ) : ProviderConnectionTestEvent

    /**
     * The complete provider path returned a patch which passed Sense's strict patch validation.
     *
     * Token counts are nullable because not every OpenAI-compatible provider reports usage.
     */
    data class Succeeded(
        val inputTokens: Long?,
        val outputTokens: Long?,
    ) : ProviderConnectionTestEvent

    data class Failed(
        val failure: ProviderConnectionTestFailure,
        val retryable: Boolean,
    ) : ProviderConnectionTestEvent

    /** Emitted only for an explicit [SenseAiProviderTestClient.cancel] call. */
    data object Cancelled : ProviderConnectionTestEvent
}

internal data class ProviderConnectionTestIdentity(
    val requestId: String,
    val generation: Long,
)

/**
 * A tiny authority gate independent from Android/Binder.
 *
 * It provides a second late-event barrier on top of SenseAiBrainClient. Replacing or cancelling a
 * run revokes its identity synchronously on the main thread before Brain cancellation is sent.
 */
internal class ProviderConnectionTestRunGate {
    private var active: ProviderConnectionTestIdentity? = null

    fun replace(identity: ProviderConnectionTestIdentity): ProviderConnectionTestIdentity? =
        active.also { active = identity }

    fun isActive(requestId: String, generation: Long): Boolean =
        active == ProviderConnectionTestIdentity(requestId, generation)

    fun takeIfActive(
        requestId: String,
        generation: Long,
    ): ProviderConnectionTestIdentity? {
        if (!isActive(requestId, generation)) return null
        return active.also { active = null }
    }

    fun revoke(): ProviderConnectionTestIdentity? = active.also { active = null }
}

/**
 * Pure request construction and diagnostic mapping for the end-to-end connectivity probe.
 */
internal object ProviderConnectionTestProtocol {
    // Enough room for the protocol envelope plus a short no_change/replace response while still
    // keeping this explicit, user-triggered diagnostic inexpensive.
    const val MAX_OUTPUT_CHARS = 512
    const val TEST_TEXT = "Sense AI connectivity test."

    fun buildRequest(
        requestId: String,
        generation: Long,
        capturedAtMonotonicMs: Long,
    ): HarnessRequestV1 {
        val snapshot = EditorSnapshotV1(
            requestId = requestId,
            snapshotId = "$requestId.snapshot",
            editorGeneration = generation,
            fieldIdentity = "sense.settings.provider-test",
            capability = SnapshotCapability.FULL_DOCUMENT,
            text = TEST_TEXT,
            selection = TextSelectionV1(TEST_TEXT.length, TEST_TEXT.length),
            target = PatchTarget.WHOLE_FIELD,
            baseSha256 = EditorTextDigest.sha256Utf8(TEST_TEXT),
            capturedAtMonotonicMs = capturedAtMonotonicMs,
            truncated = false,
            maxOutputChars = MAX_OUTPUT_CHARS,
        )
        return HarnessRequestV1(
            requestId = requestId,
            runGeneration = generation,
            skill = EditorIntent.SMART_EDIT,
            snapshot = snapshot,
            maxOutputChars = MAX_OUTPUT_CHARS,
        )
    }

    fun mapPhase(phase: HarnessPhase): ProviderConnectionTestPhase = when (phase) {
        HarnessPhase.CONNECTING -> ProviderConnectionTestPhase.CONNECTING
        HarnessPhase.UNDERSTANDING -> ProviderConnectionTestPhase.UNDERSTANDING
        HarnessPhase.GENERATING -> ProviderConnectionTestPhase.GENERATING
        HarnessPhase.VALIDATING -> ProviderConnectionTestPhase.VALIDATING
    }

    fun mapFailure(code: HarnessErrorCode): ProviderConnectionTestFailure =
        mapFailureName(code.name)

    /**
     * Kept name-based so adding a new private harness code cannot accidentally expose it through
     * this public API; unknown values fail closed as [ProviderConnectionTestFailure.INTERNAL].
     */
    fun mapFailureName(code: String): ProviderConnectionTestFailure = when (code) {
        "PROVIDER_NOT_CONFIGURED" -> ProviderConnectionTestFailure.NOT_CONFIGURED
        "PROVIDER_AUTHENTICATION" -> ProviderConnectionTestFailure.AUTHENTICATION
        "PROVIDER_QUOTA" -> ProviderConnectionTestFailure.QUOTA
        "PROVIDER_CONFIGURATION",
        "REQUEST_INVALID",
        -> ProviderConnectionTestFailure.CONFIGURATION

        "PROVIDER_RATE_LIMIT" -> ProviderConnectionTestFailure.RATE_LIMIT
        "PROVIDER_UNAVAILABLE" -> ProviderConnectionTestFailure.UNAVAILABLE
        "PROVIDER_FAILURE" -> ProviderConnectionTestFailure.NETWORK
        "FIRST_EVENT_TIMEOUT",
        "STREAM_IDLE_TIMEOUT",
        "TOTAL_TIMEOUT",
        -> ProviderConnectionTestFailure.TIMEOUT

        "PROTOCOL_INVALID",
        "EVENT_LIMIT_EXCEEDED",
        "PREVIEW_LIMIT_EXCEEDED",
        "REPAIR_LIMIT_EXCEEDED",
        "INVALID_EVENT",
        -> ProviderConnectionTestFailure.PROTOCOL

        else -> ProviderConnectionTestFailure.INTERNAL
    }
}
