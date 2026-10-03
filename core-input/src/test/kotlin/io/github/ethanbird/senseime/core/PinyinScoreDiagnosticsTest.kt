package io.github.ethanbird.senseime.core

import io.github.ethanbird.senseime.core.PinyinScoreDiagnostics.Feature
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class PinyinScoreDiagnosticsTest {
    @Test fun exactFrequencyTierAndSourcePriorAreSeparateActualTerms() {
        val decoder = fixture()
        val trace = capture { decoder.decodePrefixProbe("zhu", 255) }.single()
        val fallback = trace.entries.single { it.candidate.text == "株" && it.candidate.matchKind == CandidateMatchKind.BASE_EXACT }
        assertEquals(ln(101.0).toFloat().toDouble(), fallback.evidence[Feature.LOG_FREQUENCY], 0.0)
        assertEquals(-1.0, fallback.evidence[Feature.FALLBACK_TIER], 0.0)
        assertEquals(8f, fallback.sourcePrior, 0f)
        assertEquals(listOf(PinyinScoreDiagnostics.LexicalEdge("株", "zhu", 100L, 1)), fallback.evidence.edges)
        assertEquals(fallback.candidate.score + 8f, fallback.total, 0f)
        audit(trace)
    }

    @Test fun composedPathOwnsItsEdgesAndDoesNotCountSearchContextOrLmTwice() {
        val decoder = fixture().withLanguageModel(constantLm, .5f)
        val trace = capture { decoder.decodeWithContext("我", "nihao", 255, true) }.single()
        val entry = trace.entries.single { it.candidate.text == "你好" && it.candidate.matchKind == CandidateMatchKind.BASE_COMPOSED }
        val e = entry.evidence
        assertEquals(listOf("你", "好"), e.edges.map { it.text })
        assertEquals(2, e.segments)
        assertEquals(1f, e.normalizer, 0f)
        assertEquals(ln(1001.0).toFloat().toDouble() + ln(2001.0).toFloat(), e[Feature.LOG_FREQUENCY], 0.0)
        assertEquals(2.0, e[Feature.INTERNAL_BIGRAM], 0.0)
        assertEquals(-.65f.toDouble(), e[Feature.WORD_BOUNDARY], 0.0)
        assertEquals(3.0, e[Feature.EXTERNAL_BIGRAM], 0.0) // clipped from fixture's +10
        assertEquals(1.0, e[Feature.CHARACTER_LM], 0.0) // 2 glyphs * (−5 + 6) * .5
        assertEquals(-2 * e[Feature.NORMALIZATION_ANCHOR], e[Feature.UNIGRAM_MASS], 0.0)
        audit(trace)
    }

    @Test fun normalizationIsSharedAndAppliedToEveryLexicalComponentNotExternalEvidence() {
        val decoder = fixture().withLanguageModel(constantLm, .5f)
        val trace = capture { decoder.decodeWithContext("我", "nihaonihao", 255, true) }.single()
        val e = trace.entries.first { it.candidate.text == "你好你好" }.evidence
        val n = 10f / 6f
        assertEquals(n, e.normalizer, 0f)
        assertEquals(4, e.segments)
        assertEquals(6.0 / n, e[Feature.INTERNAL_BIGRAM], .000001)
        assertEquals(3.0, e[Feature.EXTERNAL_BIGRAM], 0.0)
        assertEquals((2f / n).toDouble(), e[Feature.CHARACTER_LM], 0.0)
        audit(trace)
    }

    @Test fun initialsCompletionHybridAndSpellingCostsKeepNamedProvenance() {
        val traces = listOf("nh", "nia", "niha", "nihoa").flatMap { query -> capture { fixture().decode(query, 255) } }
        val entries = traces.flatMap { it.entries }
        assertTrue(entries.any { it.evidence[Feature.INITIALS_LENGTH] > 0 })
        assertTrue(entries.any { it.evidence[Feature.COMPLETION] < 0 })
        assertTrue(entries.any { it.candidate.matchKind == CandidateMatchKind.BASE_HYBRID })
        assertTrue(entries.any { it.evidence[Feature.SPELLING] < 0 })
        traces.forEach(::audit)
    }

    @Test fun personalFloorAndNegativeFeedbackAreNotRelabeledAsCorpusFrequency() {
        val session = PinyinScoreDiagnostics.Session()
        val original = Candidate("词", 2f)
        session.lexical(original, "ci", 6, 0, 2f, 0f)
        val boosted = original.copy(score = 5f, matchKind = CandidateMatchKind.USER_FULL)
        session.personal(original, boosted, 4f, 1f)
        assertEquals(2.0, session.get(boosted)[Feature.LOG_FREQUENCY], 0.0)
        assertEquals(2.0, session.get(boosted)[Feature.PERSONAL_FLOOR], 0.0)
        assertEquals(1.0, session.get(boosted)[Feature.PERSONAL_ADJUSTMENT], 0.0)
        val down = original.copy(score = 1f)
        session.add(original, down, Feature.PERSONAL_ADJUSTMENT, -1f)
        assertEquals(-1.0, session.get(down)[Feature.PERSONAL_ADJUSTMENT], 0.0)
        val fresh = Candidate("程彻", 4f, "chengche", CandidateMatchKind.USER_FULL)
        session.personal(null, fresh, 3f, 1f)
        assertEquals(0.0, session.get(fresh)[Feature.LOG_FREQUENCY], 0.0)
        assertEquals(3.0, session.get(fresh)[Feature.PERSONAL_BASE], 0.0)
        assertNull(session.get(fresh).edges.single().weight)
    }

    @Test fun productionSourcesCorrectionsJointsAndLearnedSentenceKeepCompleteResults() {
        val personal = MemoryUserLexicon(clock = { 1000L })
        personal.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        val configurations = listOf(base, base.withLanguageModel(model, .5f, -4f),
            base.withLanguageModel(model, .5f, -4f).withUserLexicon(personal))
        val queries = listOf("nihao", "zhinengti", "kua'huihua", "hun'shenxs", "yisscp",
            "woobuxihuanzuoye", "chengchexihuanzhinengti", "le", "xian")
        val seen = mutableSetOf<CandidateMatchKind>()
        var calibrated = false
        for (decoder in configurations) for (query in queries) {
            val before = decoder.decodeWithContext("我很", query, 255, false)
            var observed: List<Candidate>? = null
            val traces = capture { observed = decoder.decodeWithContext("我很", query, 255, false) }
            assertEquals(query, before, observed)
            traces.forEach { trace ->
                audit(trace)
                trace.entries.forEach { seen += it.candidate.matchKind }
                calibrated = calibrated || trace.entries.any { it.evidence[Feature.CORRECTION_CALIBRATION] == 12.0 }
            }
            assertEquals(query, before, decoder.decodeWithContext("我很", query, 255, false))
        }
        assertTrue(seen.containsAll(listOf(CandidateMatchKind.BASE_EXACT, CandidateMatchKind.BASE_COMPOSED,
            CandidateMatchKind.BASE_PREFIX, CandidateMatchKind.BASE_HYBRID, CandidateMatchKind.CORRECTED)))
        assertTrue(calibrated)
    }

    @Test fun warmPrefixCacheIsBypassedForEvidenceAndResultsRemainEqual() {
        val decoder = base.withLanguageModel(model, .5f, -4f)
        for (context in listOf("", "我很", "我很。", "属于", "𠀀我")) {
            val expected = decoder.decodeWithContext(context, "lei", 255, true)
            repeat(2) {
                val traces = capture { assertEquals(expected, decoder.decodeWithContext(context, "lei", 255, true)) }
                assertEquals(1, traces.size)
                assertEquals("prefix", traces.single().seam)
                audit(traces.single())
            }
            assertEquals(expected, decoder.decodeWithContext(context, "lei", 255, true))
        }
    }

    @Test fun progressiveAcceptedPrefixAndLexicalSeamAreAlsoCovered() {
        val decoder = AdaptivePinyinDecoder(base.withLanguageModel(model, .5f, -4f), MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val composition = PinyinComposition(listOf(AcceptedPinyinSegment("很", "hen")), "lei", 19)
        val before = decoder.decodeProgressively(composition, "我", 255)
        val traces = capture { assertEquals(before, decoder.decodeProgressively(composition, "我", 255)) }
        assertTrue(traces.isNotEmpty())
        traces.forEach(::audit)
        val lexicalBefore = base.probeCanonicalChineseOnlyAfter('我'.code, "nihao", 255)
        val lexical = capture { assertEquals(lexicalBefore, base.probeCanonicalChineseOnlyAfter('我'.code, "nihao", 255)) }
        assertEquals("lexical", lexical.single().seam)
        audit(lexical.single())
    }

    @Test fun nestedObserversExceptionsCancellationAndThreadsReleaseTheirSessions() {
        val decoder = fixture()
        var outer = 0; var inner = 0
        assertFalse(PinyinScoreDiagnostics.isObserving)
        PinyinScoreDiagnostics.observe({ outer++ }) {
            decoder.decodePrefixProbe("nihao", 5)
            PinyinScoreDiagnostics.observe({ inner++ }) { decoder.decodePrefixProbe("nihao", 5) }
            val failure = AtomicReference<Throwable?>()
            Thread {
                try {
                    assertFalse(PinyinScoreDiagnostics.isObserving)
                    decoder.decodePrefixProbe("nihao", 5)
                } catch (e: Throwable) { failure.set(e) }
            }.apply { start(); join() }
            failure.get()?.let { throw it }
            assertThrows(IllegalStateException::class.java) {
                PinyinScoreDiagnostics.observe({ error("observer fixture") }) { decoder.decodePrefixProbe("nihao", 5) }
            }
            var checkpoints = 0
            assertThrows(DecodeSupersededException::class.java) {
                DecodeWorkScope.whileCurrent({ ++checkpoints < 3 }) { decoder.decode("nihaonihao", 255) }
            }
            assertNull(PinyinScoreDiagnostics.current)
            decoder.decodePrefixProbe("nihao", 5)
        }
        assertEquals(2, outer); assertEquals(1, inner)
        assertFalse(PinyinScoreDiagnostics.isObserving)
        assertNull(PinyinScoreDiagnostics.current)
        decoder.decode("nihao", 5)
        assertEquals(2, outer)
    }

    @Test fun missingEvidenceAndMixedScoresFailRatherThanExportFabricatedFeatures() {
        val session = PinyinScoreDiagnostics.Session()
        assertThrows(IllegalStateException::class.java) { session.get(Candidate("你", 10f)) }
        assertThrows(IllegalStateException::class.java) { session.put(Candidate("你", 10f), PinyinScoreDiagnostics.Evidence.EMPTY) }
        assertEquals(0.0, PinyinScoreDiagnostics.Evidence.EMPTY.sum(), 0.0)
    }

    @Test fun rankerMetadataInheritanceKeepsTheWinningScorePathNotTheMetadataDonor() {
        PinyinScoreDiagnostics.observe({ error("no decoder invocation in this test") }) {
            PinyinScoreDiagnostics.query {
                val session = checkNotNull(PinyinScoreDiagnostics.current)
                val high = Candidate("你", 5f)
                val donor = Candidate("你", 1f, "ni")
                session.lexical(high, "ni", 147, 0, 5f, 0f)
                session.lexical(donor, "ni", 2, 0, 1f, 0f)
                val winner = CandidateRanker.rank(listOf(high, donor), 5, true).single()
                assertEquals("ni", winner.canonicalPinyin)
                assertEquals(5f, winner.score, 0f)
                assertEquals(147L, session.get(winner).edges.single().weight)
            }
        }
    }

    @Test fun learnedNameInsideActualSentenceExportsPersonalEvidenceAndRespectsLmBackoff() {
        val store = MemoryUserLexicon(clock = { 1000L })
        store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        val decoder = base.withLanguageModel(model, .5f, -4f).withUserLexicon(store)
        val trace = capture { decoder.decodePrefixProbe("chengchexihuanzhinengti", 255) }.single()
        val path = trace.entries.first { it.candidate.text == "程彻喜欢智能体" }
        assertTrue(path.evidence[Feature.PERSONAL_ADJUSTMENT] > 0)
        assertEquals(0.0, path.evidence[Feature.CHARACTER_LM], 0.0)
        assertTrue(path.evidence.edges.any { it.text == "程彻" })
        audit(trace)
    }

    @Test fun closingAnotherThreadsObserverDoesNotDisableTheRemainingScope() {
        val ready = CountDownLatch(1); val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val child = Thread {
            try {
                PinyinScoreDiagnostics.observe({}) {
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                assertFalse(PinyinScoreDiagnostics.isObserving)
            } catch (e: Throwable) { failure.set(e) }
        }
        child.start()
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            var count = 0
            PinyinScoreDiagnostics.observe({ count++ }) {
                release.countDown(); child.join(10_000)
                assertFalse(child.isAlive)
                failure.get()?.let { throw it }
                assertTrue(PinyinScoreDiagnostics.isObserving)
                fixture().decodePrefixProbe("nihao", 5)
            }
            assertEquals(1, count)
            assertFalse(PinyinScoreDiagnostics.isObserving)
        } finally { release.countDown(); child.join(10_000) }
    }

    private fun capture(block: () -> Unit): List<PinyinScoreDiagnostics.Trace> {
        val traces = mutableListOf<PinyinScoreDiagnostics.Trace>()
        PinyinScoreDiagnostics.observe({ traces += it }, block)
        return traces
    }
    private fun audit(trace: PinyinScoreDiagnostics.Trace) {
        assertTrue(trace.entries.isNotEmpty())
        trace.entries.forEach {
            assertEquals(it.candidate.score.toDouble(), it.evidence.sum(), .0002)
            assertEquals(it.candidate.text, it.evidence.edges.joinToString("") { edge -> edge.text })
        }
        assertEquals(trace.ranked, CandidateRanker.rank(trace.entries.map { it.candidate }, trace.ranked.size,
            trace.exact, trace.composed))
        trace.ranked.forEach { winner ->
            assertTrue(trace.entries.any { it.candidate.text == winner.text && it.candidate.score == winner.score &&
                it.candidate.matchKind == winner.matchKind })
        }
    }

    private fun fixture(): PinyinDecoder {
        data class Word(val text: String, val weight: Int, val initials: String, val tier: Int = 0)
        val records = mapOf("ni" to listOf(Word("你", 1000, "n")), "hao" to listOf(Word("好", 2000, "h")),
            "nihaoma" to listOf(Word("你好吗", 300, "nhm")), "ma" to listOf(Word("吗", 100, "m")),
            "zhu" to listOf(Word("主", 200, "z"), Word("株", 100, "z", 1)),
            "~nh" to listOf(Word("你好", 200, "nh")), "}nia|nihao" to listOf(Word("你好", 200, "nh")))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(records.size)
            records.toSortedMap().forEach { (code, words) ->
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(words.size)
                words.forEach {
                    val text = it.text.toByteArray(Charsets.UTF_8)
                    out.writeByte(text.size); out.write(text); out.writeInt(it.weight)
                    out.writeByte(it.initials.length); out.writeBytes(it.initials); out.writeByte(it.tier)
                }
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray(), CharacterBigramModel { previous, _ -> if (previous == '我'.code) 10f else 2f })
    }
    companion object {
        private fun asset(name: String): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "ime-service/src/main/assets/$name") }.first { it.isFile }
        private val model by lazy { asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load) }
        private val base by lazy { asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) } }
        private val constantLm = object : CharacterLanguageModel {
            override fun logProbability(previous2: Int, previous1: Int, next: Int) = -5f
            override fun unigramLogProbability(next: Int) = -5f
        }
    }
}
