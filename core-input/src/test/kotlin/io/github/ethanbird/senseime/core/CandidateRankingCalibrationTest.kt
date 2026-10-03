package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CandidateRankingCalibrationTest {
    @Test fun validButImplausibleLiteralParsesDoNotBuryContextualRepairs() {
        val decoder = adaptive(base.withLanguageModel(model, .5f))
        for ((typed, expected) in listOf(
            "woobuxihuanzuoye" to "我不喜欢作业",
            "wobujieyizaiyuzongmanbu" to "我不介意在雨中漫步",
        )) {
            for (limit in listOf(10, 64, 255)) {
                val values = decoder.decodeProgressively(PinyinComposition(remainingPinyin = typed), "", limit).wholeCandidates
                assertEquals("$typed/$limit: ${values.take(3)}", expected, values.first().text)
                assertEquals(CandidateMatchKind.CORRECTED, values.first().matchKind)
            }
        }
    }

    @Test fun calibrationIsSpecificToLmCorrectionsWithCompositionButNoExactWord() {
        val scorer = PinyinLanguageScorer(PinyinLanguageBinding(model, .5f), "woobuxihuanzuoye", PinyinLanguageScorer.context(""))
        for (kind in CandidateMatchKind.entries) {
            for (exact in listOf(false, true)) for (composed in listOf(false, true)) {
                val expected = if (kind == CandidateMatchKind.CORRECTED && !exact && composed) 12f else 0f
                assertEquals(expected, scorer.sourceAdjustment(kind, exact, composed), 0f)
            }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 17f)) {
            assertThrows(IllegalArgumentException::class.java) { base.withCorrectionCalibration(value) }
        }
    }

    @Test fun exactWordsLegacyAndPersonalOnlyBackoffRemainUnchanged() {
        val calibrated = base.withLanguageModel(model, .5f)
        val old = calibrated.withCorrectionCalibration(0f)
        for (query in listOf("nihao", "zhongguo", "beijing")) {
            var exact = false
            val values = CandidateRankingDiagnostics.observe({ if (it.query == query) exact = it.exact }) {
                calibrated.decode(query, 255)
            }
            assertTrue("Fixture must be a complete dictionary entry: $query", exact)
            assertEquals(query, old.decode(query, 255), values)
        }
        assertEquals(base.decode("woobuxihuanzuoye", 255),
            base.withLanguageModel(model, 0f).decode("woobuxihuanzuoye", 255))
        val store = MemoryUserLexicon(clock = { 1_000L })
        store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        val a = adaptive(old, store)
        val b = adaptive(calibrated, store)
        assertEquals(a.decode("chengchexihuanzhinengti", 255), b.decode("chengchexihuanzhinengti", 255))
    }

    @Test fun completionReferenceOnlyRemovesTheExistingCompositionPriorGap() {
        val gap = CandidateRanker.sourcePrior(CandidateMatchKind.BASE_COMPOSED, false, true) -
            CandidateRanker.sourcePrior(CandidateMatchKind.BASE_PREFIX, false, true)
        for (boost in listOf(0f, 4f, 12f, 16f)) {
            val scorer = PinyinLanguageScorer(PinyinLanguageBinding(model, .5f, boost), "nime", PinyinLanguageScorer.context(""))
            for (kind in CandidateMatchKind.entries) for (exact in listOf(false, true)) for (composed in listOf(false, true)) {
                val expected = if (kind == CandidateMatchKind.CORRECTED && !exact && composed) (boost - gap).coerceAtLeast(0f) else 0f
                assertEquals(expected, scorer.sourceAdjustment(kind, exact, composed, literalCompletionLeads = true), 0f)
            }
        }
    }

    @Test fun aSecondRankingPassDoesNotApplyTheCalibrationAgainAndDiagnosticsLeaveNoObserver() {
        val decoder = base.withLanguageModel(model, .5f)
        val typed = "woobuxihuanzuoye"
        var trace: CandidateRankingDiagnostics.Trace? = null
        val values = CandidateRankingDiagnostics.observe({ if (it.query == typed) trace = it }) {
            decoder.decode(typed, 255)
        }
        val captured = requireNotNull(trace)
        assertEquals(values, CandidateRanker.rank(values, 255, captured.exact, captured.composed))
        var count = 0
        assertTrue(runCatching {
            CandidateRankingDiagnostics.observe({ count++ }) {
                decoder.decode(typed, 255)
                error("fixture")
            }
        }.isFailure)
        val observed = count
        assertEquals(values, decoder.decode(typed, 255))
        assertEquals(observed, count)
    }

    private fun adaptive(decoder: PinyinDecoder, store: UserLexicon = MemoryUserLexicon()) =
        AdaptivePinyinDecoder(decoder, store, PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))

    companion object {
        private fun asset(name: String): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "ime-service/src/main/assets/$name") }.first { it.isFile }
        private val model by lazy { asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load) }
        private val base by lazy { asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) } }
    }
}
