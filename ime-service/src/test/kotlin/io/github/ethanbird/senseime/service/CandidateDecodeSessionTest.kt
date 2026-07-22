package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AcceptedPinyinSegment
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateDecodeSessionTest {
    @Test
    fun repeatedRenderWhilePendingDoesNotLaunchOrCancelTheInFlightRevision() {
        val session = CandidateDecodeSession()
        val composition = PinyinComposition().type('h')

        val first = session.begin(composition)
        val repeated = session.begin(composition)

        assertTrue(first.shouldDecode)
        assertTrue(first.stateChanged)
        assertFalse(repeated.shouldDecode)
        assertFalse(repeated.stateChanged)
        assertTrue(repeated.presentation.pending)
    }

    @Test
    fun staleAsyncResultCannotReplaceTheNewestRevision() {
        val session = CandidateDecodeSession()
        val first = PinyinComposition().type('h')
        val second = first.type('u')

        assertTrue(session.begin(first).shouldDecode)
        assertTrue(session.begin(second).shouldDecode)
        assertNull(session.complete(first, decoding(first, "很"), limit = 255))
        assertTrue(session.current.pending)
        assertEquals(second, session.current.composition)

        val ready = session.complete(second, decoding(second, "胡"), limit = 255)
        assertEquals(listOf("胡"), ready?.snapshot?.candidates?.map { it.text })
        assertFalse(session.current.pending)
    }

    @Test
    fun enterStyleResetDropsAResultPostedForTheConfirmedComposition() {
        val session = CandidateDecodeSession()
        val typed = PinyinComposition().type('d')
        session.begin(typed)
        val reset = typed.reset()

        val resetLaunch = session.begin(reset)
        assertFalse(resetLaunch.shouldDecode)
        assertNull(session.complete(typed, decoding(typed, "的"), limit = 255))
        assertNull(session.currentDecoding(reset))
        assertTrue(session.current.snapshot.candidates.isEmpty())
    }

    @Test
    fun renderingTheSameRevisionRetainsItsAtomicReadySnapshot() {
        val session = CandidateDecodeSession()
        val composition = PinyinComposition().type('d')

        session.begin(composition)
        session.complete(composition, decoding(composition, "的", "地"), limit = 255)
        val repeated = session.begin(composition)

        assertFalse(repeated.shouldDecode)
        assertFalse(repeated.stateChanged)
        assertEquals(listOf("的", "地"), repeated.presentation.snapshot.candidates.map { it.text })
        assertFalse(repeated.presentation.pending)
    }

    @Test
    fun mismatchedPayloadAndStaleSelectionAreRejected() {
        val session = CandidateDecodeSession()
        val composition = PinyinComposition().type('d')
        session.begin(composition)

        val wrongPayload = ProgressivePinyinDecoding(
            revision = composition.revision,
            remainingPinyin = "de",
            wholeCandidates = listOf(Candidate("的")),
            prefixCandidates = emptyList(),
        )
        assertNull(session.complete(composition, wrongPayload, limit = 255))
        assertNull(session.select(composition, composition.revision, 0))

        session.complete(composition, decoding(composition, "的"), limit = 255)
        assertNull(session.select(composition.type('e'), composition.revision, 0))
        assertTrue(session.select(composition, composition.revision, 0) is ProgressiveCandidateChoice.Whole)
    }

    @Test
    fun sameTailAndRevisionCannotCrossAcceptedPrefixContexts() {
        val session = CandidateDecodeSession()
        val matchContext = PinyinComposition(
            acceptedSegments = listOf(AcceptedPinyinSegment("匹", "pi")),
            remainingPinyin = "pei",
            revision = 7,
        )
        val batchContext = matchContext.copy(
            acceptedSegments = listOf(AcceptedPinyinSegment("批", "pi")),
        )
        session.begin(matchContext)

        assertNull(session.complete(batchContext, decoding(batchContext, "配"), limit = 255))
        assertTrue(session.current.pending)
        assertEquals(matchContext, session.current.composition)
    }

    private fun decoding(
        composition: PinyinComposition,
        vararg values: String,
    ) = ProgressivePinyinDecoding(
        revision = composition.revision,
        remainingPinyin = composition.remainingPinyin,
        wholeCandidates = values.map { Candidate(it) },
        prefixCandidates = emptyList(),
    )
}
