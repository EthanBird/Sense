package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Correctness and bounded-latency gate for bilingual and hybrid-pinyin input. */
object M5MixedInputBenchmark {
    private data class DynamicMixedCase(
        val query: String,
        val expected: String,
        val canonicalPinyin: String,
        val formattedSegmentation: String,
        val maximumRank: Int,
    )

    private data class DynamicMixedObservation(
        val item: DynamicMixedCase,
        val rank: Int,
        val matchKind: CandidateMatchKind,
    )

    private data class DynamicMixedLatency(
        val query: String,
        val p95Ns: Double,
        val maxNs: Long,
    )

    private data class SingleLetterObservation(
        val letter: Char,
        val hasPinyinEvidence: Boolean,
        val firstChineseRank: Int?,
        val chineseCandidates: Int,
        val englishCandidates: Int,
        val firstEnglishRank: Int?,
    )

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
        val segmenter = PinyinSyllableSegmenter(syllablesFile.readLines())
        val adaptive = AdaptivePinyinDecoder(
            base,
            userLexicon,
            segmenter,
            english,
        )
        val wCandidates = adaptive.decode("w", SINGLE_LETTER_REPLAY_LIMIT)
        check(wCandidates.firstOrNull()?.text == "我") {
            "English prefix suggestions must not displace w -> 我"
        }
        check(
            wCandidates.take(SINGLE_LETTER_CHINESE_HEAD).all {
                it.matchKind !in ENGLISH_MATCH_KINDS
            },
        ) {
            "A one-letter pinyin initial must keep a Chinese candidate head: ${wCandidates.take(12)}"
        }
        val wChineseCount = wCandidates.count { it.matchKind !in ENGLISH_MATCH_KINDS }
        val wEnglishCount = wCandidates.size - wChineseCount
        val wFirstEnglishRank =
            wCandidates.indexOfFirst { it.matchKind in ENGLISH_MATCH_KINDS }
                .takeIf { it >= 0 }
                ?.plus(1)
        check(
            wChineseCount >= wEnglishCount * SINGLE_LETTER_CHINESE_DOMINANCE_RATIO &&
                wFirstEnglishRank != null,
        ) {
            "w must be Chinese-dominant while preserving reachable English candidates: $wCandidates"
        }
        check(adaptive.decode("z", 16).firstOrNull()?.text == "在") {
            "Corpus-noise English entries must not displace z -> 在"
        }
        val singleLetterObservations = ('a'..'z').map { letter ->
            val candidates = adaptive.decode(letter.toString(), SINGLE_LETTER_REPLAY_LIMIT)
            val hasPinyinEvidence =
                segmenter.syllablesStartingWith(letter.toString()).isNotEmpty()
            val firstChineseRank = candidates
                .indexOfFirst { it.matchKind !in ENGLISH_MATCH_KINDS }
                .takeIf { it >= 0 }
                ?.plus(1)
            val englishCandidates = candidates.count { it.matchKind in ENGLISH_MATCH_KINDS }
            val chineseCandidates = candidates.size - englishCandidates
            val firstEnglishRank = candidates
                .indexOfFirst { it.matchKind in ENGLISH_MATCH_KINDS }
                .takeIf { it >= 0 }
                ?.plus(1)
            if (hasPinyinEvidence) {
                val englishBudget = if (
                    candidates.any { it.matchKind == CandidateMatchKind.ENGLISH_EXACT }
                ) {
                    STRONG_PINYIN_ENGLISH_EXACT_BUDGET
                } else {
                    STRONG_PINYIN_ENGLISH_PREFIX_BUDGET
                }
                check(firstChineseRank == 1) {
                    "Valid pinyin initial $letter must keep a Chinese Top-1: $candidates"
                }
                check(englishCandidates <= englishBudget) {
                    "Valid pinyin initial $letter exceeded its English doorway budget: $candidates"
                }
            }
            if (letter == 'i' || letter == 'u') {
                check(!hasPinyinEvidence && englishCandidates > 0) {
                    "$letter should retain its Latin doorway without synthetic pinyin evidence"
                }
            }
            SingleLetterObservation(
                letter = letter,
                hasPinyinEvidence = hasPinyinEvidence,
                firstChineseRank = firstChineseRank,
                chineseCandidates = chineseCandidates,
                englishCandidates = englishCandidates,
                firstEnglishRank = firstEnglishRank,
            )
        }
        check(
            adaptive.decode("hang", 16)
                .firstOrNull { it.matchKind !in ENGLISH_MATCH_KINDS }
                ?.matchKind == CandidateMatchKind.BASE_EXACT,
        ) {
            "A hybrid alias must not displace a valid full-pinyin candidate among Chinese results"
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
            it.consumedPinyin == "ho" && it.remainingPinyin == "st"
        } ?: error("host must expose a selectable production prefix for ho|st: ${host.prefixCandidates.take(16)}")
        val acceptedHostPrefix = hostComposition.acceptPrefix(host.revision, hostPrefix)
        check(acceptedHostPrefix.visibleText == hostPrefix.candidate.text + "st") {
            "host prefix acceptance must preserve the selected text and suffix: $acceptedHostPrefix"
        }

        val funCandidates = adaptive.decode("fun", 64)
        check(
            funCandidates.firstOrNull()?.matchKind !in ENGLISH_MATCH_KINDS &&
                funCandidates.getOrNull(1)?.text == "fun" &&
                funCandidates.getOrNull(1)?.matchKind == CandidateMatchKind.ENGLISH_EXACT,
        ) {
            "Complete English input must preserve the Chinese head and remain available at second place: " +
                funCandidates.take(16)
        }
        val frostFunCandidates = funCandidates.filter { it.text in FROST_FUN_TEXTS }
        check(frostFunCandidates.map { it.text }.toSet() == FROST_FUN_TEXTS) {
            "Frost fun hybrid recall regression: ${funCandidates.take(64)}"
        }
        check(frostFunCandidates.first().text == "妇女") {
            "The highest-frequency Frost fun hybrid must lead its production cohort: $frostFunCandidates"
        }
        check(
            frostFunCandidates.indexOfFirst { it.text == "妇女" } <
                frostFunCandidates.indexOfFirst { it.text == "父女" } &&
                frostFunCandidates.indexOfFirst { it.text == "父女" } <
                frostFunCandidates.indexOfFirst { it.text == "腐女" },
        ) {
            "Frost fu-nv frequency order regression: $frostFunCandidates"
        }
        check(
            frostFunCandidates.all {
                it.matchKind == CandidateMatchKind.BASE_HYBRID &&
                    it.canonicalPinyin in FROST_FUN_CANONICAL_CODES
            },
        ) {
            "Frost fun records must be recalled through typed hybrid evidence: $frostFunCandidates"
        }
        val learnedFun = adaptive.learn(
            "fun",
            frostFunCandidates.first { it.text == "妇女" },
        ) ?: error("fun hybrid selection must be learnable")
        check("fun" in learnedFun.aliases)
        check(
            adaptive.decode("fun", 64)
                .firstOrNull { it.matchKind !in ENGLISH_MATCH_KINDS }
                ?.matchKind == CandidateMatchKind.USER_FULL,
        ) {
            "fun hybrid alias must be recalled immediately"
        }
        val reloaded = AdaptivePinyinDecoder(
            base,
            MemoryUserLexicon(listOf(learnedFun)),
            PinyinSyllableSegmenter(syllablesFile.readLines()),
            english,
        )
        check(
            reloaded.decode("fun", 64)
                .firstOrNull { it.matchKind !in ENGLISH_MATCH_KINDS }
                ?.matchKind == CandidateMatchKind.USER_FULL,
        ) {
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
        val dynamicMixedObservations = DYNAMIC_MIXED_CASES.map { item ->
            val candidates = adaptive.decode(item.query, DYNAMIC_MIXED_CANDIDATE_LIMIT)
            val index = candidates.indexOfFirst { candidate ->
                candidate.text == item.expected &&
                    candidate.canonicalPinyin == item.canonicalPinyin
            }
            check(index >= 0 && index + 1 <= item.maximumRank) {
                "Dynamic mixed-pinyin recall regression for ${item.query}: ${candidates.take(16)}"
            }
            val matched = candidates[index]
            check(matched.matchKind == CandidateMatchKind.BASE_HYBRID) {
                "Dynamic mixed-pinyin evidence must remain typed as BASE_HYBRID: $matched"
            }
            val segmented = segmenter.segmentMixed(
                item.query,
                matched.canonicalPinyin,
                matched.canonicalInitials,
            )
            check(segmented?.formatted == item.formattedSegmentation) {
                "Candidate-aligned mixed segmentation drift for ${item.query}: $segmented"
            }
            DynamicMixedObservation(
                item = item,
                rank = index + 1,
                matchKind = matched.matchKind,
            )
        }

        repeat(WARMUP_COUNT) {
            english.suggest("host", 16)
            adaptive.decodeProgressively(hostComposition, 32)
            adaptive.decode("zhongwsrf", 16)
            DYNAMIC_MIXED_CASES.forEach { item ->
                adaptive.decode(item.query, DYNAMIC_MIXED_CANDIDATE_LIMIT)
            }
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
        val dynamicMixedLatencies = DYNAMIC_MIXED_CASES.map { item ->
            val samples = LongArray(DYNAMIC_MIXED_SAMPLES_PER_QUERY) {
                measureNanoTime {
                    check(
                        adaptive.decode(item.query, DYNAMIC_MIXED_CANDIDATE_LIMIT)
                            .any { it.text == item.expected },
                    )
                }
            }
            DynamicMixedLatency(
                query = item.query,
                p95Ns = percentile(samples, 0.95),
                maxNs = samples.max(),
            )
        }
        val englishP95 = perLookup(englishSamples, ENGLISH_LOOKUPS)
        val hostP95 = perLookup(hostSamples, MIXED_LOOKUPS)
        val hybridP95 = perLookup(hybridSamples, HYBRID_LOOKUPS)
        val dynamicMixedP95 = dynamicMixedLatencies.maxOf(DynamicMixedLatency::p95Ns)
        val dynamicMixedMax = dynamicMixedLatencies.maxOf(DynamicMixedLatency::maxNs)
        check(englishP95 <= ENGLISH_P95_GATE_NS) { "English lookup p95 regression: $englishP95 ns" }
        check(hostP95 <= MIXED_P95_GATE_NS) { "host mixed decode p95 regression: $hostP95 ns" }
        check(hybridP95 <= HYBRID_P95_GATE_NS) { "hybrid decode p95 regression: $hybridP95 ns" }
        dynamicMixedLatencies.forEach { latency ->
            check(latency.p95Ns <= DYNAMIC_MIXED_P95_GATE_NS) {
                "dynamic mixed decode p95 regression for ${latency.query}: ${latency.p95Ns} ns"
            }
        }

        report.writeText(
            """
            {
              "schemaVersion": 4,
              "stage": "M5-mixed-input",
              "generatedAt": "${Instant.now()}",
              "correctness": {
                "w": "我",
                "wChineseHead": ${jsonStringArray(
                    wCandidates.take(SINGLE_LETTER_CHINESE_HEAD).map { it.text },
                )},
                "wChineseCandidates": $wChineseCount,
                "wEnglishCandidates": $wEnglishCount,
                "wFirstEnglishRank": $wFirstEnglishRank,
                "singleLetters": ${singleLetterJson(singleLetterObservations)},
                "z": "在",
                "hangFirstChineseSource": "BASE_EXACT",
                "host": ["host", "hosts", "hostile", "${jsonString(hostPrefix.candidate.text)}|st"],
                "funEnglishExact": "fun",
                "funFrostHybrid": ${jsonStringArray(frostFunCandidates.map { it.text })},
                "funLearnedAlias": "fun",
                "zhongwsrf": "中文输入法",
                "zhongwensrf": "中文输入法",
                "dynamicMixed": ${dynamicMixedJson(dynamicMixedObservations)}
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
              },
              "dynamicMixedDecode": {
                "cases": ${DYNAMIC_MIXED_CASES.size},
                "candidateLimit": $DYNAMIC_MIXED_CANDIDATE_LIMIT,
                "p95Ns": ${format(dynamicMixedP95)},
                "maxNs": $dynamicMixedMax,
                "gateNs": ${format(DYNAMIC_MIXED_P95_GATE_NS)},
                "byQuery": ${dynamicMixedLatencyJson(dynamicMixedLatencies)}
              }
            }
            """.trimIndent() + "\n",
        )
        println("M5 mixed-input benchmark written to ${report.absolutePath}")
    }

    private fun perLookup(samples: LongArray, lookups: Int): Double {
        return percentile(samples, 0.95) / lookups
    }

    private fun percentile(samples: LongArray, percentile: Double): Double {
        val sorted = samples.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble()
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

    private fun jsonStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "\"${jsonString(it)}\"" }

    private fun dynamicMixedJson(values: List<DynamicMixedObservation>): String =
        values.joinToString(prefix = "[", postfix = "]") { observation ->
            val item = observation.item
            """{"query":"${jsonString(item.query)}","expected":"${jsonString(item.expected)}","segmentation":"${jsonString(item.formattedSegmentation)}","canonicalPinyin":"${jsonString(item.canonicalPinyin)}","rank":${observation.rank},"maximumRank":${item.maximumRank},"matchKind":"${observation.matchKind.name}"}"""
        }

    private fun dynamicMixedLatencyJson(values: List<DynamicMixedLatency>): String =
        values.joinToString(prefix = "[", postfix = "]") { latency ->
            """{"query":"${jsonString(latency.query)}","p95Ns":${format(latency.p95Ns)},"maxNs":${latency.maxNs}}"""
        }

    private fun singleLetterJson(values: List<SingleLetterObservation>): String =
        values.joinToString(prefix = "[", postfix = "]") { observation ->
            """{"letter":"${observation.letter}","hasPinyinEvidence":${observation.hasPinyinEvidence},"firstChineseRank":${observation.firstChineseRank ?: "null"},"chineseCandidates":${observation.chineseCandidates},"englishCandidates":${observation.englishCandidates},"firstEnglishRank":${observation.firstEnglishRank ?: "null"}}"""
        }

    private fun jsonString(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }

    private const val SAMPLE_COUNT = 7
    private const val WARMUP_COUNT = 100
    private const val ENGLISH_LOOKUPS = 5_000
    private const val MIXED_LOOKUPS = 200
    private const val HYBRID_LOOKUPS = 1_000
    private const val DYNAMIC_MIXED_SAMPLES_PER_QUERY = 50
    private const val ENGLISH_P95_GATE_NS = 500_000.0
    private const val MIXED_P95_GATE_NS = 5_000_000.0
    private const val HYBRID_P95_GATE_NS = 5_000_000.0
    private const val DYNAMIC_MIXED_P95_GATE_NS = 5_000_000.0
    private const val SINGLE_LETTER_REPLAY_LIMIT = 16
    private const val SINGLE_LETTER_CHINESE_HEAD = 4
    private const val SINGLE_LETTER_CHINESE_DOMINANCE_RATIO = 3
    private const val STRONG_PINYIN_ENGLISH_PREFIX_BUDGET = 2
    private const val STRONG_PINYIN_ENGLISH_EXACT_BUDGET = 3
    private const val DYNAMIC_MIXED_CANDIDATE_LIMIT = 255
    private val ENGLISH_MATCH_KINDS = setOf(
        CandidateMatchKind.ENGLISH_EXACT,
        CandidateMatchKind.ENGLISH_PREFIX,
    )
    private val FROST_FUN_TEXTS = linkedSetOf("妇女", "父女", "腐女", "赋能")
    private val FROST_FUN_CANONICAL_CODES = setOf("funv", "funeng")
    private val DYNAMIC_MIXED_CASES = listOf(
        DynamicMixedCase(
            query = "hunshenxs",
            expected = "浑身解数",
            canonicalPinyin = "hunshenxieshu",
            formattedSegmentation = "hun'shen'x's",
            maximumRank = 1,
        ),
        DynamicMixedCase(
            query = "kaiyuanxy",
            expected = "开源协议",
            canonicalPinyin = "kaiyuanxieyi",
            formattedSegmentation = "kai'yuan'x'y",
            maximumRank = 10,
        ),
        DynamicMixedCase(
            query = "suanfafzd",
            expected = "算法复杂度",
            canonicalPinyin = "suanfafuzadu",
            formattedSegmentation = "suan'fa'f'z'd",
            maximumRank = 10,
        ),
        DynamicMixedCase(
            query = "yemianxs",
            expected = "页面显示",
            canonicalPinyin = "yemianxianshi",
            formattedSegmentation = "ye'mian'x's",
            maximumRank = 10,
        ),
        DynamicMixedCase(
            query = "wenjianxz",
            expected = "文件下载",
            canonicalPinyin = "wenjianxiazai",
            formattedSegmentation = "wen'jian'x'z",
            maximumRank = 10,
        ),
        DynamicMixedCase(
            query = "quanxiansz",
            expected = "权限设置",
            canonicalPinyin = "quanxianshezhi",
            formattedSegmentation = "quan'xian's'z",
            maximumRank = 10,
        ),
        DynamicMixedCase(
            query = "yisscp",
            expected = "\u827A\u672F\u6536\u85CF\u54C1",
            canonicalPinyin = "yishushoucangpin",
            formattedSegmentation = "yi's's'c'p",
            maximumRank = 64,
        ),
        DynamicMixedCase(
            query = "xiaszf",
            expected = "\u897F\u5B89\u5E02\u653F\u5E9C",
            canonicalPinyin = "xianshizhengfu",
            formattedSegmentation = "xi'a's'z'f",
            maximumRank = 64,
        ),
        DynamicMixedCase(
            query = "dancs",
            expected = "\u5927\u5E74\u521D\u4E09",
            canonicalPinyin = "danianchusan",
            formattedSegmentation = "da'n'c's",
            maximumRank = 64,
        ),
        DynamicMixedCase(
            query = "gongjijin",
            expected = "\u653B\u51FB\u6280\u80FD",
            canonicalPinyin = "gongjijineng",
            formattedSegmentation = "gong'ji'ji'n",
            maximumRank = 255,
        ),
    )
}
