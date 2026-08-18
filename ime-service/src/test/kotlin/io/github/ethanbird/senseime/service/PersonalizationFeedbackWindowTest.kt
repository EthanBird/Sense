package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.LearnedPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalizationFeedbackWindowTest {
    @Test
    fun quickDeleteConsumesSingleCodePointCommitInsideTheWindow() {
        var now = 1_000L
        val window = PersonalizationFeedbackWindow(clock = { now })
        window.remember(phrase("甲"), start = 4, endExclusive = 5)

        now += 500L
        val attempt = window.prepareQuickDelete(cursor = 5)

        assertEquals("甲", window.complete(attempt)?.text)
        assertNull(window.complete(attempt))
        assertNull(window.prepareQuickDelete(cursor = 5))
    }

    @Test
    fun deletingOnlyTheLastCodePointDoesNotDemoteTheWholeLearnedPhrase() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("程彻"), start = 4, endExclusive = 6)

        val attempt = window.prepareQuickDelete(cursor = 6)

        assertNull(window.complete(attempt))
        assertNull(window.prepareQuickDelete(cursor = 5))
    }

    @Test
    fun supplementaryHanSingleCodePointStillCountsAsAWholeQuickDelete() {
        val text = String(Character.toChars(0x29F7E))
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase(text), start = 4, endExclusive = 6)

        assertEquals(text, window.complete(window.prepareQuickDelete(cursor = 6))?.text)
    }

    @Test
    fun oldCommitExpiresBeforeBackspaceCanDemoteIt() {
        var now = 1_000L
        val window = PersonalizationFeedbackWindow(clock = { now })
        window.remember(phrase("甲"), start = 4, endExclusive = 5)

        now += 3_001L

        assertNull(window.prepareQuickDelete(cursor = 5))
    }

    @Test
    fun replacingTheWholeCommittedRangeProducesNegativeFeedback() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("程彻"), start = 4, endExclusive = 6)

        val attempt = window.prepareReplacement(selectionStart = 4, selectionEnd = 6)

        assertEquals("程彻", window.complete(attempt)?.text)
        assertNull(window.prepareReplacement(selectionStart = 4, selectionEnd = 6))
    }

    @Test
    fun replacingOnlyPartOfACommittedPhraseDoesNotDemoteTheWholePhrase() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("程彻"), start = 4, endExclusive = 6)

        assertNull(window.complete(window.prepareReplacement(selectionStart = 5, selectionEnd = 6)))
    }

    @Test
    fun replacingABroaderSentenceDoesNotTreatOneEmbeddedPhraseAsRejected() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("程彻"), start = 4, endExclusive = 6)

        assertNull(window.complete(window.prepareReplacement(selectionStart = 0, selectionEnd = 10)))
    }

    @Test
    fun failedUnrelatedEditLeavesTheRecentCommitAvailable() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("甲"), start = 4, endExclusive = 5)

        window.prepareReplacement(selectionStart = 10, selectionEnd = 12)
        val quickDelete = window.prepareQuickDelete(cursor = 5)

        assertEquals("甲", window.complete(quickDelete)?.text)
    }

    @Test
    fun cursorAtTheStartOrUnknownCoordinatesNeverCountAsQuickDelete() {
        val atStart = PersonalizationFeedbackWindow(clock = { 1_000L })
        atStart.remember(phrase("甲"), start = 4, endExclusive = 5)
        assertNull(atStart.complete(atStart.prepareQuickDelete(cursor = 4)))

        val unknownCommit = PersonalizationFeedbackWindow(clock = { 1_000L })
        unknownCommit.remember(phrase("甲"), start = -1, endExclusive = -1)
        assertNull(unknownCommit.complete(unknownCommit.prepareQuickDelete(cursor = -1)))
    }

    @Test
    fun acceptedEditCompletesAgainstFrozenFeedbackAfterSynchronousClear() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("甲"), start = 4, endExclusive = 5)
        val attempt = window.prepareQuickDelete(cursor = 5)

        window.clear()

        assertEquals("甲", window.complete(attempt)?.text)
    }

    @Test
    fun completingAnOldAttemptNeverClearsANewerCommit() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("旧"), start = 4, endExclusive = 5)
        val oldAttempt = window.prepareReplacement(selectionStart = 4, selectionEnd = 5)

        window.remember(phrase("新"), start = 8, endExclusive = 9)

        assertEquals("旧", window.complete(oldAttempt)?.text)
        assertEquals("新", window.complete(window.prepareQuickDelete(cursor = 9))?.text)
    }

    @Test
    fun nestedAttemptsForTheSameCommitProduceAtMostOneDemotion() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        window.remember(phrase("甲"), start = 4, endExclusive = 5)
        val outer = window.prepareQuickDelete(cursor = 5)
        val nested = window.prepareQuickDelete(cursor = 5)

        assertEquals("甲", window.complete(nested)?.text)
        assertNull(window.complete(outer))
    }

    @Test
    fun wubiSingleCodePointCommitUsesTheSameTransactionalQuickDeleteWindow() {
        val window = PersonalizationFeedbackWindow(clock = { 1_000L })
        val candidate = Candidate(
            text = "输",
            score = 3f,
            matchKind = CandidateMatchKind.WUBI_EXACT,
            canonicalCode = "lwyy",
        )
        window.rememberWubi("lwyy", candidate, start = 4, endExclusive = 5)

        val target = window.complete(window.prepareQuickDelete(cursor = 5))

        assertEquals(PersonalizationLearningTarget.Wubi("lwyy", candidate), target)
    }

    private fun phrase(text: String) = LearnedPhrase(
        fullPinyin = "ni",
        initials = "n",
        text = text,
        useCount = 1,
        createdAtMillis = 1_000L,
        lastUsedAtMillis = 1_000L,
    )
}
