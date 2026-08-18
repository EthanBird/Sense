package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserSelectionKind

internal object PinyinCommitLearningPolicy {
    fun evidence(
        hasAcceptedSegments: Boolean,
        requested: UserLearningEvidence,
    ): UserLearningEvidence = if (
        hasAcceptedSegments && requested.kind == UserSelectionKind.DEFAULT_ACCEPT
    ) {
        UserLearningEvidence.COMPOSED_CONFIRM
    } else {
        requested
    }
}
