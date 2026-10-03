package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.system.measureNanoTime

/** Development ablation only: same M8 queries, real progressive API, complete final candidates. */
object M11LatticeLanguageBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7) { "Usage: <lexicon> <bigrams> <syllables> <english> <replay> <scng> <report>" }
        val files = args.map(::File)
        val bigrams = files[1].inputStream().use(BinaryCharacterBigramModel::load)
        val base = files[0].inputStream().use { PinyinDecoder.load(it, bigrams) }
        val segmenter = PinyinSyllableSegmenter(files[2].readLines().toSet())
        val english = files[3].inputStream().use(EnglishLexicon::load)
        val model = files[5].inputStream().use(BinaryCharacterLanguageModel::load)
        val cases = M8DailyInputBenchmark.readCases(files[4])
        val neutral = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = -6f
            override fun logProbability(previous2: Int, previous1: Int, next: Int) = -6f
        }
        val modes = listOf("legacy" to base, "calibrationOnly" to base.withLanguageModel(neutral)) +
            listOf(.25f, .5f, 1f, 2f).map { "lm$it" to base.withLanguageModel(model, it) }
        var baselineRanks = emptyList<Int>()
        val reports = modes.map { (label, decoder) ->
            val adaptive = AdaptivePinyinDecoder(decoder, MemoryUserLexicon(), segmenter, english)
            val times = ArrayList<Long>()
            val ranks = ArrayList<Int>()
            var errors = 0
            var chars = 0
            val observations = cases.mapIndexed { index, case ->
                val composing = case.query.fold(PinyinComposition()) { state, key -> state.type(key) }
                lateinit var result: ProgressivePinyinDecoding
                times += measureNanoTime { result = adaptive.decodeProgressively(composing, case.context, 255) }
                val candidates = result.wholeCandidates
                val accepted = listOf(case.expected) + case.aliases
                val rank = candidates.indexOfFirst { it.text in accepted }.let { if (it < 0) 0 else it + 1 }
                ranks += rank
                errors += accepted.minOf { M8DailyInputBenchmark.editDistance(candidates.firstOrNull()?.text.orEmpty(), it) }
                chars += case.expected.codePointCount(0, case.expected.length)
                """{"query":${quote(case.query)},"expected":${quote(case.expected)},"baselineRank":${baselineRanks.getOrNull(index) ?: rank},"rank":$rank,"top5":[${candidates.take(5).joinToString { quote(it.text) }}]}"""
            }
            if (label == "legacy") baselineRanks = ranks
            val gained = ranks.indices.count { ranks[it] == 1 && baselineRanks[it] != 1 }
            val lost = ranks.indices.count { ranks[it] != 1 && baselineRanks[it] == 1 }
            val sorted = times.sorted()
            val personal = AdaptivePinyinDecoder(decoder, MemoryUserLexicon(clock = { 1_000L }), segmenter, english)
            personal.learn("chengche", Candidate("程彻", canonicalPinyin = "chengche", canonicalInitials = "cc"))
            personal.learn("zhinengti", Candidate("智能体", canonicalPinyin = "zhinengti", canonicalInitials = "znt"))
            val personalCases = mapOf("chengchexihuanzhinengti" to "程彻喜欢智能体", "woxihuanchengche" to "我喜欢程彻", "zhinengtikeyibangmang" to "智能体可以帮忙", "wodezhinengti" to "我的智能体")
            val personalResults = personalCases.map { (query, gold) ->
                val values = personal.decode(query, 255)
                """{"query":${quote(query)},"expected":${quote(gold)},"rank":${values.indexOfFirst { it.text == gold } + 1},"first":${quote(values.firstOrNull()?.text.orEmpty())}}"""
            }
            println("$label: first=${ranks.count { it == 1 }}/${cases.size}, gains=$gained losses=$lost, p95=${sorted[(sorted.size * .95).toInt() - 1] / 1e6}ms")
            """{"mode":${quote(label)},"top1":${ranks.count { it == 1 }},"top3":${ranks.count { it in 1..3 }},"top10":${ranks.count { it in 1..10 }},"covered":${ranks.count { it > 0 }},"gains":$gained,"losses":$lost,"cer":${errors.toDouble()/chars},"finalDecodeP95Ms":${sorted[(sorted.size*.95).toInt()-1]/1e6},"personalSmoke":[${personalResults.joinToString()}],"observations":[${observations.joinToString()}]}"""
        }
        files[6].parentFile?.mkdirs()
        val repo = generateSequence(files[0].absoluteFile.parentFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val sourceRoot = File(repo, "core-input/src/main/kotlin/io/github/ethanbird/senseime/core")
        val sourceNames = listOf("PinyinDecoder.kt", "PinyinLanguageScorer.kt", "CharacterLanguageModel.kt", "AdaptivePinyinDecoder.kt", "PrefixCandidateCache.kt", "UserPinyinLattice.kt", "M11LatticeLanguageBenchmark.kt")
        val sources = sourceNames.joinToString { quote(it) + ":" + quote(sha(File(sourceRoot, it))) }
        files[6].writeText("""{"schemaVersion":1,"generatedAt":${quote(Instant.now().toString())},"purpose":"M8 development ablation, not held-out P2C evaluation; host final progressive decode, not per-key Android timing","candidateLimit":255,"cases":${cases.size},"calibration":{"normalizer":"max(1, rawQueryLetters/6)","characterInsertionPrior":6,"centeredEmissionClip":[-6,6],"oovEmission":"neutral; aggregate UNK mass is not an individual glyph probability","personalOnlyMultiCharacterWord":"query-level lexical backoff"},"sources":{$sources},"assets":{${files.take(6).joinToString { quote(it.name)+":"+quote(sha(it)) }}},"modes":[${reports.joinToString()}]}""" + "\n")
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
