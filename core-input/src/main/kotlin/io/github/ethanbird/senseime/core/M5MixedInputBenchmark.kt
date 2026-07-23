package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Correctness and bounded-latency gate for bilingual and hybrid-pinyin input. */
object M5MixedInputBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) {
            "Usage: M5MixedInputBenchmark <lexicon> <bigrams> <syllables> <english> <report>"
        }
        val lexiconFile = File(args[0])
        val bigramFile = File(args[1])
        val syllablesFile = File(args[2])
        val englishFile = File(args[3])
        val report = File(args[4])
        report.parentFile?.mkdirs()

        val bigrams = bigramFile.inputStream().buffered().use(BinaryCharacterBigramModel::load)
        val base = lexiconFile.inputStream().buffered().use { PinyinDecoder.load(it, bigrams) }
        val english = englishFile.inputStream().buffered().use { EnglishLexicon.load(it) }
        val userLexicon = MemoryUserLexicon()
        val adaptive = AdaptivePinyinDecoder(
            base,
            userLexicon,
            PinyinSyllableSegmenter(syllablesFile.readLines()),
            english,
        )
        check(adaptive.decode("w", 16).firstOrNull()?.text == "我") {
            "English prefix suggestions must not displace w -> 我"
        }
        check(adaptive.decode("z", 16).firstOrNull()?.text == "在") {
            "Corpus-noise English entries must not displace z -> 在"
        }
        check(adaptive.decode("hang", 16).firstOrNull()?.matchKind == CandidateMatchKind.BASE_EXACT) {
            "A hybrid alias must not displace a valid full-pinyin candidate"
        }
        check(
            base.decodeAfter("上".codePointAt(0), "hang", 255)
                .firstOrNull()
                ?.matchKind == CandidateMatchKind.BASE_EXACT,
        ) {
            "Context reranking must preserve valid full-pinyin precedence"
        }

        val hostComposition = "host".fold(PinyinComposition()) { state, character -> state.type(character) }
        val host = adaptive.decodeProgressively(hostComposition, 32)
        check(host.wholeCandidates.take(3).map { it.text } == listOf("host", "hosts", "hostile")) {
            "host English order regression: ${host.wholeCandidates.take(8)}"
        }
        val hostPrefix = host.prefixCandidates.firstOrNull {
            it.candidate.text == "好哦" && it.consumedPinyin == "ho" && it.remainingPinyin == "st"
        } ?: error("host must expose 好哦|st: ${host.prefixCandidates.take(16)}")
        check(hostComposition.acceptPrefix(host.revision, hostPrefix).visibleText == "好哦st")

        val funValues = adaptive.decode("fun", 16).map { it.text }
        check(funValues.take(6) == listOf("妇女", "👩🏻", "服你", "赋能", "fun", "腐女")) {
            "fun mixed order regression: $funValues"
        }
        val learnedFun = adaptive.learn(
            "fun",
            adaptive.decode("fun", 16).first { it.text == "妇女" },
        ) ?: error("fun hybrid selection must be learnable")
        check("fun" in learnedFun.aliases)
        check(adaptive.decode("fun", 16).first().matchKind == CandidateMatchKind.USER_FULL) {
            "fun hybrid alias must be recalled immediately"
        }
        val reloaded = AdaptivePinyinDecoder(
            base,
            MemoryUserLexicon(listOf(learnedFun)),
            PinyinSyllableSegmenter(syllablesFile.readLines()),
            english,
        )
        check(reloaded.decode("fun", 16).first().matchKind == CandidateMatchKind.USER_FULL) {
            "fun hybrid alias must survive user-lexicon reload"
        }
        val hybrid = adaptive.decode("zhongwsrf", 16).firstOrNull()
        check(
            hybrid?.text == "中文输入法" &&
                hybrid.canonicalPinyin == "zhongwenshurufa" &&
                hybrid.matchKind == CandidateMatchKind.BASE_HYBRID
        ) {
            "zhongwsrf hybrid regression: $hybrid"
        }
        check(adaptive.decode("zhongwensrf", 16).firstOrNull()?.text == "中文输入法") {
            "zhongwensrf hybrid regression"
        }

        repeat(WARMUP_COUNT) {
            english.suggest("host", 16)
            adaptive.decodeProgressively(hostComposition, 32)
            adaptive.decode("zhongwsrf", 16)
        }
        val englishSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(ENGLISH_LOOKUPS) { check(english.suggest("host", 16).first().text == "host") }
            }
        }
        val hostSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(MIXED_LOOKUPS) {
                    check(adaptive.decodeProgressively(hostComposition, 32).wholeCandidates.first().text == "host")
                }
            }
        }
        val hybridSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(HYBRID_LOOKUPS) { check(adaptive.decode("zhongwsrf", 16).first().text == "中文输入法") }
            }
        }
        val englishP95 = perLookup(englishSamples, ENGLISH_LOOKUPS)
        val hostP95 = perLookup(hostSamples, MIXED_LOOKUPS)
        val hybridP95 = perLookup(hybridSamples, HYBRID_LOOKUPS)
        check(englishP95 <= ENGLISH_P95_GATE_NS) { "English lookup p95 regression: $englishP95 ns" }
        check(hostP95 <= MIXED_P95_GATE_NS) { "host mixed decode p95 regression: $hostP95 ns" }
        check(hybridP95 <= HYBRID_P95_GATE_NS) { "hybrid decode p95 regression: $hybridP95 ns" }

        report.writeText(
            """
            {
              "schemaVersion": 1,
              "stage": "M5-mixed-input",
              "generatedAt": "${Instant.now()}",
              "correctness": {
                "w": "我",
                "z": "在",
                "hangFirstSource": "BASE_EXACT",
                "host": ["host", "hosts", "hostile", "好哦|st"],
                "fun": ["妇女", "👩🏻", "服你", "赋能", "fun", "腐女"],
                "funLearnedAlias": "fun",
                "zhongwsrf": "中文输入法",
                "zhongwensrf": "中文输入法"
              },
              "englishLexicon": {
                "words": ${englishFile.useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }},
                "bytes": ${englishFile.length()},
                "sha256": "${sha256(englishFile)}",
                "p95Ns": ${format(englishP95)},
                "gateNs": ${format(ENGLISH_P95_GATE_NS)}
              },
              "mixedProgressiveDecode": {
                "p95Ns": ${format(hostP95)},
                "gateNs": ${format(MIXED_P95_GATE_NS)}
              },
              "hybridDecode": {
                "p95Ns": ${format(hybridP95)},
                "gateNs": ${format(HYBRID_P95_GATE_NS)}
              }
            }
            """.trimIndent() + "\n",
        )
        println("M5 mixed-input benchmark written to ${report.absolutePath}")
    }

    private fun perLookup(samples: LongArray, lookups: Int): Double {
        val sorted = samples.sorted()
        val index = (ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble() / lookups
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun format(value: Double): String = "%.2f".format(Locale.US, value)

    private const val SAMPLE_COUNT = 7
    private const val WARMUP_COUNT = 100
    private const val ENGLISH_LOOKUPS = 5_000
    private const val MIXED_LOOKUPS = 200
    private const val HYBRID_LOOKUPS = 1_000
    private const val ENGLISH_P95_GATE_NS = 500_000.0
    private const val MIXED_P95_GATE_NS = 5_000_000.0
    private const val HYBRID_P95_GATE_NS = 5_000_000.0
}
