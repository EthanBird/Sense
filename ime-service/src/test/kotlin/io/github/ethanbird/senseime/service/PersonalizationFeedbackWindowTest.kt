package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.LearnedPhrase
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalizationFeedbackWindowTest {
    @Test
    fun quickDeleteConsumesOnlyTheNewestCommitInsideTheWindow() {
        var now = 1_000L
        val window = PersonalizationFeedbackWindow(clock = { now })
        window.remember(phrase(), start = 4, endExclusive = 6)

        now += 500L
        val attempt = window.prepareQuickDelete(cursor = 6)

        assertEquals("拟", window.complete(attempt)?.text)
        assertNull(window.complete(attempt))
        assertNull(window.prepareQuickDelete(cursor = 6))
    }

    @Test
    fun oldCommitExpiresBeforeBackspaceCanDemoteIt() {
        var now = 1_000L
        val window = PersonalizationFeedbackWindow(clock = { now })
        window.remember(phrase(), start = 4, endExclusive = 6)

        now += 3_001L

        assertNull(window.prepareQuickDelete(cursor = 6))
    }

    @Test
    fun replacingAnOverlappingCommittedRangeProducesNegativeFeedback() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase(), start = 4, endExclusive = 6)

        val attempt = window.prepareReplacement(selectionStart = 3, selectionEnd = 5)

        assertEquals("拟", window.complete(attempt)?.text)
        assertNull(window.prepareReplacement(selectionStart = 3, selectionEnd = 5))
    }

    @Test
    fun failedUnrelatedEditLeavesTheRecentCommitAvailable() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase(), start = 4, endExclusive = 6)

        // Preparing is phase one. When the editor rejects the edit, the service
        // simply drops the attempt and the original feedback remains live.
        window.prepareReplacement(selectionStart = 10, selectionEnd = 12)
        val quickDelete = window.prepareQuickDelete(cursor = 6)

        assertEquals("拟", window.complete(quickDelete)?.text)
    }

    @Test
    fun cursorAtTheStartOrUnknownCoordinatesNeverCountAsQuickDelete() {
        val atStart = PersonalizationFeedbackWindow(clock = { 1_000L })
        atStart.remember(phrase(), start = 4, endExclusive = 6)
        assertNull(atStart.complete(atStart.prepareQuickDelete(cursor = 4)))

        val unknownCommit = PersonalizationFeedbackWindow(clock = { 1_000L })
        unknownCommit.remember(phrase(), start = -1, endExclusive = -1)
        assertNull(unknownCommit.complete(unknownCommit.prepareQuickDelete(cursor = -1)))
    }

    @Test
    fun acceptedEditCompletesAgainstFrozenFeedbackAfterSynchronousClear() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase(), start = 4, endExclusive = 6)
        val attempt = window.prepareQuickDelete(cursor = 6)

        // Simulates a synchronous onUpdateSelection/resetComposition callback
        // arriving before InputConnection.delete... returns to the service.
        window.clear()

        assertEquals("拟", window.complete(attempt)?.text)
    }

    @Test
    fun completingAnOldAttemptNeverClearsANewerCommit() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("旧"), start = 4, endExclusive = 5)
        val oldAttempt = window.prepareReplacement(selectionStart = 4, selectionEnd = 5)

        window.remember(phrase("新"), start = 8, endExclusive = 9)

        assertEquals("旧", window.complete(oldAttempt)?.text)
        assertEquals(
            "新",
            window.complete(window.prepareQuickDelete(cursor = 9))?.text,
        )
    }

    @Test
    fun nestedAttemptsForTheSameCommitProduceAtMostOneDemotion() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase(), start = 4, endExclusive = 6)
        val outer = window.prepareQuickDelete(cursor = 6)
        val nested = window.prepareQuickDelete(cursor = 6)

        assertEquals("拟", window.complete(nested)?.text)
        assertNull(window.complete(outer))
    }

    @Test
    fun wubiCommitUsesTheSameTransactionalQuickDeleteWindow() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        val candidate = Candidate(
            text = "输入法",
            score = 3f,
            matchKind = CandidateMatchKind.WUBI_EXACT,
            canonicalCode = "lwyy",
        )
        window.rememberWubi("lwyy", candidate, start = 4, endExclusive = 7)

        val target = window.complete(window.prepareQuickDelete(cursor = 7))

        assertEquals(
            PersonalizationLearningTarget.Wubi("lwyy", candidate),
            target,
        )
    }

    private fun phrase(text: String = "拟") = LearnedPhrase(
        fullPinyin = "ni",
        initials = "n",
        text = text,
        useCount = 1,
        createdAtMillis = 1_000L,
        lastUsedAtMillis = 1_000L,
    )
}
