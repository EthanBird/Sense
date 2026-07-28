package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRequestEnvelopePolicyTest {
    @Test
    fun maximumLegalCjkAndSurrogateRequestKeepsExplicitBinderHeadroom() {
        val request = maximumLegalRequest()
        ProtocolValidator.validate(request).requireValid()

        val admission = BrainRequestEnvelopePolicy.assess(request)
            as BrainRequestEnvelopePolicy.Admission.Accepted

        assertEquals(
            AgentSkillPolicy.MAX_SKILLS,
            BrainRequestEnvelopePolicy.RESERVED_DISCOVERY_SUMMARIES,
        )
        assertTrue(admission.reservedDiscoveryBytes > 48 * 1024L)
        assertTrue(
            admission.estimatedBytes <
                BrainRequestEnvelopePolicy.PRODUCT_LIMIT_BYTES.toLong(),
        )
        assertTrue(
            admission.systemHeadroomBytes >=
                BrainRequestEnvelopePolicy.REQUIRED_SYSTEM_HEADROOM_BYTES,
        )
        assertTrue(
            admission.estimatedBytes <
                BrainRequestEnvelopePolicy.SYSTEM_BUDGET_BYTES.toLong(),
        )
    }

    @Test
    fun oversizedLocalRequestIsTypedBeforeBundleOrBinderWork() {
        val base = maximumLegalRequest()
        val oversized = base.copy(
            activeSkill = requireNotNull(base.activeSkill).copy(
                content = "界".repeat(SenseAiProtocol.MAX_SKILL_CONTENT_CHARS * 2),
            ),
            snapshot = base.snapshot.copy(
                text = "界".repeat(SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS * 2),
                baseSha256 = EditorTextDigest.sha256Utf8(
                    "界".repeat(SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS * 2),
                ),
            ),
        )

        val admission = BrainRequestEnvelopePolicy.assess(oversized)
            as BrainRequestEnvelopePolicy.Admission.Rejected

        assertEquals(HarnessErrorCode.IPC_ENVELOPE_TOO_LARGE, admission.errorCode)
        assertTrue(admission.estimatedBytes > admission.limitBytes)
        val failure = runCatching {
            BrainRequestEnvelopePolicy.requireAccepted(oversized)
        }.exceptionOrNull()
        assertTrue(failure is BrainRequestEnvelopeTooLargeException)
    }

    private fun maximumLegalRequest(): HarnessRequestV1 {
        val mixed = buildString(SenseAiProtocol.MAX_SKILL_CONTENT_CHARS) {
            repeat(SenseAiProtocol.MAX_SKILL_CONTENT_CHARS / 4) {
                append('\uD83D')
                append('\uDE80')
                append('中')
                append('文')
            }
        }
        assertEquals(SenseAiProtocol.MAX_SKILL_CONTENT_CHARS, mixed.length)
        val requestId = "request-envelope-max"
        val snapshot = EditorSnapshotV1(
            requestId = requestId,
            snapshotId = "snapshot-envelope-max",
            editorGeneration = Long.MAX_VALUE,
            fieldIdentity = "field-envelope-max",
            capability = SnapshotCapability.FULL_DOCUMENT,
            text = mixed,
            selection = TextSelectionV1(mixed.length, mixed.length),
            target = PatchTarget.WHOLE_FIELD,
            baseSha256 = EditorTextDigest.sha256Utf8(mixed),
            capturedAtMonotonicMs = Long.MAX_VALUE,
            truncated = false,
            maxOutputChars = SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS,
        )
        return HarnessRequestV1(
            requestId = requestId,
            runGeneration = Long.MAX_VALUE,
            skill = EditorIntent.SMART_EDIT,
            skillCatalogGeneration = Long.MAX_VALUE,
            activeSkill = ActiveSkillInstructionV1(
                id = "s".repeat(SenseAiProtocol.MAX_ID_CHARS),
                revision = Long.MAX_VALUE,
                catalogGeneration = Long.MAX_VALUE,
                name = "技".repeat(SenseAiProtocol.MAX_SKILL_NAME_CHARS),
                description =
                    "述".repeat(SenseAiProtocol.MAX_SKILL_DESCRIPTION_CHARS),
                content = mixed,
            ),
            snapshot = snapshot,
            maxOutputChars = SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS,
        )
    }
}
