package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserSelectionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinCommitLearningPolicyTest {
    @Test
    fun defaultConfirmationOfAnAssembledPhraseUsesComposedEvidence() {
        assertEquals(
            UserLearningEvidence.COMPOSED_CONFIRM,
            PinyinCommitLearningPolicy.evidence(
                hasAcceptedSegments = true,
                requested = UserLearningEvidence.DEFAULT_ACCEPT,
            ),
        )
    }

    @Test
    fun explicitAndUnassembledCommitsKeepTheirRequestedEvidence() {
        val explicit = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 7)
        assertEquals(explicit, PinyinCommitLearningPolicy.evidence(true, explicit))
        assertEquals(
            UserLearningEvidence.DEFAULT_ACCEPT,
            PinyinCommitLearningPolicy.evidence(false, UserLearningEvidence.DEFAULT_ACCEPT),
        )
    }
}
