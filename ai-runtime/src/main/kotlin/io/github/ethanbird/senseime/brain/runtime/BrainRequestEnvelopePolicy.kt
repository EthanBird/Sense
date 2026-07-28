package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy

/**
 * Conservative, payload-copy-free estimate of the Android Bundle/Parcel START envelope.
 *
 * Android's Binder transaction buffer is process-shared and device-dependent in practice, so this
 * is a Sense product limit rather than a claim about a platform-wide maximum. The estimate charges
 * UTF-16 storage, Bundle keys, map-entry/type metadata, Message/Messenger framing, alignment and a
 * complete 64-entry discovery-catalog reserve. Discovery summaries are currently reloaded from the
 * immutable catalog in :brain rather than duplicated across Binder; reserving them here keeps the
 * frozen wire format forward-compatible without consuming the final 64 KiB system headroom.
 */
internal object BrainRequestEnvelopePolicy {
    const val SYSTEM_BUDGET_BYTES = 512 * 1024
    const val PRODUCT_LIMIT_BYTES = 448 * 1024
    const val REQUIRED_SYSTEM_HEADROOM_BYTES = SYSTEM_BUDGET_BYTES - PRODUCT_LIMIT_BYTES
    const val RESERVED_DISCOVERY_SUMMARIES = AgentSkillPolicy.MAX_SKILLS

    sealed interface Admission {
        val estimatedBytes: Long

        data class Accepted(
            override val estimatedBytes: Long,
            val reservedDiscoveryBytes: Long,
            val systemHeadroomBytes: Long,
        ) : Admission

        data class Rejected(
            override val estimatedBytes: Long,
            val limitBytes: Int = PRODUCT_LIMIT_BYTES,
            val errorCode: HarnessErrorCode = HarnessErrorCode.IPC_ENVELOPE_TOO_LARGE,
        ) : Admission
    }

    fun assess(request: HarnessRequestV1): Admission {
        val budget = ParcelEstimate()
        budget.addFraming()
        budget.addString("request_id", request.requestId)
        budget.addLong("generation")
        budget.addString("skill", request.skill.name)
        budget.addInt("max_output")
        request.skillCatalogGeneration?.let {
            budget.addLong("skill_catalog_generation")
        }
        request.activeSkill?.let { activeSkill ->
            budget.addString("active_skill_protocol", activeSkill.protocol)
            budget.addString("active_skill_id", activeSkill.id)
            budget.addLong("active_skill_revision")
            budget.addLong("active_skill_catalog_generation")
            budget.addString("active_skill_name", activeSkill.name)
            budget.addString("active_skill_description", activeSkill.description)
            budget.addString("active_skill_content", activeSkill.content)
        }

        val snapshot = request.snapshot
        budget.addString("snapshot_id", snapshot.snapshotId)
        budget.addLong("editor_generation")
        budget.addString("field_identity", snapshot.fieldIdentity)
        budget.addString("capability", snapshot.capability.name)
        budget.addString("text", snapshot.text)
        budget.addInt("text_start")
        budget.addInt("selection_start")
        budget.addInt("selection_end")
        budget.addNullableString("target", snapshot.target?.name)
        budget.addString("base_sha256", snapshot.baseSha256)
        budget.addLong("captured_at")
        budget.addBoolean("truncated")

        val discoveryReserve = DISCOVERY_CATALOG_RESERVE_BYTES
        val estimate = budget.bytes + discoveryReserve
        return if (estimate <= PRODUCT_LIMIT_BYTES.toLong()) {
            Admission.Accepted(
                estimatedBytes = estimate,
                reservedDiscoveryBytes = discoveryReserve,
                systemHeadroomBytes = SYSTEM_BUDGET_BYTES.toLong() - estimate,
            )
        } else {
            Admission.Rejected(estimatedBytes = estimate)
        }
    }

    fun requireAccepted(request: HarnessRequestV1) {
        val admission = assess(request)
        if (admission is Admission.Rejected) {
            throw BrainRequestEnvelopeTooLargeException(
                estimatedBytes = admission.estimatedBytes,
                limitBytes = admission.limitBytes,
            )
        }
    }

    private fun discoveryCatalogReserveBytes(): Long {
        val oneSummary = ParcelEstimate().apply {
            addStringLength("skill_id", SenseAiProtocol.MAX_ID_CHARS)
            addLong("skill_revision")
            addStringLength("skill_name", SenseAiProtocol.MAX_SKILL_NAME_CHARS)
            addStringLength(
                "skill_description",
                SenseAiProtocol.MAX_SKILL_DESCRIPTION_CHARS,
            )
            addString("skill_intent", "SMART_EDIT")
        }.bytes
        return align4(LIST_HEADER_BYTES + oneSummary * RESERVED_DISCOVERY_SUMMARIES)
    }

    private class ParcelEstimate {
        var bytes: Long = 0L
            private set

        fun addFraming() {
            bytes += MESSAGE_AND_BUNDLE_FRAMING_BYTES
        }

        fun addString(key: String, value: String) {
            addStringLength(key, value.length)
        }

        fun addStringLength(key: String, utf16Length: Int) {
            addEntry(key, encodedStringBytes(utf16Length))
        }

        fun addNullableString(key: String, value: String?) {
            if (value == null) {
                addEntry(key, NULL_VALUE_BYTES)
            } else {
                addString(key, value)
            }
        }

        fun addLong(key: String) {
            addEntry(key, LONG_VALUE_BYTES)
        }

        fun addInt(key: String) {
            addEntry(key, INT_VALUE_BYTES)
        }

        fun addBoolean(key: String) {
            addEntry(key, INT_VALUE_BYTES)
        }

        private fun addEntry(key: String, valueBytes: Long) {
            bytes += MAP_ENTRY_AND_TYPE_BYTES + encodedStringBytes(key.length) + valueBytes
        }
    }

    private fun encodedStringBytes(utf16Length: Int): Long =
        align4(INT_VALUE_BYTES + (utf16Length.toLong() + 1L) * 2L)

    private fun align4(bytes: Long): Long = (bytes + 3L) and -4L

    private const val MESSAGE_AND_BUNDLE_FRAMING_BYTES = 16L * 1024L
    private const val LIST_HEADER_BYTES = 256L
    private const val MAP_ENTRY_AND_TYPE_BYTES = 24L
    private const val NULL_VALUE_BYTES = 8L
    private const val INT_VALUE_BYTES = 4L
    private const val LONG_VALUE_BYTES = 8L
    private val DISCOVERY_CATALOG_RESERVE_BYTES = discoveryCatalogReserveBytes()
}

internal class BrainRequestEnvelopeTooLargeException(
    val estimatedBytes: Long,
    val limitBytes: Int,
) : IllegalArgumentException(
    "Brain START envelope estimate $estimatedBytes exceeds product limit $limitBytes",
)
