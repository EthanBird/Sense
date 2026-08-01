package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.core.AdaptivePinyinDecoder
import io.github.ethanbird.senseime.core.BinaryCharacterBigramModel
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.ChineseOnlyInputDecoder
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.T9Composition
import io.github.ethanbird.senseime.core.T9SyllableIndex
import io.github.ethanbird.senseime.core.WubiComposition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativeInputDecodingTest {
    @Test
    fun t9BootstrapFallbackDoesNotPublishLatinCandidate() {
        val composition = "22".fold(T9Composition()) { state, digit -> state.typeDigit(digit) }
        val decoder = AdaptivePinyinDecoder(
            base = FakeDecoder(),
            userLexicon = MemoryUserLexicon(),
            segmenter = PinyinSyllableSegmenter(setOf("ba")),
        )

        val result = AlternativeInputDecoder.decode(
            request(
                key = key(
                    ChineseInputScheme.PINYIN_T9,
                    composition.revision,
                    composition.rawDigits,
                ),
                composition = composition,
                index = T9SyllableIndex(listOf("ba")),
                decoder = decoder,
            ),
        )

        assertTrue(result.candidates.isEmpty())
        assertEquals("22 · ba", result.composingLabel)
    }

    @Test
    fun t9UsesChineseOnlySeamAndShowsResolvedSpelling() {
        val composition = "486".fold(T9Composition()) { state, digit -> state.typeDigit(digit) }
        val request = request(
            key = key(ChineseInputScheme.PINYIN_T9, composition.revision, composition.rawDigits),
            composition = composition,
            index = T9SyllableIndex(listOf("hun")),
        )

        val result = AlternativeInputDecoder.decode(request)

        assertEquals(listOf("浑"), result.candidates.map { it.text })
        assertEquals("486 · hun", result.composingLabel)
    }

    @Test
    fun productionT9RecoversHunShenXieShuFromDigitsAndDisplaysHybridJoints() {
        val assets = File("src/main/assets")
        val syllables = File(assets, "pinyin_syllables.txt").readLines()
        val bigrams = File(assets, "pinyin_bigrams.bin").inputStream().buffered()
            .use(BinaryCharacterBigramModel::load)
        val production = File(assets, "pinyin_lexicon.bin").inputStream().buffered()
            .use { PinyinDecoder.load(it, bigrams) }
        val chineseOnly = AdaptivePinyinDecoder(
            base = production,
            userLexicon = MemoryUserLexicon(),
            segmenter = PinyinSyllableSegmenter(syllables),
        )
        val composition = "486743697".fold(T9Composition()) { state, digit ->
            state.typeDigit(digit)
        }
        val result = AlternativeInputDecoder.decode(
            request(
                key = key(
                    ChineseInputScheme.PINYIN_T9,
                    composition.revision,
                    composition.rawDigits,
                ),
                composition = composition,
                index = T9SyllableIndex(syllables),
                decoder = chineseOnly,
                limit = 64,
            ),
        )

        assertEquals("浑身解数", result.candidates.firstOrNull()?.text)
        assertEquals("486743697 · hun'shen'x's", result.composingLabel)
        assertTrue(
            result.candidates.none { candidate ->
                candidate.text.isNotEmpty() && candidate.text.all { it in 'a'..'z' || it in 'A'..'Z' }
            },
        )
    }

    @Test
    fun sessionRejectsAResultFromAnOlderSchemeEpoch() {
        val composition = "486".fold(T9Composition()) { state, digit -> state.typeDigit(digit) }
        val original = request(
            key = key(ChineseInputScheme.PINYIN_T9, composition.revision, composition.rawDigits),
            composition = composition,
            index = T9SyllableIndex(listOf("hun")),
        )
        val session = AlternativeCandidateSession()
        session.begin(original.copy(key = original.key.copy(schemeEpoch = 2)))

        val stale = session.complete(
            original,
            AlternativeInputDecoder.decode(original),
            activePinyinGeneration = original.pinyinDecoderGeneration,
            activeWubiGeneration = original.wubiDecoderGeneration,
        )

        assertNull(stale)
    }

    @Test
    fun selectionUsesOpaquePresentationRevision() {
        val composition = "486".fold(T9Composition()) { state, digit -> state.typeDigit(digit) }
        val request = request(
            key = key(ChineseInputScheme.PINYIN_T9, composition.revision, composition.rawDigits),
            composition = composition,
            index = T9SyllableIndex(listOf("hun")),
        )
        val decoding = AlternativeInputDecoder.decode(request)
        val session = AlternativeCandidateSession()
        session.begin(request)
        session.complete(request, decoding, 1, 1)

        assertNull(session.select(998, 0))
        assertEquals("浑", session.select(999, 0)?.text)
    }

    @Test
    fun t9IgnoresUnrelatedWubiGenerationButRejectsPinyinReplacement() {
        val composition = "486".fold(T9Composition()) { state, digit -> state.typeDigit(digit) }
        val request = request(
            key = key(ChineseInputScheme.PINYIN_T9, composition.revision, composition.rawDigits),
            composition = composition,
            index = T9SyllableIndex(listOf("hun")),
        )
        val decoding = AlternativeInputDecoder.decode(request)

        assertNotNull(completed(request, decoding, activePinyin = 1, activeWubi = 2))
        assertNull(completed(request, decoding, activePinyin = 2, activeWubi = 1))
    }

    @Test
    fun directWubiDependsOnlyOnWubiGeneration() {
        val request = wubiRequest(reverse = false)
        val decoding = AlternativeDecoding(request.key, request.key.rawCode, emptyList(), emptyList())

        assertNotNull(completed(request, decoding, activePinyin = 2, activeWubi = 1))
        assertNull(completed(request, decoding, activePinyin = 1, activeWubi = 2))
    }

    @Test
    fun reverseWubiDependsOnBothDecoderGenerations() {
        val request = wubiRequest(reverse = true)
        val decoding = AlternativeDecoding(request.key, request.key.rawCode, emptyList(), emptyList())

        assertNotNull(completed(request, decoding, activePinyin = 1, activeWubi = 1))
        assertNull(completed(request, decoding, activePinyin = 2, activeWubi = 1))
        assertNull(completed(request, decoding, activePinyin = 1, activeWubi = 2))
    }

    private fun request(
        key: AlternativeCompositionKey,
        composition: T9Composition,
        index: T9SyllableIndex,
        decoder: InputDecoder = ChineseOnlyFixture,
        limit: Int = 8,
    ) = AlternativeDecodeRequest(
        key = key,
        t9Composition = composition,
        wubiComposition = null,
        t9Index = index,
        pinyinDecoder = decoder,
        pinyinDecoderGeneration = 1,
        wubiDecoder = null,
        wubiCandidateDecoder = null,
        wubiDecoderGeneration = 1,
        leftContext = "",
        limit = limit,
    )

    private fun key(scheme: ChineseInputScheme, revision: Long, raw: String) =
        AlternativeCompositionKey(scheme, 1, revision, 999, raw)

    private fun wubiRequest(reverse: Boolean): AlternativeDecodeRequest {
        val composition = if (reverse) {
            WubiComposition().type('z').type('n')
        } else {
            WubiComposition().type('a')
        }
        return AlternativeDecodeRequest(
            key = key(ChineseInputScheme.WUBI_86, composition.revision, composition.visibleCode),
            t9Composition = null,
            wubiComposition = composition,
            t9Index = T9SyllableIndex(listOf("ni")),
            pinyinDecoder = ChineseOnlyFixture,
            pinyinDecoderGeneration = 1,
            wubiDecoder = null,
            wubiCandidateDecoder = null,
            wubiDecoderGeneration = 1,
            leftContext = "",
            limit = 8,
        )
    }

    private fun completed(
        request: AlternativeDecodeRequest,
        decoding: AlternativeDecoding,
        activePinyin: Long,
        activeWubi: Long,
    ): AlternativePresentation? = AlternativeCandidateSession().let { session ->
        session.begin(request)
        session.complete(request, decoding, activePinyin, activeWubi)
    }

    private object ChineseOnlyFixture : ChineseOnlyInputDecoder {
        override fun decode(composing: String, limit: Int): List<Candidate> =
            listOf(Candidate("english"))

        override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> {
            assertTrue(composing.startsWith("hun"))
            return listOf(Candidate("浑", score = 10f, canonicalPinyin = "hun"))
        }
    }
}
