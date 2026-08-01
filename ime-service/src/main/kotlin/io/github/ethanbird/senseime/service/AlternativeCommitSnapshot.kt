package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme

/** Decoder family that owned a candidate when an alternative composition was committed. */
internal enum class AlternativeLearningDomain {
    NONE,
    PINYIN,
    WUBI,
}

/**
 * Immutable ownership captured before calling into a potentially reentrant [android.view.inputmethod.InputConnection].
 *
 * Host callbacks may reset the composition, switch the active scheme, or start another editor
 * before `commitText` returns. Learning and cleanup must therefore use this snapshot rather than
 * consulting the mutable service state after the host call.
 */
internal data class AlternativeCommitSnapshot(
    val compositionKey: AlternativeCompositionKey,
    val rawCode: String,
    val learningDomain: AlternativeLearningDomain,
    val editorSessionId: Long,
    val inputConnectionIdentity: Any?,
) {
    fun stillOwnsComposition(currentKey: AlternativeCompositionKey): Boolean =
        compositionKey == currentKey

    fun isSameEditor(
        currentEditorSessionId: Long,
        currentInputConnectionIdentity: Any?,
    ): Boolean =
        editorSessionId == currentEditorSessionId &&
            inputConnectionIdentity === currentInputConnectionIdentity

    companion object {
        fun capture(
            coordinator: ChineseInputSchemeCoordinator,
            hasCandidate: Boolean,
            editorSessionId: Long,
            inputConnectionIdentity: Any?,
        ): AlternativeCommitSnapshot {
            val learningDomain = if (!hasCandidate) {
                AlternativeLearningDomain.NONE
            } else {
                when (coordinator.scheme) {
                    ChineseInputScheme.PINYIN_T9 -> AlternativeLearningDomain.PINYIN
                    ChineseInputScheme.WUBI_86 -> if (coordinator.isWubiReverseLookup) {
                        AlternativeLearningDomain.PINYIN
                    } else {
                        AlternativeLearningDomain.WUBI
                    }
                    ChineseInputScheme.PINYIN_QWERTY -> AlternativeLearningDomain.NONE
                }
            }
            val compositionKey = coordinator.key()
            return AlternativeCommitSnapshot(
                compositionKey = compositionKey,
                rawCode = compositionKey.rawCode,
                learningDomain = learningDomain,
                editorSessionId = editorSessionId,
                inputConnectionIdentity = inputConnectionIdentity,
            )
        }
    }
}
