package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.*
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductionPinyinDecodersTest {
    private val assets get() = RuntimeEnvironment.getApplication().assets
    private val syllables by lazy { assets.open("pinyin_syllables.txt").bufferedReader().use { it.readLines().toSet() } }
    private val segmenter by lazy { PinyinSyllableSegmenter(syllables) }
    private val base by lazy {
        val bigrams = assets.open("pinyin_bigrams.bin").use(BinaryCharacterBigramModel::load)
        BundledPinyinLexicon.load(bigrams) { assets.open(it) }
    }
    private val english by lazy { assets.open("english_lexicon.txt").use(EnglishLexicon::load) }
    private fun bundled() = BundledPinyinLanguageModel.load { assets.open(it) }
    private fun decoders(store: UserLexicon = MemoryUserLexicon(), model: CharacterLanguageModel = bundled().model) =
        ProductionPinyinDecoders.create(base, store, segmenter, english, model)

    @Test fun layeredVocabularyOffersIntelligentAgentWithoutPersonalHistory() {
        val decoder = decoders().fullPinyin
        assertEquals("智能体", decoder.decodeProgressively(PinyinComposition(remainingPinyin = "zhinengti"), "", 255).wholeCandidates.first().text)
    }

    @Test fun oldMixedSpellingAliasesRemainReachableInLegacyProductionBinding() {
        val pair = decoders()
        assertTrue(pair.t9.decode("yisscp", 255).any { it.text == "艺术收藏品" })
        assertTrue(pair.t9.decode("hunshenxs", 255).any { it.text == "浑身解数" })
        // E9's rebuilt archived-code trace corrects the earlier classification:
        // supplemental prefix expansion crowded this out before final LM scoring.
        assertTrue(pair.fullPinyin.decode("yisscp", 255).any { it.text == "艺术收藏品" })
        assertTrue(pair.fullPinyin.decode("hunshenxs", 255).any { it.text == "浑身解数" })
    }

    @Test fun unknownGlyphNeutralityDoesNotBeatTheObservedSentenceInProduction() {
        val query = "geimianbaoderenbuhuie"
        val candidates = decoders().fullPinyin.decodeProgressively(PinyinComposition(remainingPinyin = query), "", 255).wholeCandidates
        assertEquals("给面包的人不会饿", candidates.first().text)
        assertTrue("rare glyph is still available, not filtered", candidates.any { it.text == "给面包的人不会呃" })
    }

    @Test fun explicitRareWordLearningStillSurvivesReuseRestoreAndForget() {
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val store = MemoryUserLexicon(clock = { 1_000L }, onRecord = { journal[it.fullPinyin to it.text] = it })
        val decoder = decoders(store).fullPinyin
        for ((code, text, initials) in listOf(Triple("kuagu", "胯骨", "kg"), Triple("chengche", "程彻", "cc"), Triple("zhinengti", "智能体", "znt"))) {
            val before = decoder.decode(code, 64)
            val target = Candidate(text, canonicalPinyin = code, canonicalInitials = initials)
            assertNotNull(decoder.learn(code, target))
            assertEquals(text, decoder.decode(code, 64).first().text)
            assertNotNull(decoder.learn(code, target, UserLearningEvidence.DEFAULT_ACCEPT))
            val accepted = decoder.decode(code, 64)
            assertEquals(text, accepted.first().text)
            val restored = decoders(MemoryUserLexicon(initial = journal.values, clock = { 1_000L })).fullPinyin
            assertEquals(accepted, restored.decode(code, 64))
            assertTrue(store.forget(code, text))
            assertEquals(before, decoder.decode(code, 64))
        }
    }

    @Test fun actualProductionSearchStopsWhenANewerWorkerRequestSupersedesIt() {
        val model = bundled().model
        val firstTransition = CountDownLatch(1)
        val releaseTransition = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val modelCalls = AtomicInteger()
        val canceled = AtomicInteger()
        val values = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observedModel = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = model.unigramLogProbability(next)
            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float {
                if (modelCalls.incrementAndGet() == 1) {
                    firstTransition.countDown()
                    assertTrue(releaseTransition.await(5, TimeUnit.SECONDS))
                }
                return model.logProbability(previous2, previous1, next)
            }
        }
        val decoder = decoders(model = observedModel).fullPinyin
        val runner = LatestOnlyTaskRunner<String, ProgressivePinyinDecoding>(
            threadName = "actual-pinyin-cancellation",
            work = { input, shouldContinue ->
                try {
                    DecodeWorkScope.whileCurrent(shouldContinue) {
                        decoder.decodeProgressively(PinyinComposition(remainingPinyin = input), "", 255)
                    }
                } catch (error: DecodeSupersededException) {
                    canceled.incrementAndGet()
                    throw error
                }
            },
            deliver = { _, _, result -> values += result.wholeCandidates.first().text; delivered.countDown() },
            fail = { _, _, error -> throw AssertionError("Cancellation must be dropped, not published as an empty result", error) },
        )
        try {
            runner.submit("woxihuanbeijing")
            assertTrue(firstTransition.await(5, TimeUnit.SECONDS))
            runner.submit("nihao")
            releaseTransition.countDown()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(1, canceled.get())
            assertEquals(listOf("你好"), values.toList())
        } finally { releaseTransition.countDown(); runner.close() }
    }

    @Test fun androidAssetBindingReproducesEveryVersionedReferencePrediction() {
        assertEquals(BundledPinyinLanguageModel.State.READY, bundled().state)
        val pair = decoders()
        assertNotSame(pair.t9, pair.fullPinyin)
        // E7 extends the dictionary. Keep historical reports intact and compare
        // against the unchanged frozen candidate's versioned host execution.
        val reference = JSONObject(repo("benchmarks/results/e7-android-binding-reference.json").readText())
        assertEquals(.5, reference.getDouble("lmWeight"), 0.0)
        assertEquals(12, reference.getInt("correctionCompositionBoost"))
        assertEquals(BundledPinyinLanguageModel.OOV_FEATURE.toDouble(), reference.getDouble("oovFeature"), 0.0)
        val rows = reference.getJSONArray("observations")
        assertEquals(125, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val result = pair.fullPinyin.decodeProgressively(PinyinComposition(remainingPinyin = row.getString("query")), "", 255).wholeCandidates
            assertEquals("active cancellation scope changes complete output", result, DecodeWorkScope.whileCurrent({ true }) {
                pair.fullPinyin.decodeProgressively(PinyinComposition(remainingPinyin = row.getString("query")), "", 255).wholeCandidates
            })
            val top = row.getJSONArray("top5")
            assertEquals("binding replay ${row.getString("id")}", (0 until top.length()).map(top::getString), result.take(5).map { it.text })
            assertEquals(row.getInt("rank"), result.indexOfFirst { it.text == row.getString("expected") } + 1)
        }
    }

    @Test fun t9KeepsItsLegacyDecoderWhileBothLayoutsSeeTheSameLearning() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        val pair = decoders(store)
        val legacy = AdaptivePinyinDecoder(base, store, segmenter, english)
        val index = T9SyllableIndex(assets.open("pinyin_syllables.txt").bufferedReader().use { it.readLines() })
        for (keys in listOf("64426", "94664486746", "74878", "24264")) {
            val input = keys.fold(T9Composition()) { state, key -> state.typeDigit(key) }
            assertEquals(T9AlternativeInputDecoder.decode(input, index, legacy, "", 64),
                T9AlternativeInputDecoder.decode(input, index, pair.t9, "", 64))
        }
        pair.fullPinyin.learn("chengche", Candidate("程彻", canonicalPinyin = "chengche", canonicalInitials = "cc"))
        assertEquals("程彻", pair.t9.decode("chengche", 8).first().text)
        assertEquals("我喜欢程彻", pair.fullPinyin.decode("woxihuanchengche", 8).first().text)
    }

    @Test fun missingModelUsesOneLegacyInstanceWithUnchangedPredictions() {
        val pair = decoders(model = CharacterLanguageModel.EMPTY)
        assertSame(pair.fullPinyin, pair.t9)
        val legacy = AdaptivePinyinDecoder(base, MemoryUserLexicon(), segmenter, english)
        for (query in listOf("nihao", "woxihuanni", "woshiyigezhongguoern")) {
            assertEquals(legacy.decode(query, 64), pair.fullPinyin.decode(query, 64))
        }
        val notice = JSONObject(assets.open("pinyin_character_lm_notice.json").bufferedReader().use { it.readText() })
        assertEquals("lm0.5", notice.getString("decoderMode"))
        assertTrue(notice.getJSONObject("attribution").getInt("sentences") > 76_000)
    }

    @Test fun provenanceNamesResolveToTheActualAttributedTrainingSources() {
        val notice = JSONObject(assets.open("pinyin_character_lm_notice.json").bufferedReader().use { it.readText() })
        val info = notice.getJSONObject("attribution")
        val bytes = assets.open(info.getString("file")).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(info.getString("sha256"), hash)
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals("# sentenceId\tcontributor", lines.first())
        val rows = lines.drop(1).map { it.split('\t') }
        assertEquals(info.getInt("sentences"), rows.size)
        assertTrue(rows.all { it.size == 2 && it[0].toLongOrNull() != null && it[1].isNotBlank() })
        assertEquals(rows.size, rows.map { it[0] }.toSet().size)
        assertEquals(info.getInt("contributors"), rows.map { it[1] }.toSet().size)
    }

    @Test fun bundledLanguageModelPreservesEnglishDoorwaysAndMixedSpellingRecall() {
        val decoder = decoders().fullPinyin
        val englishKinds = setOf(CandidateMatchKind.ENGLISH_EXACT, CandidateMatchKind.ENGLISH_PREFIX)
        assertEquals("我", decoder.decode("w", 32).first().text)
        assertEquals("在", decoder.decode("z", 32).first().text)
        for (letter in 'a'..'z') {
            if (syllables.any { it.startsWith(letter) }) {
                assertTrue(decoder.decode(letter.toString(), 32).first().matchKind !in englishKinds)
            }
        }
        val host = decoder.decodeProgressively(PinyinComposition(remainingPinyin = "host"), "", 32)
        assertEquals(listOf("host", "hosts", "hostile"), host.wholeCandidates.take(3).map { it.text })
        assertTrue(host.prefixCandidates.any { it.consumedPinyin == "ho" && it.remainingPinyin == "st" })
        val funValues = decoder.decode("fun", 64)
        assertEquals("fun", funValues[1].text)
        assertEquals(CandidateMatchKind.ENGLISH_EXACT, funValues[1].matchKind)
        assertTrue(funValues.any { it.text == "妇女" && it.matchKind == CandidateMatchKind.BASE_HYBRID })
        assertEquals(CandidateMatchKind.BASE_EXACT, decoder.decode("hang", 16).first { it.matchKind !in englishKinds }.matchKind)
    }

    @Test fun shippingBindingPassesTheRequiredPersonalSentenceReplay() {
        val rows = M9PersonalSentenceBenchmark.readCases(repo("benchmarks/replay/m9-personal-word-sentences.tsv"))
        val store = MemoryUserLexicon(clock = { 1_000L })
        val decoder = decoders(store).fullPinyin
        for (row in rows.distinctBy { it.code to it.text }) {
            assertNotNull(decoder.learn(row.code, Candidate(row.text, canonicalPinyin = row.code, canonicalInitials = row.initials)))
        }
        for (row in rows.filter { it.required }) {
            assertEquals(row.query, row.expected, decoder.decode(row.query, 255).first().text)
        }
    }

    @Test fun developmentAblationSeparatesWordFrequencyDictionaryBoundaryAndCorpusEvidence() {
        val cases = M12P2cBenchmark.readCases(repo("benchmarks/corpus/p2c-v1/dev.tsv"))
        val unigramBase = assets.open("pinyin_lexicon.bin").use(PinyinDecoder::load)
        val neutral = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = -6f
            override fun logProbability(previous2: Int, previous1: Int, next: Int) = -6f
        }
        val models = listOf(
            "wordFrequencyOnly" to ProductionPinyinDecoders.create(unigramBase, MemoryUserLexicon(), segmenter, english, CharacterLanguageModel.EMPTY).fullPinyin,
            "dictionaryBigram" to decoders(model = CharacterLanguageModel.EMPTY).fullPinyin,
            "calibrationOnly" to decoders(model = neutral).fullPinyin,
            "corpusLm0.5" to decoders().fullPinyin,
        )
        val observations = JSONArray()
        for ((name, decoder) in models) {
            var top1 = 0; var top3 = 0; var covered = 0; var errors = 0; var characters = 0
            for (case in cases) {
                val result = decoder.decodeProgressively(PinyinComposition(remainingPinyin = case.query), "", 255).wholeCandidates
                val rank = result.indexOfFirst { it.text == case.expected } + 1
                if (rank == 1) top1++
                if (rank in 1..3) top3++
                if (rank > 0) covered++
                errors += M8DailyInputBenchmark.editDistance(result.firstOrNull()?.text.orEmpty(), case.expected)
                characters += case.expected.codePointCount(0, case.expected.length)
            }
            observations.put(JSONObject().put("mode", name).put("top1", top1).put("top3", top3).put("covered", covered)
                .put("characterErrors", errors).put("characters", characters).put("cer", errors.toDouble() / characters))
        }
        // This is a development-only diagnostic, not another selection from the test set.
        // Pinned primary results remain untouched; CI can archive this independent report.
        val report = requireNotNull(repo("settings.gradle.kts").parentFile).resolve(".artifacts/input-quality/e7/m13-service-ablation.json")
        requireNotNull(report.parentFile).mkdirs()
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        report.writeText(JSONObject().put("schemaVersion", 1).put("scope", "dev only; Robolectric Android assets; diagnostic, not a new held-out selection")
            .put("cases", cases.size).put("devSha256", hash(repo("benchmarks/corpus/p2c-v1/dev.tsv")))
            .put("modelSha256", hash(repo("ime-service/src/main/assets/pinyin_character_lm.scng")))
            .put("corpusOovFeature", BundledPinyinLanguageModel.OOV_FEATURE)
            .put("modes", observations).toString(2) + "\n")
        // E7's pre-audit layered development replay is the versioned reference, not
        // an assertion silently relaxed to tolerate arbitrary future output changes.
        val quality = JSONObject(repo("benchmarks/results/e7-quality-gate.json").readText())
        val expected = quality.getJSONObject("comparisons").getJSONObject("development")
            .getJSONObject("byGroup").getJSONObject("operation").getJSONObject("clean")
            .getJSONObject("after").getInt("top1")
        assertEquals(expected, observations.getJSONObject(3).getInt("top1"))
    }

    private fun repo(path: String): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, path) }.first { it.isFile }
}
