package io.github.ethanbird.senseime.brain.runtime

import android.os.Bundle
import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorPatchV1
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.HarnessResultMode
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchOperationV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

internal object BrainMessageProtocol {
    const val START = 1
    const val CANCEL = 2
    const val EVENT = 3
    const val ATTACH = 4
    const val ATTACH_RESULT = 5
}

internal object BrainMessageCodec {
    fun encodeIdentity(requestId: String, generation: Long): Bundle = Bundle().apply {
        putString("request_id", requestId)
        putLong("generation", generation)
    }

    fun decodeIdentity(bundle: Bundle): Pair<String, Long> =
        bundle.requireString("request_id") to bundle.getLong("generation")

    fun encodeAttachResult(
        requestId: String,
        generation: Long,
        attached: Boolean,
    ): Bundle = encodeIdentity(requestId, generation).apply {
        putBoolean("attached", attached)
    }

    fun decodeAttachResult(bundle: Bundle): Triple<String, Long, Boolean> {
        val (requestId, generation) = decodeIdentity(bundle)
        return Triple(requestId, generation, bundle.getBoolean("attached"))
    }

    fun encodeRequest(request: HarnessRequestV1): Bundle {
        BrainRequestEnvelopePolicy.requireAccepted(request)
        return Bundle().apply {
            putString("request_id", request.requestId)
            putLong("generation", request.runGeneration)
            putString("skill", request.skill.name)
            putString("result_mode", request.resultMode.name)
            putInt("max_output", request.maxOutputChars)
            request.skillCatalogGeneration?.let { generation ->
                putLong("skill_catalog_generation", generation)
            }
            request.activeSkill?.let { activeSkill ->
                putString("active_skill_protocol", activeSkill.protocol)
                putString("active_skill_id", activeSkill.id)
                putLong("active_skill_revision", activeSkill.revision)
                putLong("active_skill_catalog_generation", activeSkill.catalogGeneration)
                putString("active_skill_name", activeSkill.name)
                putString("active_skill_description", activeSkill.description)
                putString("active_skill_content", activeSkill.content)
            }
            val snapshot = request.snapshot
            putString("snapshot_id", snapshot.snapshotId)
            putLong("editor_generation", snapshot.editorGeneration)
            putString("field_identity", snapshot.fieldIdentity)
            putString("capability", snapshot.capability.name)
            putString("text", snapshot.text)
            putInt("text_start", snapshot.textStartOffset)
            putInt("selection_start", snapshot.selection?.start ?: NO_SELECTION)
            putInt("selection_end", snapshot.selection?.end ?: NO_SELECTION)
            putString("target", snapshot.target?.name)
            putString("base_sha256", snapshot.baseSha256)
            putLong("captured_at", snapshot.capturedAtMonotonicMs)
            putBoolean("truncated", snapshot.truncated)
        }
    }

    fun decodeRequest(bundle: Bundle): HarnessRequestV1 {
        val requestId = bundle.requireString("request_id")
        val maxOutput = bundle.getInt("max_output")
        val selectionStart = bundle.getInt("selection_start", NO_SELECTION)
        val selectionEnd = bundle.getInt("selection_end", NO_SELECTION)
        val selection = if (selectionStart == NO_SELECTION || selectionEnd == NO_SELECTION) {
            null
        } else {
            TextSelectionV1(selectionStart, selectionEnd)
        }
        val snapshot = EditorSnapshotV1(
            requestId = requestId,
            snapshotId = bundle.requireString("snapshot_id"),
            editorGeneration = bundle.getLong("editor_generation"),
            fieldIdentity = bundle.requireString("field_identity"),
            capability = SnapshotCapability.valueOf(bundle.requireString("capability")),
            text = bundle.requireString("text"),
            textStartOffset = bundle.getInt("text_start"),
            selection = selection,
            target = bundle.getString("target")?.let(PatchTarget::valueOf),
            baseSha256 = bundle.requireString("base_sha256"),
            capturedAtMonotonicMs = bundle.getLong("captured_at"),
            truncated = bundle.getBoolean("truncated"),
            maxOutputChars = maxOutput,
        )
        return HarnessRequestV1(
            requestId = requestId,
            runGeneration = bundle.getLong("generation"),
            skill = EditorIntent.valueOf(bundle.requireString("skill")),
            resultMode = bundle.getString("result_mode")
                ?.let(HarnessResultMode::valueOf)
                ?: HarnessResultMode.EDITOR_PATCH,
            skillCatalogGeneration = if (bundle.containsKey("skill_catalog_generation")) {
                bundle.getLong("skill_catalog_generation")
            } else {
                null
            },
            activeSkill = bundle.getString("active_skill_id")?.let { activeSkillId ->
                ActiveSkillInstructionV1(
                    protocol = bundle.requireString("active_skill_protocol"),
                    id = activeSkillId,
                    revision = bundle.getLong("active_skill_revision"),
                    catalogGeneration = bundle.getLong("active_skill_catalog_generation"),
                    name = bundle.requireString("active_skill_name"),
                    description = bundle.requireString("active_skill_description"),
                    content = bundle.requireString("active_skill_content"),
                )
            },
            snapshot = snapshot,
            maxOutputChars = maxOutput,
        )
    }

    fun encodeCancel(
        requestId: String,
        generation: Long,
        reason: HarnessCancelReason,
    ): Bundle = Bundle().apply {
        putString("request_id", requestId)
        putLong("generation", generation)
        putString("reason", reason.name)
    }

    fun decodeCancel(bundle: Bundle): Triple<String, Long, HarnessCancelReason> =
        Triple(
            bundle.requireString("request_id"),
            bundle.getLong("generation"),
            HarnessCancelReason.valueOf(bundle.requireString("reason")),
        )

    fun encodeEvent(event: AiEvent): Bundle = Bundle().apply {
        putString("request_id", event.requestId)
        putLong("generation", event.runGeneration)
        when (event) {
            is AiEvent.Started -> {
                putString("type", "started")
                putLong("started_at", event.startedAtMonotonicMs)
            }

            is AiEvent.Status -> {
                putString("type", "status")
                putString("phase", event.phase.name)
                putString("label", event.label)
            }

            is AiEvent.DescriptionDelta -> {
                val payload = encodeDescriptionDelta(event)
                putString("type", payload.type)
                putString(DESCRIPTION_TEXT_KEY, payload.text)
            }

            is AiEvent.PreviewReset -> {
                putString("type", "preview_reset")
                putInt("attempt", event.attempt)
            }

            is AiEvent.PreviewDelta -> {
                putString("type", "preview_delta")
                putString("text", event.text)
            }

            is AiEvent.PreviewReplace -> {
                BrainPreviewReplaceWirePolicy.requirePayload(
                    event.attempt,
                    event.text,
                    event.description,
                )
                putString("type", "preview_replace")
                putInt("attempt", event.attempt)
                putString("text", event.text)
                putString("description", event.description)
            }

            is AiEvent.AgentProgress -> {
                putString("type", "agent_progress")
                putLong("revision", event.revision)
                putString("step_id", event.stepId)
                putString("progress_kind", event.kind.name)
                putString("progress_state", event.state.name)
                putString("title", event.title)
                putString("detail", event.detail)
                event.toolCallId?.let { putString("tool_call_id", it) }
                event.toolName?.let { putString("tool_name", it) }
            }

            is AiEvent.Usage -> {
                putString("type", "usage")
                putLong("input_tokens", event.inputTokens)
                putLong("output_tokens", event.outputTokens)
            }

            is AiEvent.FinalPatch -> {
                putString("type", "final_patch")
                encodePatch(this, event.patch)
            }

            is AiEvent.FinalAnswer -> {
                putString("type", "final_answer")
                putString("text", event.text)
            }

            is AiEvent.Cancelled -> {
                putString("type", "cancelled")
                putString("reason", event.reason.name)
            }

            is AiEvent.Failed -> {
                putString("type", "failed")
                putString("code", event.code.name)
                putBoolean("retryable", event.retryable)
            }
        }
    }

    fun decodeEvent(bundle: Bundle): AiEvent {
        val requestId = bundle.requireString("request_id")
        val generation = bundle.getLong("generation")
        return when (bundle.requireString("type")) {
            "started" -> AiEvent.Started(requestId, generation, bundle.getLong("started_at"))
            "status" -> AiEvent.Status(
                requestId,
                generation,
                HarnessPhase.valueOf(bundle.requireString("phase")),
                bundle.requireString("label"),
            )
            DESCRIPTION_DELTA_TYPE -> decodeDescriptionDelta(
                requestId = requestId,
                generation = generation,
                text = bundle.requireString(DESCRIPTION_TEXT_KEY),
            )
            "preview_reset" -> AiEvent.PreviewReset(
                requestId,
                generation,
                bundle.getInt("attempt"),
            )
            "preview_delta" -> AiEvent.PreviewDelta(
                requestId,
                generation,
                bundle.requireString("text"),
            )
            "preview_replace" -> {
                val payload = BrainPreviewReplaceWirePolicy.requirePayload(
                    attempt = bundle.getInt("attempt").takeIf {
                        bundle.containsKey("attempt")
                    },
                    text = bundle.getString("text"),
                    description = bundle.getString("description"),
                )
                AiEvent.PreviewReplace(
                    requestId = requestId,
                    runGeneration = generation,
                    attempt = payload.attempt,
                    text = payload.text,
                    description = payload.description,
                )
            }
            "agent_progress" -> AiEvent.AgentProgress(
                requestId = requestId,
                runGeneration = generation,
                revision = bundle.getLong("revision"),
                stepId = bundle.requireString("step_id"),
                kind = io.github.ethanbird.senseime.ai.protocol.AgentProgressKind.valueOf(
                    bundle.requireString("progress_kind"),
                ),
                state = io.github.ethanbird.senseime.ai.protocol.AgentProgressState.valueOf(
                    bundle.requireString("progress_state"),
                ),
                title = bundle.requireString("title"),
                detail = bundle.getString("detail").orEmpty(),
                toolCallId = bundle.getString("tool_call_id"),
                toolName = bundle.getString("tool_name"),
            )
            "usage" -> AiEvent.Usage(
                requestId,
                generation,
                bundle.getLong("input_tokens"),
                bundle.getLong("output_tokens"),
            )
            "final_patch" -> AiEvent.FinalPatch(requestId, generation, decodePatch(bundle))
            "final_answer" -> AiEvent.FinalAnswer(
                requestId,
                generation,
                bundle.requireString("text"),
            )
            "cancelled" -> AiEvent.Cancelled(
                requestId,
                generation,
                HarnessCancelReason.valueOf(bundle.requireString("reason")),
            )
            "failed" -> AiEvent.Failed(
                requestId,
                generation,
                HarnessErrorCode.valueOf(bundle.requireString("code")),
                bundle.getBoolean("retryable"),
            )
            else -> error("Unknown Brain event")
        }
    }

    private fun encodePatch(bundle: Bundle, patch: EditorPatchV1) {
        bundle.putString("patch_protocol", patch.protocol)
        bundle.putString("patch_request_id", patch.requestId)
        bundle.putString("patch_snapshot_id", patch.snapshotId)
        bundle.putString("patch_base_sha256", patch.baseSha256)
        bundle.putString("patch_intent", patch.intent.name)
        bundle.putString("operation_type", patch.operation.type.name)
        bundle.putString("operation_target", patch.operation.target?.name)
        bundle.putString("operation_text", patch.operation.text)
        bundle.putString("selection_after", patch.operation.selectionAfter?.name)
    }

    private fun decodePatch(bundle: Bundle): EditorPatchV1 = EditorPatchV1(
        protocol = bundle.requireString("patch_protocol"),
        requestId = bundle.requireString("patch_request_id"),
        snapshotId = bundle.requireString("patch_snapshot_id"),
        baseSha256 = bundle.requireString("patch_base_sha256"),
        intent = EditorIntent.valueOf(bundle.requireString("patch_intent")),
        operation = PatchOperationV1(
            type = PatchOperationType.valueOf(bundle.requireString("operation_type")),
            target = bundle.getString("operation_target")?.let(PatchTarget::valueOf),
            text = bundle.getString("operation_text"),
            selectionAfter = bundle.getString("selection_after")?.let(SelectionAfter::valueOf),
        ),
    )

    private fun Bundle.requireString(key: String): String =
        requireNotNull(getString(key)) { "Missing Brain message field $key" }

    internal fun decodeDescriptionDelta(
        requestId: String,
        generation: Long,
        text: String,
    ): AiEvent.DescriptionDelta = AiEvent.DescriptionDelta(
        requestId = requestId,
        runGeneration = generation,
        text = text,
    )

    internal fun encodeDescriptionDelta(
        event: AiEvent.DescriptionDelta,
    ): DescriptionDeltaWirePayload = DescriptionDeltaWirePayload(
        type = DESCRIPTION_DELTA_TYPE,
        text = event.text,
    )

    internal data class DescriptionDeltaWirePayload(
        val type: String,
        val text: String,
    )

    internal const val DESCRIPTION_DELTA_TYPE = "description_delta"
    internal const val DESCRIPTION_TEXT_KEY = "text"
    private const val NO_SELECTION = Int.MIN_VALUE
}
