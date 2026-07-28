package io.github.ethanbird.senseime.ai.protocol

/**
 * Provider-neutral input to a single bounded AI editing run.
 *
 * [runGeneration] is generated locally and is never trusted when echoed by a model. It lets
 * callers discard late provider events after cancellation or editor replacement.
 */
data class HarnessRequestV1(
    val protocol: String = SenseAiProtocol.HARNESS_REQUEST_V1,
    val requestId: String,
    val runGeneration: Long,
    val skill: EditorIntent = EditorIntent.SMART_EDIT,
    /**
     * Exact immutable Skill catalog generation visible in the keyboard when this run starts.
     *
     * This is present even when no Skill is active so discovery descriptions and later
     * skill_read calls cannot drift to a different Settings/Agent mutation mid-run.
     */
    val skillCatalogGeneration: Long? = null,
    /**
     * Exact custom/built-in Skill revision selected by the keyboard, if any.
     *
     * [skill] remains the closed editor-operation intent used by the local Patch gate.
     */
    val activeSkill: ActiveSkillInstructionV1? = null,
    val snapshot: EditorSnapshotV1,
    val maxOutputChars: Int = snapshot.maxOutputChars,
)
