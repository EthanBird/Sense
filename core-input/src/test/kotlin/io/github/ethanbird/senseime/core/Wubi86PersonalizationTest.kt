package io.github.ethanbird.senseime.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Wubi86PersonalizationTest {
    @Test
    fun selectionEvidenceHasExplicitBoundedStrengthTiers() {
        val default = WubiLearningEvidence.DEFAULT_ACCEPT.strength
        val completion = WubiLearningEvidence.COMPLETION_SELECTION.strength
        val explicit = WubiLearningEvidence(
            WubiSelectionKind.EXPLICIT_SELECTION,
            selectedRank = 7,
        ).strength
        val deepest = WubiLearningEvidence(
            WubiSelectionKind.EXPLICIT_SELECTION,
            selectedRank = Int.MAX_VALUE,
        ).strength

        assertTrue(completion > default)
        assertTrue(explicit > completion)
        assertTrue(deepest >= explicit)
        assertTrue(deepest <= WubiLearningEvidence.MAX_EVENT_STRENGTH)
    }

    @Test
    fun canonicalLearningIsNormalizedIndexedByEveryPrefixAndSchemeIsolated() {
        val lexicon = MemoryWubiUserLexicon(clock = { 1_000L })

        lexicon.record("ABCD", "甲")
        lexicon.record("bcde", "甲")

        listOf("a", "ab", "abc", "abcd").forEach { prefix ->
            assertEquals(listOf("abcd"), lexicon.lookup(prefix, 8).map { it.canonicalCode })
        }
        assertEquals(listOf("bcde"), lexicon.lookup("b", 8).map { it.canonicalCode })
        assertTrue(lexicon.lookup("ac", 8).isEmpty())

        captureFailure<IllegalArgumentException> { lexicon.record("z", "非法") }
        captureFailure<IllegalArgumentException> { lexicon.record("abcde", "过长") }
        captureFailure<IllegalArgumentException> { lexicon.record("n'i", "分隔符") }
    }

    @Test
    fun capacityEvictionDeletesTheRecordAndEveryDerivedPrefixIndex() {
        var now = 1_000L
        val mutations = mutableListOf<WubiUserLexiconMutation>()
        val lexicon = MemoryWubiUserLexicon(
            clock = { now++ },
            onMutation = mutations::add,
            limits = WubiUserLexiconLimits(
                maximumRecords = 8,
                maximumRecordsPerCanonicalCode = 1,
                maximumIndexedRecordsPerPrefix = 2,
            ),
        )
        lexicon.record("abcd", "弱", WubiLearningEvidence.DEFAULT_ACCEPT)
        lexicon.record("abcd", "强", WubiLearningEvidence.EXPLICIT_SELECTION)

        listOf("a", "ab", "abc", "abcd").forEach { prefix ->
            assertEquals(listOf("强"), lexicon.lookup(prefix, 8).map { it.text })
        }
        assertTrue(
            mutations.contains(WubiUserLexiconMutation.Delete("abcd", "弱")),
        )

        val mutationCount = mutations.size
        lexicon.lookup("a", 8)
        assertEquals("Lookup must not perform durable I/O", mutationCount, mutations.size)
    }

    @Test
    fun oneExplicitDeepSelectionOverridesAnExtremeStaticWeightGapWithinTheSameCode() {
        val lexicon = MemoryWubiUserLexicon(clock = { 1_000L })
        val personalizer = Wubi86CandidatePersonalizer(lexicon)
        val candidates = listOf(
            wubiCandidate("高频", score = 21.66f, code = "abcd"),
            wubiCandidate("想要", score = 0f, code = "abcd"),
        )

        assertEquals(listOf("高频", "想要"), personalizer.rank("abcd", candidates, 2).map { it.text })
        assertNotNull(
            personalizer.recordSelection(
                "abcd",
                candidates[1],
                WubiLearningEvidence(WubiSelectionKind.EXPLICIT_SELECTION, selectedRank = 31),
            ),
        )

        assertEquals(listOf("想要", "高频"), personalizer.rank("abcd", candidates, 2).map { it.text })
    }

    @Test
    fun exactCandidatesRemainAheadOfCompletionsAfterCompletionLearning() {
        val lexicon = MemoryWubiUserLexicon(clock = { 1_000L })
        val personalizer = Wubi86CandidatePersonalizer(lexicon)
        val exact = wubiCandidate("精确", score = -100f, code = "ab")
        val completion = wubiCandidate(
            "补全",
            score = 100f,
            code = "abcd",
            matchKind = CandidateMatchKind.WUBI_COMPLETION,
        )
        personalizer.recordSelection(
            "ab",
            completion,
            WubiLearningEvidence(WubiSelectionKind.COMPLETION_SELECTION, selectedRank = 12),
        )

        assertEquals(
            listOf("精确", "补全"),
            personalizer.rank("ab", listOf(exact, completion), 2).map { it.text },
        )
        assertEquals("补全", lexicon.lookup("abcd", 1).single().text)
    }

    @Test
    fun quickDeleteDemotesAndReloadPreservesTheSameEvidenceResult() {
        val lexicon = MemoryWubiUserLexicon(clock = { 1_000L })
        val personalizer = Wubi86CandidatePersonalizer(lexicon)
        val primary = wubiCandidate("常用", score = 10f, code = "abcd")
        val selected = wubiCandidate("误选", score = 9f, code = "abcd")
        val candidates = listOf(primary, selected)
        personalizer.recordSelection("abcd", selected, WubiLearningEvidence.EXPLICIT_SELECTION)
        assertEquals("误选", personalizer.rank("abcd", candidates, 2).first().text)

        personalizer.demote("abcd", selected, WubiNegativeFeedback.QUICK_DELETE)
        assertEquals("常用", personalizer.rank("abcd", candidates, 2).first().text)

        val durableSnapshot = lexicon.lookup("abcd", 8)
        val reloaded = MemoryWubiUserLexicon(initial = durableSnapshot, clock = { 1_000L })
        assertEquals(
            lexicon.lookup("abcd", 8).map { it.text to it.rankingBoost },
            reloaded.lookup("abcd", 8).map { it.text to it.rankingBoost },
        )
    }

    @Test
    fun staleRowsNeverInjectCandidatesThatAreAbsentFromTheCurrentBaseLexicon() {
        val lexicon = MemoryWubiUserLexicon(clock = { 1_000L })
        lexicon.record("abcd", "旧词")
        val current = listOf(wubiCandidate("现词", score = 1f, code = "abcd"))

        assertEquals(
            listOf("现词"),
            Wubi86CandidatePersonalizer(lexicon).rank("abcd", current, 8).map { it.text },
        )
    }

    @Test
    fun adaptiveDecoderUsesABoundedExpandedPoolForPreviouslyDeepSelections() {
        val candidates = (0 until 24).map { index ->
            wubiCandidate("候选$index", score = (24 - index).toFloat(), code = "abcd")
        }
        var requestedLimit = 0
        val base = object : InputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> {
                requestedLimit = limit
                return candidates.take(limit)
            }
        }
        val decoder = AdaptiveWubi86Decoder(
            baseDecoder = base,
            userLexicon = MemoryWubiUserLexicon(clock = { 1_000L }),
            preferredCandidatePoolSize = 32,
        )
        decoder.learn(
            "abcd",
            candidates[10],
            WubiLearningEvidence(WubiSelectionKind.EXPLICIT_SELECTION, selectedRank = 10),
        )

        assertEquals("候选10", decoder.decode("abcd", 1).single().text)
        assertTrue(requestedLimit in 16..32)
    }

    @Test
    fun concurrentRecordAndLookupKeepIndexesConsistentAndBounded() {
        val lexicon = MemoryWubiUserLexicon(
            clock = { 1_000L },
            limits = WubiUserLexiconLimits(
                maximumRecords = 64,
                maximumRecordsPerCanonicalCode = 16,
                maximumIndexedRecordsPerPrefix = 24,
            ),
        )
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until 4).map { worker ->
            Thread {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(100) { index ->
                        val suffix = ('a'.code + ((worker * 100 + index) % 20)).toChar()
                        val code = "ab$suffix"
                        lexicon.record(code, "词$worker-$index")
                        lexicon.lookup("a", 8)
                    }
                } catch (error: Throwable) {
                    failures += error
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(5_000L) }

        assertTrue(failures.toList().joinToString(), failures.isEmpty())
        assertTrue(lexicon.lookup("a", 1_000).size <= 24)
        assertFalse(threads.any(Thread::isAlive))
    }

    private fun wubiCandidate(
        text: String,
        score: Float,
        code: String,
        matchKind: CandidateMatchKind = CandidateMatchKind.WUBI_EXACT,
    ): Candidate = Candidate(
        text = text,
        score = score,
        matchKind = matchKind,
        canonicalCode = code,
    )

    private inline fun <reified T : Throwable> captureFailure(block: () -> Unit): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }
}
