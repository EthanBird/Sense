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
    fun decoderHotSwapCanForceTheSameCompositionThroughPendingAgain() {
        val session = CandidateDecodeSession()
        val composition = PinyinComposition().type('d')
        session.begin(composition, decoderGeneration = 1)
        session.complete(
            composition,
            decoding(composition, "旧"),
            limit = 255,
            decoderGeneration = 1,
        )

        val restarted = session.begin(
            composition,
            decoderGeneration = 2,
            forceDecode = true,
        )

        assertTrue(restarted.shouldDecode)
        assertTrue(restarted.stateChanged)
        assertTrue(restarted.presentation.pending)
        assertEquals(listOf("旧"), restarted.presentation.snapshot.candidates.map { it.text })
        assertNull(session.select(composition, composition.revision, 0))
    }

    @Test
    fun decoderResultThatPostsAfterHotSwapCannotCompleteTheNewGeneration() {
        val session = CandidateDecodeSession()
        val composition = PinyinComposition().type('d')
        session.begin(composition, decoderGeneration = 1)
        val publication = session.begin(composition, decoderGeneration = 2)

        assertTrue(publication.shouldDecode)
        assertTrue(publication.stateChanged)
        assertNull(
            session.complete(
                composition,
                decoding(composition, "旧"),
                limit = 255,
                decoderGeneration = 1,
            ),
        )
        assertTrue(session.current.pending)
        assertNull(session.currentDecoding(composition, decoderGeneration = 2))

        val current = session.complete(
            composition,
            decoding(composition, "新"),
            limit = 255,
            decoderGeneration = 2,
        )
        assertEquals(listOf("新"), current?.snapshot?.candidates?.map { it.text })
    }

    @Test
    fun newRevisionRetainsOldVisualBatchButCannotSelectIt() {
        val session = CandidateDecodeSession()
        val first = PinyinComposition().type('h')
        session.begin(first)
        session.complete(first, decoding(first, "好", "和"), limit = 255)

        val second = first.type('a')
        val pending = session.begin(second)

        assertEquals(listOf("好", "和"), pending.presentation.snapshot.candidates.map { it.text })
        assertTrue(pending.presentation.pending)
        assertNull(session.currentDecoding(second))
        assertNull(session.select(second, second.revision, 0))

        val ready = session.complete(second, decoding(second, "哈"), limit = 255)
        assertEquals(listOf("哈"), ready?.snapshot?.candidates?.map { it.text })
    }

    @Test
    fun pendingCompositionCannotSelectRetainedSnapshotEvenWhenRevisionMatches() {
        val session = CandidateDecodeSession()
        val first = PinyinComposition(
            acceptedSegments = listOf(AcceptedPinyinSegment("匹", "pi")),
            remainingPinyin = "pei",
            revision = 7,
        )
        session.begin(first)
        session.complete(first, decoding(first, "配"), limit = 255)

        val second = first.copy(
            acceptedSegments = listOf(AcceptedPinyinSegment("批", "pi")),
        )
        val pending = session.begin(second)

        assertTrue(pending.presentation.pending)
        assertEquals(listOf("配"), pending.presentation.snapshot.candidates.map { it.text })
        assertNull(session.select(second, requestedRevision = 7, sourceIndex = 0))
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
