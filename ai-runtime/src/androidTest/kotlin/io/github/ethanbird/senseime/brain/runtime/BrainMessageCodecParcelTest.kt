package io.github.ethanbird.senseime.brain.runtime

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrainMessageCodecParcelTest {
    @Test
    fun maximumMixedUtf16RequestAndFullDiscoveryReserveRoundTripBelow512KiB() {
        val request = maximumLegalRequest()
        val bundle = BrainMessageCodec.encodeRequest(request)
        val admission = BrainRequestEnvelopePolicy.assess(request)
            as BrainRequestEnvelopePolicy.Admission.Accepted
        val currentParcel = Parcel.obtain()
        try {
            bundle.writeToParcel(currentParcel, 0)
            assertTrue(
                currentParcel.dataSize().toLong() <=
                    admission.estimatedBytes - admission.reservedDiscoveryBytes,
            )
        } finally {
            currentParcel.recycle()
        }

        /*
         * Production reloads the exact immutable discovery catalog in :brain and therefore does
         * not duplicate these summaries across Binder. Include the complete future-wire reserve
         * in this Android Parcel probe so the preflight's compatibility headroom is measured
         * against real Bundle encoding rather than only the pure estimator.
         */
        bundle.putParcelableArrayList(
            FUTURE_DISCOVERY_RESERVE_KEY,
            ArrayList(
                List(AgentSkillPolicy.MAX_SKILLS) { index ->
                    Bundle().apply {
                        putString(
                            "skill_id",
                            "s${index.toString().padStart(2, '0')}" +
                                "x".repeat(AgentSkillPolicy.MAX_ID_CHARS - 3),
                        )
                        putLong("skill_revision", Long.MAX_VALUE)
                        putString(
                            "skill_name",
                            "技".repeat(AgentSkillPolicy.MAX_NAME_CHARS),
                        )
                        putString(
                            "skill_description",
                            "述".repeat(AgentSkillPolicy.MAX_DESCRIPTION_CHARS),
                        )
                        putString("skill_intent", EditorIntent.SMART_EDIT.name)
                    }
                },
            ),
        )

        val parcel = Parcel.obtain()
        val restoredParcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            val size = parcel.dataSize()
            val bytes = parcel.marshall()
            assertEquals(size, bytes.size)
            assertTrue(size < BrainRequestEnvelopePolicy.SYSTEM_BUDGET_BYTES)
            assertTrue(size.toLong() <= admission.estimatedBytes)

            restoredParcel.unmarshall(bytes, 0, bytes.size)
            restoredParcel.setDataPosition(0)
            val restored = Bundle.CREATOR.createFromParcel(restoredParcel)
            restored.classLoader = javaClass.classLoader
            assertEquals(
                AgentSkillPolicy.MAX_SKILLS,
                restored.getParcelableArrayList<Bundle>(
                    FUTURE_DISCOVERY_RESERVE_KEY,
                )?.size,
            )
            assertEquals(request, BrainMessageCodec.decodeRequest(restored))
        } finally {
            restoredParcel.recycle()
            parcel.recycle()
        }
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
        val requestId = "request-parcel-max"
        return HarnessRequestV1(
            requestId = requestId,
            runGeneration = Long.MAX_VALUE,
            skill = EditorIntent.SMART_EDIT,
            skillCatalogGeneration = Long.MAX_VALUE,
            activeSkill = ActiveSkillInstructionV1(
                id = "s".repeat(AgentSkillPolicy.MAX_ID_CHARS),
                revision = Long.MAX_VALUE,
                catalogGeneration = Long.MAX_VALUE,
                name = "技".repeat(AgentSkillPolicy.MAX_NAME_CHARS),
                description = "述".repeat(AgentSkillPolicy.MAX_DESCRIPTION_CHARS),
                content = mixed,
            ),
            snapshot = EditorSnapshotV1(
                requestId = requestId,
                snapshotId = "snapshot-parcel-max",
                editorGeneration = Long.MAX_VALUE,
                fieldIdentity = "field-parcel-max",
                capability = SnapshotCapability.FULL_DOCUMENT,
                text = mixed,
                selection = TextSelectionV1(mixed.length, mixed.length),
                target = PatchTarget.WHOLE_FIELD,
                baseSha256 = EditorTextDigest.sha256Utf8(mixed),
                capturedAtMonotonicMs = Long.MAX_VALUE,
                truncated = false,
                maxOutputChars = SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS,
            ),
            maxOutputChars = SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS,
        )
    }

    private companion object {
        const val FUTURE_DISCOVERY_RESERVE_KEY = "_sense_future_skill_summaries_probe"
    }
}
