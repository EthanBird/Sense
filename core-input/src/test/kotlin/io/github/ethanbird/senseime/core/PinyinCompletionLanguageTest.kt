package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PinyinCompletionLanguageTest {
    private val predictable = object : CharacterLanguageModel {
        override fun unigramLogProbability(next: Int) = -1f
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = -1f
    }

    @Test fun statisticallyRecalledUntypedSuffixDoesNotCollectCharacterInsertionRewards() {
        val base = fixture(mapOf("woxiang" to "我想", "}wox|woxiang" to "我想", "{wox" to "我想和你在一起"))
        for (limit in listOf(12,255)) {
            val d = base.withLanguageModel(predictable,.5f)
            assertEquals("我想",d.decode("wox",limit).first().text)
            assertTrue(d.decode("wox",limit).any { it.text == "我想和你在一起" })
        }
    }

    @Test fun canonicalPrefixDoesNotGetMoreRewardMerelyByPredictingMoreCharacters() {
        val base = fixture(mapOf("beijing" to "北京", "beijinghuanyingni" to "北京欢迎你"))
        val decoder = base.withLanguageModel(predictable,.5f)
        for (limit in listOf(1,12,255)) assertEquals("北京", decoder.decode("beij",limit).first().text)
    }

    @Test fun realProductionMixedInputPrefersTypedWordToSpeculativeSentence() {
        val base = production
        val decoder = AdaptivePinyinDecoder(base,MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        for (query in listOf("nix","wox")) {
            val expected = if(query=="nix") "你想" else "我想"
            for (limit in listOf(12,255)) {
                assertEquals(expected,decoder.decodeProgressively(PinyinComposition(emptyList(),query),"",limit).wholeCandidates.first().text)
            }
        }
    }

    @Test fun rareExactRecordDoesNotHideACommonLiteralContinuation() {
        for (limit in listOf(1,12,255)) {
            val candidates = production.decode("wome",limit)
            assertEquals("我们", candidates.first().text)
            assertEquals(CandidateMatchKind.BASE_PREFIX, candidates.first().matchKind)
            assertEquals("women", candidates.first().canonicalPinyin)
        }
    }

    @Test fun unfinishedWordDoesNotInheritTheComposedPathsCorrectionHandicap() {
        val decoder = AdaptivePinyinDecoder(production, MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        for (context in listOf("", "我想")) for (limit in listOf(12, 255)) {
            val result = decoder.decodeProgressively(PinyinComposition(emptyList(), "nime"), context, limit)
            assertEquals("$context / $limit", "你们", result.wholeCandidates.first().text)
            assertEquals(CandidateMatchKind.BASE_PREFIX, result.wholeCandidates.first().matchKind)
            if (limit == 255) assertTrue(result.wholeCandidates.any { it.text == "你的" })
        }
        assertEquals("你们", production.decode("nime", 1).first().text)
    }

    @Test fun literalCompletionUsesItsOwnSourcePriorAsTheCorrectionReference() {
        var trace: PinyinScoreDiagnostics.Trace? = null
        PinyinScoreDiagnostics.observe({ if (it.query == "nime" && it.seam == "decode") trace = it }) {
            production.decode("nime", 255)
        }
        val snapshot = requireNotNull(trace)
        assertFalse(snapshot.exact)
        assertTrue(snapshot.composed)
        val expected = 12f - (CandidateRanker.sourcePrior(CandidateMatchKind.BASE_COMPOSED, false, true) -
            CandidateRanker.sourcePrior(CandidateMatchKind.BASE_PREFIX, false, true))
        val repairs = snapshot.entries.filter { it.candidate.matchKind == CandidateMatchKind.CORRECTED }
        assertTrue(repairs.isNotEmpty())
        for (entry in repairs) assertEquals(expected.toDouble(),
            entry.evidence[PinyinScoreDiagnostics.Feature.CORRECTION_CALIBRATION], 0.00001)
    }

    @Test fun correctionFreePrefixProbesDoNotRepeatContextLookupsForCalibration() =
        assertCalibrationDoesNotRepeatContextLookups(prefixProbe = true)

    @Test fun completionCalibrationReusesTheFinalCandidateContextLookups() =
        assertCalibrationDoesNotRepeatContextLookups(prefixProbe = false)

    private fun assertCalibrationDoesNotRepeatContextLookups(prefixProbe: Boolean) {
        val bigrams = asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)
        var lookups = 0
        val counted = CharacterBigramModel { previous, next -> lookups++; bigrams.score(previous, next) }
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it, counted) }
            .withLanguageModel(asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load), .5f, -4f)
        val counts = listOf(0f, 12f).map { boost ->
            lookups = 0
            val result = base.withCorrectionCalibration(boost).decodeWithContext("我想", "nime", 255, prefixProbe)
            assertTrue(result.any { it.text == "你们" })
            if (!prefixProbe) assertTrue(result.any { it.text == "你的" })
            lookups
        }
        assertTrue(counts[0] > 0)
        assertEquals("Calibration must reuse, not repeat, candidate context evidence", counts[0], counts[1])
    }

    @Test fun explicitCorrectionChoiceStillLearnsAgainstTheLiteralCompletion() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        fun adaptive(memory: UserLexicon) = AdaptivePinyinDecoder(production, memory,
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val decoder = adaptive(store)
        val chosen = decoder.decode("nime", 255).first { it.text == "你的" }
        assertNotNull(decoder.learn("nime", chosen, UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 20)))
        repeat(2) {
            val first = decoder.decode("nime", 255).first()
            assertEquals("你的", first.text)
            decoder.learn("nime", first, UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT, 0))
        }
        val restored = adaptive(MemoryUserLexicon(store.lookup("nime", 255), clock = { 1_000L }))
        assertEquals("你的", restored.decode("nime", 255).first().text)
    }

    @Test fun theLiteralCompletionKeepsCanonicalIdentityForSubsequentLearning() {
        val store = MemoryUserLexicon()
        val decoder = AdaptivePinyinDecoder(production, store,
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val first = decoder.decode("nime", 255).first()
        assertEquals("你们", first.text)
        assertEquals("nimen", first.canonicalPinyin)
        val learned = requireNotNull(decoder.learn("nime", first))
        assertEquals("nimen", learned.fullPinyin)
        assertTrue("nime" in learned.aliases)
        assertEquals("你们", decoder.decode("nime", 255).first().text)
    }

    @Test fun completeCommonWordsStillBeatSpeculativeLongerWords() {
        for ((query, expected) in listOf("nihao" to "你好", "zhege" to "这个", "shijie" to "世界", "women" to "我们"))
            for (limit in listOf(1,12,255)) assertEquals(expected, production.decode(query,limit).first().text)
    }

    @Test fun explicitlySeparatedExactInputDoesNotRecallUnrequestedContinuations() {
        val candidates=production.decode("wo'me",255)
        assertEquals("我么", candidates.first().text)
        assertFalse(candidates.any {it.text=="我们"})
    }

    @Test fun completeSingleSyllablesKeepTheirLiteralCandidates() {
        for ((query, expected) in listOf("za" to "咋", "ya" to "呀"))
            for (limit in listOf(1,12,255))
                assertEquals(expected, production.decodeWithContext("我想",query,limit,false).first().text)
    }

    @Test fun explicitlyChosenRareExactWordStillOverridesTheCompletion() {
        val decoder=AdaptivePinyinDecoder(production,MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val choice=decoder.decode("wome",255).first {it.text=="我么"}
        assertNotNull(decoder.learn("wome",choice,UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION,20)))
        repeat(2) {
            val first=decoder.decode("wome",255).first()
            assertEquals("我么",first.text)
            decoder.learn("wome",first,UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT,0))
        }
    }

    @Test fun theUnfinishedLastSyllableKeepsItsInsertionEvidence() {
        val base = fixture(mapOf("beijing" to "北京", "beijinghuanyingni" to "北京欢迎你"))
        val d = base.withLanguageModel(predictable,.5f)
        val traces = mutableListOf<PinyinScoreDiagnostics.Trace>()
        PinyinScoreDiagnostics.observe({traces+=it}) { d.decode("beij",255);d.decode("beijing",255) }
        val prefix = traces.first {it.query=="beij"}.entries.first {it.candidate.text=="北京" && it.candidate.matchKind==CandidateMatchKind.BASE_PREFIX}
        val exact = traces.first {it.query=="beijing"}.entries.first {it.candidate.text=="北京" && it.candidate.matchKind==CandidateMatchKind.BASE_EXACT}
        assertEquals(5.0,prefix.evidence[PinyinScoreDiagnostics.Feature.CHARACTER_LM],0.00001)
        assertEquals(5.0/(7f/6f),exact.evidence[PinyinScoreDiagnostics.Feature.CHARACTER_LM],0.00001)
    }

    @Test fun partialSyllablesDoNotTurnIntoUnrelatedCorrections() {
        for ((query,expected) in listOf("f" to "分","p" to "跑","q" to "去","zhine" to "只能"))
            assertEquals(expected, production.decode(query,12).first().text)
    }

    @Test fun aValidUnfinishedFirstSyllableIsNotAutoCorrectedBeforeItIsFinished() {
        for (query in listOf("go","gon","zhon","f","q")) for(limit in listOf(12,255)) {
            val candidates=production.decode(query,limit)
            assertTrue(candidates.any {it.matchKind==CandidateMatchKind.BASE_PREFIX})
            assertFalse("$query: $candidates",candidates.any {it.matchKind==CandidateMatchKind.CORRECTED})
        }
        assertTrue(production.decode("nihoa",255).any {it.matchKind==CandidateMatchKind.CORRECTED && it.text=="你好"})
    }

    @Test fun coveredSyllablesAreAlignedToInitialsIncludingAmbiguousBoundaries() {
        val s=PinyinSyllableSegmenter(setOf("xi","xian","an","xiang","wo","bei","jing","zhi","ne","neng"))
        for ((q,initials,count) in listOf(Triple("wox","wxhnzyq",2),Triple("beij","bjhyn",2),
                Triple("zhine","zn",2),Triple("xian","xa",2),Triple("xian","x",1),Triple("xi","xa",1)))
            assertEquals("$q / $initials",count,s.coveredPrefixCharacters(q,initials))
        assertEquals(0,s.coveredPrefixCharacters("wox","bj"))
        assertEquals(0,s.coveredPrefixCharacters("", "wx"))
        assertEquals(0,s.coveredPrefixCharacters("wox",null))
    }

    @Test fun supportedCharactersAndOovKeepTheirOriginalScoresWithoutCachePollution() {
        val model=object:CharacterLanguageModel {
            override fun containsCodePoint(codePoint:Int)=codePoint!='陌'.code
            override fun unigramLogProbability(next:Int)=-2f
            override fun logProbability(previous2:Int,previous1:Int,next:Int)=-2f
        }
        val s=PinyinLanguageScorer(PinyinLanguageBinding(model,.5f,oovFeature=-4f),"wo",PinyinLanguageScorer.context(""))
        val original=s.feature("我𠀀陌")
        assertEquals(original,s.completionFeature("我𠀀陌",3),0f)
        assertEquals(original-3f,s.completionFeature("我𠀀陌",1),0f)
        assertEquals(original-6f,s.completionFeature("我𠀀陌",0),0f)
        assertEquals(original,s.feature("我𠀀陌"),0f)
    }

    @Test fun disabledModelKeepsLegacyCompletionAndExplicitFullSpellingStillWorks() {
        val base=fixture(mapOf("beijing" to "北京","beijinghuanyingni" to "北京欢迎你"))
        for(q in listOf("b","beij","beijinghuanyingni"))
            assertEquals(base.decode(q,255),base.withLanguageModel(predictable,0f).decode(q,255))
        assertEquals("北京欢迎你",base.withLanguageModel(predictable,.5f).decode("beijinghuanyingni",255).first().text)
    }

    private fun fixture(records: Map<String,String>): PinyinDecoder {
        // The production decoder derives its syllable inventory from single-Han records.
        val complete=records+mapOf("bei" to "北","jing" to "京","wo" to "我","xiang" to "想")
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX");out.writeShort(3);out.writeInt(complete.size)
            for((code,word) in complete.toSortedMap()) {
                out.writeByte(code.length);out.writeBytes(code);out.writeByte(1)
                val text=word.toByteArray(Charsets.UTF_8);out.writeByte(text.size);out.write(text);out.writeInt(1000)
                val initials=if(word.length==1) code.take(1) else when(word){"我想"->"wx";"我想和你在一起"->"wxhnzyq";"北京"->"bj";else->"bjhyn"}
                out.writeByte(initials.length);out.writeBytes(initials);out.writeByte(0)
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray())
    }
    // Do not pin another full production dictionary for the lifetime of the JUnit worker.
    private val production by lazy {
        asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
            .withLanguageModel(asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load),.5f,-4f)
    }
    companion object {
        private fun asset(name:String):File = listOf(File("../ime-service/src/main/assets/$name"),
            File("ime-service/src/main/assets/$name")).first {it.isFile}
    }
}
