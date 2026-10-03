package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CorrectionProbeLanguageTest {
    @Test fun missingKeySpellingsReachTheSentenceBeamUsingLanguageEvidence() {
        val decoder = decoder()
        for ((typed, text) in listOf("wbuxihuanzuoye" to "我不喜欢作业",
            "wobuxhuanzuoye" to "我不喜欢作业", "zhexieshiwomendshuzhuo" to "这些是我们的书桌")) {
            val values = decoder.decodeProgressively(PinyinComposition(remainingPinyin = typed), "", 255).wholeCandidates
            assertEquals("$typed: ${values.take(5)}", text, values.firstOrNull()?.text)
            assertEquals(CandidateMatchKind.CORRECTED, values.first().matchKind)
        }
    }

    @Test fun diagnosticScopeRestoresAfterExceptionsAndNeverChangesCandidates() {
        val decoder = decoder()
        val input = PinyinComposition(remainingPinyin = "wobuxhuanzuoye")
        val plain = decoder.decodeProgressively(input, "", 255)
        val traces = mutableListOf<CorrectionSearchDiagnostics.Trace>()
        assertEquals(plain, CorrectionSearchDiagnostics.observe(traces::add) { decoder.decodeProgressively(input,"",255) })
        assertTrue(traces.any { it.query == input.remainingPinyin && it.selected.any { path -> path.canonical == "wobuxihuanzuoye" } })
        assertTrue(runCatching { CorrectionSearchDiagnostics.observe(traces::add) { error("fixture") } }.isFailure)
        val count = traces.size
        assertEquals(plain, decoder.decodeProgressively(input,"",255))
        assertEquals(count, traces.size)
    }

    @Test fun correctedCompositionReadsPersonalEvidenceAndContextAgainOnTheNextQuery() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        val reused = decoder(store)
        val input = PinyinComposition(remainingPinyin = "wobuxhuanzuoye")
        fun score() = reused.decodeProgressively(input, "", 255).wholeCandidates
            .first { it.text == "我不喜欢作业" }.score
        val before = score()
        store.record("zuoye", "zy", "作业", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        val learned = score()
        assertTrue("Personal edge evidence should still affect corrected sentences", learned > before)
        store.demote("zuoye", "作业", UserNegativeFeedback.IMMEDIATE_REPLACEMENT)
        assertTrue(score() < learned)
        for (context in listOf("", "他说", "明天", "hello ")) {
            for (limit in listOf(10, 255)) {
                assertEquals(decoder(store).decodeProgressively(input, context, limit),
                    reused.decodeProgressively(input, context, limit))
            }
        }
    }

    @Test fun aGraphRetainedMissingKeyRouteGetsAFullBeamBesideCheaperErrorChannels() {
        val traces = mutableListOf<CorrectionSearchDiagnostics.Trace>()
        val typed = "bixucaiquyiiexingdongle"
        val canonical = "bixucaiquyixiexingdongle"
        val result = CorrectionSearchDiagnostics.observe(traces::add) {
            decoder().decodeProgressively(PinyinComposition(remainingPinyin = typed), "", 255)
        }
        assertTrue(traces.any { it.query == typed && it.paths.any { p -> p.canonical == canonical } })
        assertTrue("Graph recall alone is not visible recall: " + traces.filter { it.query == typed }.map {
            "single gaps=${it.probes.filter { p -> p.singleInsertionDeletion }}; selected=${it.selected}"
        },
            result.wholeCandidates.any { it.text == "必须采取一些行动了" })
    }

    @Test fun channelReservationsNeverDisplaceTheBestScoutedPath() {
        for (limit in listOf(1, 10, 255)) {
          for (typed in listOf("womenqugongyuanpaizhaope", "bixucaiquyiiexingdongle", "nihaoshijiee", "woaini")) {
            val traces = mutableListOf<CorrectionSearchDiagnostics.Trace>()
            CorrectionSearchDiagnostics.observe(traces::add) {
                decoder().decodeProgressively(PinyinComposition(remainingPinyin = typed), "", limit)
            }
            val trace = traces.first { it.query == typed && it.probes.isNotEmpty() }
            assertTrue("$typed/$limit: best scout ${trace.probes.first()} displaced by ${trace.selected}",
                trace.probes.first() in trace.selected)
            assertTrue(trace.selected.size <= if (typed == "woaini" && limit < 64) 1 else 4)
          }
        }
    }

    private fun decoder(store: UserLexicon = MemoryUserLexicon()) = AdaptivePinyinDecoder(base.withLanguageModel(model,.5f), store,
        PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()), asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))

    companion object {
        private fun asset(name: String): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it,"ime-service/src/main/assets/$name") }.first { it.isFile }
        private val base by lazy { asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) } }
        private val model by lazy { asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load) }
    }
}
