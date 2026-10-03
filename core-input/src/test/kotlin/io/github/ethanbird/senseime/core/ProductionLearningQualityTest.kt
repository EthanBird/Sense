package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Production assets + actual adaptive decoder; cache hits must never freeze personal ranking. */
class ProductionLearningQualityTest {
    @Test
    fun acceptingLearnedNameWithSpaceDoesNotEraseTheEarlierExplicitPreference() {
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val store = MemoryUserLexicon(clock = { 1_000L }, onRecord = { journal[it.fullPinyin to it.text] = it })
        val adaptive = AdaptivePinyinDecoder(base(), store, segmenter())
        adaptive.learn("chengche", Candidate("程彻", canonicalPinyin = "chengche", canonicalInitials = "cc"),
            UserLearningEvidence(UserSelectionKind.COMPOSED_CONFIRM, selectedRank = 8))
        repeat(4) {
            val candidate = adaptive.decode("chengche", 255).first()
            assertEquals("The next confirmation must not undo earlier manual selection, iteration=$it", "程彻", candidate.text)
            adaptive.learn("chengche", candidate, UserLearningEvidence.DEFAULT_ACCEPT)
        }
        val restored = AdaptivePinyinDecoder(base(), MemoryUserLexicon(initial = journal.values, clock = { 1_000L }), segmenter())
        assertEquals("程彻", restored.decode("chengche", 255).first().text)
    }

    @Test
    fun constrainedT9RetainsLexicalProvenanceAcrossNumericSpellings() {
        val adaptive = AdaptivePinyinDecoder(base(), MemoryUserLexicon(), segmenter())
        val input = "426".fold("64".fold(T9Composition()) { state, c -> state.typeDigit(c) }.forceJoint()) { state, c -> state.typeDigit(c) }
        val index = T9SyllableIndex(file("pinyin_syllables.txt").readLines())
        val result = T9AlternativeInputDecoder.decode(input, index, adaptive, "", 64)
        assertEquals(result.candidates.take(3).toString(), "你好", result.candidates.first().text)
        assertEquals(CandidateMatchKind.BASE_EXACT, result.candidates.first().matchKind)
    }

    @Test
    fun learnedWordsParticipateAtTheBeginningMiddleAndEndOfNewSentences() {
        val adaptive = AdaptivePinyinDecoder(base(), MemoryUserLexicon(clock = { 1_000L }), segmenter())
        adaptive.learn("chengche", Candidate("程彻", canonicalPinyin = "chengche", canonicalInitials = "cc"))
        adaptive.learn("zhinengti", Candidate("智能体", canonicalPinyin = "zhinengti", canonicalInitials = "znt"))
        val cases = mapOf(
            "chengchexihuanzhinengti" to "程彻喜欢智能体",
            "woxihuanchengche" to "我喜欢程彻",
            "zhinengtikeyibangmang" to "智能体可以帮忙",
            "wodezhinengti" to "我的智能体",
        )
        for ((pinyin, text) in cases) for (limit in listOf(6, 255)) {
            val values = adaptive.decode(pinyin, limit)
            assertEquals("$pinyin limit=$limit: ${values.take(3)}", text, values.first().text)
            assertEquals(pinyin, values.first().canonicalPinyin)
            assertEquals(text.codePointCount(0, text.length), values.first().canonicalInitials?.length)
        }
    }

    @Test
    fun learnedPhraseReuseIsIsolatedPerDecoderAndForgetRestoresBaseSentences() {
        val sharedBase = base()
        val firstUser = MemoryUserLexicon(clock = { 1_000L })
        val adaptive = AdaptivePinyinDecoder(sharedBase, firstUser, segmenter())
        val otherUser = AdaptivePinyinDecoder(sharedBase, MemoryUserLexicon(), segmenter())
        val query = "zhinengtikeyibangmang"
        val before = adaptive.decode(query, 255)
        val learned = requireNotNull(adaptive.learn("zhinengti", Candidate("智能体", canonicalPinyin = "zhinengti", canonicalInitials = "znt")))
        assertEquals("智能体可以帮忙", adaptive.decode(query, 255).first().text)
        assertEquals(before, otherUser.decode(query, 255))
        assertTrue(adaptive.forget(learned))
        assertEquals(before, adaptive.decode(query, 255))
    }

    @Test
    fun quickDeleteRemovesTheUserOnlyWordFromNewSentenceSearch() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        val adaptive = AdaptivePinyinDecoder(base(), store, segmenter())
        val query = "chengchexihuanzhinengti"
        val before = adaptive.decode(query, 255)
        val phrase = requireNotNull(adaptive.learn("chengche", Candidate("程彻", canonicalPinyin = "chengche", canonicalInitials = "cc")))
        assertTrue(adaptive.decode(query, 255).first().text.startsWith("程彻"))
        adaptive.demote(phrase, UserNegativeFeedback.QUICK_DELETE)
        assertEquals(before, adaptive.decode(query, 255))
    }

    @Test
    fun forcedSyllableJointsAllowMatchingLearnedWordsButProtectOtherSegmentations() {
        val adaptive = AdaptivePinyinDecoder(base(), MemoryUserLexicon(clock = { 1_000L }), segmenter())
        adaptive.learn("chengche", Candidate("程彻", canonicalPinyin = "chengche", canonicalInitials = "cc"))
        adaptive.learn("xian", Candidate("先", canonicalPinyin = "xian", canonicalInitials = "x"))
        assertEquals("我喜欢程彻", adaptive.decode("woxihuan'cheng'che", 255).first().text)
        assertEquals("我喜欢西安", adaptive.decode("woxihuan'xi'an", 255).first().text)
    }

    @Test
    fun learnedPhraseLongerThanStaticEdgeLimitCanComposeWithASuffix() {
        val adaptive = AdaptivePinyinDecoder(base(), MemoryUserLexicon(clock = { 1_000L }), segmenter())
        val code = "wodezhongwenshurufazhinengti"
        assertTrue(code.length > 24)
        assertNotNull(adaptive.learn(code, Candidate("我的中文输入法智能体", canonicalPinyin = code, canonicalInitials = "wdzwsrfznt")))
        assertEquals("我的中文输入法智能体很好用", adaptive.decode(code + "henhaoyong", 255).first().text)
    }

    @Test
    fun learnedNameAndTechnicalWordSurviveSubsequentDecodesAndJournalRestore() {
        val base = base()
        val segmenter = segmenter()
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val lexicon = MemoryUserLexicon(clock = { 1_000L }, onRecord = { journal[it.fullPinyin to it.text] = it })
        val adaptive = AdaptivePinyinDecoder(base, lexicon, segmenter)
        for ((pinyin, text, initials) in listOf(Triple("chengche", "程彻", "cc"), Triple("zhinengti", "智能体", "znt"))) {
            // This is the canonical metadata carried by a confirmed segmented selection.
            assertNotNull(adaptive.learn(pinyin, Candidate(text, canonicalPinyin = pinyin, canonicalInitials = initials)))
            repeat(3) {
                val decoding = adaptive.decodeProgressively(PinyinComposition(remainingPinyin = pinyin), limit = 255)
                assertEquals(text, decoding.wholeCandidates.first().text)
                adaptive.decodeProgressively(PinyinComposition(remainingPinyin = "nihao"), limit = 255)
            }
        }
        val restored = AdaptivePinyinDecoder(base, MemoryUserLexicon(initial = journal.values, clock = { 1_000L }), segmenter)
        assertEquals("程彻", restored.decode("chengche", 255).first().text)
        assertEquals("智能体", restored.decode("zhinengti", 255).first().text)
    }

    @Test
    fun cachedBasePrefixIsRerankedAfterLearningAndForget() {
        val base = base()
        val adaptive = AdaptivePinyinDecoder(base, MemoryUserLexicon(clock = { 1_000L }), segmenter())
        val input = PinyinComposition(remainingPinyin = "shiren")
        val before = adaptive.decodeProgressively(input, limit = 255)
        assertTrue(before.prefixCandidates.any { it.consumedPinyin == "shi" })
        val learned = requireNotNull(adaptive.learn("shi", Candidate("诗", canonicalPinyin = "shi", canonicalInitials = "s")))
        val after = adaptive.decodeProgressively(input, limit = 255)
        assertTrue(after.prefixCandidates.any {
            it.consumedPinyin == "shi" && it.candidate.text == "诗" && it.candidate.matchKind == CandidateMatchKind.USER_FULL
        })
        assertTrue(adaptive.forget(learned))
        assertEquals(before, adaptive.decodeProgressively(input, limit = 255))
    }

    private fun base(): PinyinDecoder {
        val bigrams = file("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)
        return file("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it, bigrams) }
    }

    private fun segmenter() = PinyinSyllableSegmenter(file("pinyin_syllables.txt").readLines())

    private fun file(name: String): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "ime-service/src/main/assets/$name") }.first { it.isFile }
}
