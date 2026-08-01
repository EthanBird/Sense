package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.WubiComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WubiOverflowCoordinatorTest {
    @Test
    fun readyPolicyMatrixPreservesAllThreeAutoCommitMeanings() {
        val candidateSets = listOf(
            emptyList(),
            listOf(exact("唯一")),
            listOf(exact("首选"), exact("次选")),
        )
        val expected = mapOf(
            WubiAutoCommitMode.RIME_STYLE to listOf(
                WubiOverflowAction.Commit(null),
                WubiOverflowAction.Commit(exact("唯一")),
                WubiOverflowAction.Commit(exact("首选")),
            ),
            WubiAutoCommitMode.UNIQUE_AT_4 to listOf(
                WubiOverflowAction.Reject,
                WubiOverflowAction.Commit(exact("唯一")),
                WubiOverflowAction.Reject,
            ),
            WubiAutoCommitMode.OFF to List(3) { WubiOverflowAction.Reject },
        )

        WubiAutoCommitMode.entries.forEach { mode ->
            candidateSets.forEachIndexed { index, candidates ->
                assertEquals(
                    "$mode, exact count ${candidates.size}",
                    expected.getValue(mode)[index],
                    WubiOverflowCoordinator().onCharacter(
                        composition = compositionOf("abcd"),
                        character = 'e',
                        presentationRevision = 9L,
                        mode = mode,
                        decoding = decoding(*candidates.toTypedArray()),
                    ),
                )
            }
        }
    }

    @Test
    fun pendingPolicyMatrixReplaysOnlyWhenResolvedPolicyAllowsCommit() {
        val candidateSets = listOf(
            emptyList(),
            listOf(exact("唯一")),
            listOf(exact("首选"), exact("次选")),
        )
        val expected = mapOf(
            WubiAutoCommitMode.RIME_STYLE to listOf(
                WubiOverflowAction.Commit(null),
                WubiOverflowAction.Commit(exact("唯一")),
                WubiOverflowAction.Commit(exact("首选")),
            ),
            WubiAutoCommitMode.UNIQUE_AT_4 to listOf(
                WubiOverflowAction.Reject,
                WubiOverflowAction.Commit(exact("唯一")),
                WubiOverflowAction.Reject,
            ),
        )

        listOf(WubiAutoCommitMode.RIME_STYLE, WubiAutoCommitMode.UNIQUE_AT_4).forEach { mode ->
            candidateSets.forEachIndexed { index, candidates ->
                val coordinator = WubiOverflowCoordinator()
                assertEquals(
                    WubiOverflowAction.Await(41L),
                    coordinator.onCharacter(
                        compositionOf("abcd"),
                        'e',
                        41L,
                        mode,
                        decoding = null,
                    ),
                )
                assertSame(
                    WubiOverflowAction.Continue,
                    coordinator.onDecoded(40L, candidates),
                )
                assertEquals(
                    "$mode, exact count ${candidates.size}",
                    expected.getValue(mode)[index],
                    coordinator.onDecoded(41L, candidates),
                )
                assertSame(
                    WubiOverflowAction.Continue,
                    coordinator.onDecoded(41L, candidates),
                )
            }
        }
    }

    @Test
    fun offNeverStartsAWorkerWaitEvenWhenTheDecodeIsPending() {
        assertSame(
            WubiOverflowAction.Reject,
            WubiOverflowCoordinator().onCharacter(
                compositionOf("abcd"),
                'e',
                7L,
                WubiAutoCommitMode.OFF,
                decoding = null,
            ),
        )
    }

    @Test
    fun timeoutPreservesUniquePolicyAndOnlyRimeFallsBackToRawCommit() {
        val rime = WubiOverflowCoordinator()
        assertEquals(
            WubiOverflowAction.Await(17L),
            rime.onCharacter(
                compositionOf("abcd"),
                'e',
                17L,
                WubiAutoCommitMode.RIME_STYLE,
                decoding = null,
            ),
        )
        assertEquals(WubiOverflowAction.Commit(null), rime.onTimeout(17L))
        assertSame(WubiOverflowAction.Continue, rime.onDecoded(17L, listOf(exact("迟到"))))

        val unique = WubiOverflowCoordinator()
        assertEquals(
            WubiOverflowAction.Await(23L),
            unique.onCharacter(
                compositionOf("abcd"),
                'e',
                23L,
                WubiAutoCommitMode.UNIQUE_AT_4,
                decoding = null,
            ),
        )
        assertSame(WubiOverflowAction.Continue, unique.onTimeout(22L))
        assertSame(WubiOverflowAction.Reject, unique.onTimeout(23L))
        assertSame(WubiOverflowAction.Continue, unique.onDecoded(23L, listOf(exact("迟到"))))
    }

    @Test
    fun completionCandidatesNeverSatisfyRimeOrUniqueExactPolicy() {
        val completion = completion("补全")
        assertEquals(
            WubiOverflowAction.Commit(null),
            WubiOverflowCoordinator().onCharacter(
                compositionOf("abcd"),
                'e',
                1L,
                WubiAutoCommitMode.RIME_STYLE,
                decoding(completion),
            ),
        )
        assertSame(
            WubiOverflowAction.Reject,
            WubiOverflowCoordinator().onCharacter(
                compositionOf("abcd"),
                'e',
                1L,
                WubiAutoCommitMode.UNIQUE_AT_4,
                decoding(completion),
            ),
        )
    }

    @Test
    fun incompleteReverseAndNonLetterInputsDoNotStartOverflowTransaction() {
        val coordinator = WubiOverflowCoordinator()
        assertSame(
            WubiOverflowAction.Continue,
            coordinator.onCharacter(
                compositionOf("abc"),
                'd',
                1L,
                WubiAutoCommitMode.RIME_STYLE,
                null,
            ),
        )
        assertSame(
            WubiOverflowAction.Continue,
            coordinator.onCharacter(
                WubiComposition().type('z'),
                'a',
                2L,
                WubiAutoCommitMode.RIME_STYLE,
                null,
            ),
        )
        assertSame(
            WubiOverflowAction.Continue,
            coordinator.onCharacter(
                compositionOf("abcd"),
                'z',
                3L,
                WubiAutoCommitMode.RIME_STYLE,
                null,
            ),
        )
        assertSame(
            WubiOverflowAction.Continue,
            coordinator.onCharacter(
                compositionOf("abcd"),
                '1',
                4L,
                WubiAutoCommitMode.RIME_STYLE,
                null,
            ),
        )
    }

    @Test
    fun clearCancelsAWaitingOverflowTransaction() {
        val coordinator = WubiOverflowCoordinator()
        assertTrue(
            coordinator.onCharacter(
                compositionOf("abcd"),
                'e',
                7L,
                WubiAutoCommitMode.RIME_STYLE,
                null,
            ) is WubiOverflowAction.Await,
        )
        coordinator.clear()
        assertSame(
            WubiOverflowAction.Continue,
            coordinator.onDecoded(7L, listOf(exact("候选"))),
        )
    }

    private fun compositionOf(code: String): WubiComposition =
        code.fold(WubiComposition()) { state, character -> state.type(character) }

    private fun exact(text: String) = Candidate(
        text = text,
        score = 1f,
        matchKind = CandidateMatchKind.WUBI_EXACT,
        canonicalCode = "abcd",
    )

    private fun completion(text: String) = Candidate(
        text = text,
        score = 2f,
        matchKind = CandidateMatchKind.WUBI_COMPLETION,
        canonicalCode = "abcde",
    )

    private fun decoding(vararg candidates: Candidate) = AlternativeDecoding(
        key = AlternativeCompositionKey(
            scheme = ChineseInputScheme.WUBI_86,
            schemeEpoch = 1L,
            localRevision = 4L,
            presentationRevision = 9L,
            rawCode = "abcd",
        ),
        composingLabel = "abcd",
        candidates = candidates.toList(),
        candidateLabels = candidates.map(Candidate::text),
    )
}
