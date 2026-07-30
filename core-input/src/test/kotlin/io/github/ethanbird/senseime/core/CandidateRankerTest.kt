package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateRankerTest {
    @Test
    fun canonicalExactAndAlternativeSourcesShareOneScoreDomain() {
        val values = CandidateRanker.rank(
            candidates = listOf(
                Candidate("可能", score = 11.5f, matchKind = CandidateMatchKind.BASE_HYBRID),
                Candidate("肯", score = 4.6f, canonicalPinyin = "ken", matchKind = CandidateMatchKind.BASE_EXACT),
                Candidate("啃", score = 4.3f, canonicalPinyin = "ken", matchKind = CandidateMatchKind.BASE_EXACT),
            ),
            limit = 8,
            hasCanonicalExact = true,
        )

        assertEquals(listOf("肯", "啃", "可能"), values.map { it.text })
    }

    @Test
    fun duplicateTextKeepsTheBestTypedEvidence() {
        val values = CandidateRanker.rank(
            candidates = listOf(
                Candidate("西安", score = 5f, matchKind = CandidateMatchKind.CORRECTED),
                Candidate("西安", score = 4.8f, matchKind = CandidateMatchKind.BASE_COMPOSED),
            ),
            limit = 8,
            hasCanonicalExact = false,
        )

        assertEquals(CandidateMatchKind.BASE_COMPOSED, values.single().matchKind)
    }

    @Test
    fun hybridTypedEvidenceOutranksANearbySpellingCorrection() {
        val values = CandidateRanker.rank(
            candidates = listOf(
                Candidate("corrected", score = 10f, matchKind = CandidateMatchKind.CORRECTED),
                Candidate("hybrid", score = 9.4f, matchKind = CandidateMatchKind.BASE_HYBRID),
            ),
            limit = 8,
            hasCanonicalExact = false,
        )

        assertEquals(listOf("hybrid", "corrected"), values.map { it.text })
    }

    @Test
    fun boundedAccumulatorLetsALateStrongerDuplicateReenterTheTopK() {
        val candidates = buildList {
            add(Candidate("late", score = -100f, matchKind = CandidateMatchKind.BASE_PREFIX))
            repeat(200) { index ->
                add(
                    Candidate(
                        text = "value-%03d".format(index),
                        score = index.toFloat(),
                        matchKind = CandidateMatchKind.BASE_PREFIX,
                    ),
                )
            }
            add(Candidate("late", score = 1_000f, matchKind = CandidateMatchKind.BASE_PREFIX))
        }

        val values = CandidateRanker.rank(
            candidates = candidates,
            limit = 5,
            hasCanonicalExact = false,
        )

        assertEquals(
            listOf("late", "value-199", "value-198", "value-197", "value-196"),
            values.map { it.text },
        )
    }
}
