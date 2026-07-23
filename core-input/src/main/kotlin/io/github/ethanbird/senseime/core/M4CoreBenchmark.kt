package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Production correctness and latency gate for initials lookup and progressive segmentation. */
object M4CoreBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "Usage: M4CoreBenchmark <lexicon> <bigrams> <syllables> <replay> <report>" }
        val lexiconFile = File(args[0])
        val bigramFile = File(args[1])
        val syllablesFile = File(args[2])
        val replayFile = File(args[3])
        val report = File(args[4])
        report.parentFile?.mkdirs()

        val replay = replayFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
        val wExpectation = replay.single { it[0] == "decode" && it[1] == "w" }[2]
        val initialsExpectation = replay.single { it[0] == "decode" && it[1] == "ygz" }[2]
        val coverage = replay.single { it[0] == "coverage" }
        require(coverage.size == 4) { "M4 coverage replay row is invalid" }
        val coverageLimit = coverage[3].toInt()
        val composition = replay.single { it[0] == "composition" }
        require(composition.size == 3) { "M4 composition replay row is invalid" }
        val learning = replay.single { it[0] == "learn" }
        require(learning.size == 4) { "M4 learning replay row is invalid" }
        val progressive = replay.single { it[0] == "progressive" && it[1] == "pipei" }
        require(progressive.size == 9) { "M4 progressive replay row is invalid" }

        val bigrams = bigramFile.inputStream().buffered().use(BinaryCharacterBigramModel::load)
        val base = lexiconFile.inputStream().buffered().use { PinyinDecoder.load(it, bigrams) }
        val adaptive = AdaptivePinyinDecoder(
            base,
            MemoryUserLexicon(),
            PinyinSyllableSegmenter(syllablesFile.readLines()),
        )

        check(base.decode("w", 8).firstOrNull()?.text == wExpectation) { "w frequency regression" }
        val initials = base.decode("ygz", 8).firstOrNull()
        check(initials?.text == initialsExpectation && initials.matchKind == CandidateMatchKind.BASE_INITIALS) {
            "ygz initials regression: $initials"
        }
        val fourCharacterInitials = linkedMapOf(
            "scxt" to "上窜下跳",
            "ssyw" to "蛇鼠一窝",
        ).mapValues { (shortCode, expected) ->
            base.decode(shortCode, 8).firstOrNull().also { candidate ->
                check(candidate?.text == expected && candidate.matchKind == CandidateMatchKind.BASE_INITIALS) {
                    "$shortCode four-character initials regression: $candidate"
                }
            }!!
        }
        val coverageCandidates = base.decode(coverage[1], coverageLimit)
        val coverageRank = rankOf(coverageCandidates, coverage[2])
            ?: error("${coverage[1]} must expose ${coverage[2]} within $coverageLimit candidates")
        val compositionCandidates = base.decode(composition[1], PRODUCTION_CANDIDATE_LIMIT)
        val compositionRank = rankOf(compositionCandidates, composition[2])
            ?: error("${composition[1]} must expose ${composition[2]} as a segmented alternative")
        val learnable = base.decode(learning[1], PRODUCTION_CANDIDATE_LIMIT)
            .firstOrNull { it.text == learning[2] }
            ?: error("${learning[1]} must expose ${learning[2]}")
        check(learnable.canonicalPinyin == learning[3]) {
            "${learning[1]} candidate must retain canonical source ${learning[3]}: $learnable"
        }
        check(adaptive.learn(learning[1], learnable) != null) { "${learning[1]} selection must be learnable" }
        val recalled = adaptive.decode(learning[1], 8).firstOrNull()
        check(recalled?.text == learning[2] && recalled.matchKind == CandidateMatchKind.USER_INITIALS) {
            "one explicit selection must immediately rerank ${learning[2]}: $recalled"
        }

        val typed = "pipei".fold(PinyinComposition()) { state, character -> state.type(character) }
        val decoded = adaptive.decodeProgressively(typed, 32)
        check(decoded.wholeCandidates.firstOrNull()?.text == progressive[2]) { "pipei whole-candidate regression" }
        val pi = decoded.prefixCandidates.firstOrNull {
            it.consumedPinyin == progressive[3] && it.candidate.text == progressive[4]
        } ?: error("pipei must expose ${progressive[4]} as a ${progressive[3]} prefix: ${decoded.prefixCandidates}")
        check(decoded.prefixCandidates.first() == pi) { "whole-phrase evidence must rank ${progressive[4]} first" }
        check(decoded.prefixCandidates.any { it.candidate.text == progressive[5] }) {
            "pipei must expose ${progressive[5]} as a ${progressive[3]} prefix"
        }
        check(pi.remainingPinyin == progressive[6]) { "pipei remainder regression" }
        val partial = typed.acceptPrefix(decoded.revision, pi)
        check(partial.visibleText == progressive[7] && partial.confirmRaw() == progressive[7]) {
            "mixed composing regression"
        }
        val tail = adaptive.decodeProgressively(partial, 32).wholeCandidates.firstOrNull()
        check(partial.confirmPrimary(tail) == progressive[8]) { "Space completion regression: $tail" }

        repeat(WARMUP_LOOKUPS) {
            base.decode("ygz", 8)
            adaptive.decodeProgressively(typed, 16)
        }
        repeat(PRODUCTION_WARMUP_LOOKUPS) {
            check(base.decode(composition[1], PRODUCTION_CANDIDATE_LIMIT).any { it.text == composition[2] })
            check(adaptive.decodeProgressively(typed, PRODUCTION_CANDIDATE_LIMIT).prefixCandidates.isNotEmpty())
        }
        val initialsSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(INITIALS_LOOKUPS) { check(base.decode("ygz", 8).first().text == "一个字") }
            }
        }
        val progressiveSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(PROGRESSIVE_LOOKUPS) {
                    check(adaptive.decodeProgressively(typed, 16).prefixCandidates.isNotEmpty())
                }
            }
        }
        val productionProgressiveSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(PRODUCTION_PROGRESSIVE_LOOKUPS) {
                    check(
                        adaptive.decodeProgressively(typed, PRODUCTION_CANDIDATE_LIMIT)
                            .prefixCandidates
                            .isNotEmpty(),
                    )
                }
            }
        }
        val compositionSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(COMPOSITION_LOOKUPS) {
                    check(
                        base.decode(composition[1], PRODUCTION_CANDIDATE_LIMIT)
                            .any { it.text == composition[2] },
                    )
                }
            }
        }
        val initialsP95 = perLookup(initialsSamples, INITIALS_LOOKUPS, 0.95)
        val progressiveP95 = perLookup(progressiveSamples, PROGRESSIVE_LOOKUPS, 0.95)
        val productionProgressiveP95 = perLookup(
            productionProgressiveSamples,
            PRODUCTION_PROGRESSIVE_LOOKUPS,
            0.95,
        )
        val compositionP95 = perLookup(compositionSamples, COMPOSITION_LOOKUPS, 0.95)
        check(initialsP95 <= INITIALS_P95_GATE_NS) { "ygz p95 latency regression: $initialsP95 ns" }
        check(progressiveP95 <= PROGRESSIVE_P95_GATE_NS) { "progressive p95 latency regression: $progressiveP95 ns" }
        check(productionProgressiveP95 <= PRODUCTION_PROGRESSIVE_P95_GATE_NS) {
            "255-candidate progressive p95 latency regression: $productionProgressiveP95 ns"
        }
        check(compositionP95 <= COMPOSITION_P95_GATE_NS) {
            "segmented composition p95 latency regression: $compositionP95 ns"
        }
        val productionProgressiveCount = adaptive
            .decodeProgressively(typed, PRODUCTION_CANDIDATE_LIMIT)
            .prefixCandidates
            .size

        report.writeText(
            """
            {
              "schemaVersion": 1,
              "stage": "M4-core",
              "generatedAt": "${Instant.now()}",
              "lexicon": {
                "keys": ${recordCount(lexiconFile)},
                "bytes": ${lexiconFile.length()},
                "sha256": "${sha256(lexiconFile)}"
              },
              "bigrams": {
                "records": ${modelRecordCount(bigramFile)},
                "bytes": ${bigramFile.length()},
                "sha256": "${sha256(bigramFile)}"
              },
              "correctness": {
                "w": "我",
                "ygz": "一个字",
                "scxt": "${fourCharacterInitials.getValue("scxt").text}",
                "ssyw": "${fourCharacterInitials.getValue("ssyw").text}",
                "pipeiPrefix": "匹|pei",
                "enter": "匹pei",
                "space": "匹配"
              },
              "candidateCoverage": {
                "maximumCandidateLimit": $PRODUCTION_CANDIDATE_LIMIT,
                "huaRequestedLimit": $coverageLimit,
                "huaCountAtLimit": ${coverageCandidates.size},
                "huaTarget": "${coverage[2]}",
                "huaTargetRank": $coverageRank,
                "shanghuaCount": ${compositionCandidates.size},
                "shanghuaTarget": "${composition[2]}",
                "shanghuaTargetRank": $compositionRank,
                "learnedShortCode": "${learning[1]}",
                "learnedTopCandidate": "${recalled.text}"
              },
              "initialsLookup": {
                "lookupsPerSample": $INITIALS_LOOKUPS,
                "p95Ns": ${format(initialsP95)},
                "gateNs": ${format(INITIALS_P95_GATE_NS)}
              },
              "progressiveDecode": {
                "lookupsPerSample": $PROGRESSIVE_LOOKUPS,
                "p95Ns": ${format(progressiveP95)},
                "gateNs": ${format(PROGRESSIVE_P95_GATE_NS)}
              },
              "productionLimitProgressiveDecode": {
                "candidateLimit": $PRODUCTION_CANDIDATE_LIMIT,
                "prefixCandidateCount": $productionProgressiveCount,
                "lookupsPerSample": $PRODUCTION_PROGRESSIVE_LOOKUPS,
                "p95Ns": ${format(productionProgressiveP95)},
                "gateNs": ${format(PRODUCTION_PROGRESSIVE_P95_GATE_NS)}
              },
              "segmentedComposition": {
                "candidateLimit": $PRODUCTION_CANDIDATE_LIMIT,
                "lookupsPerSample": $COMPOSITION_LOOKUPS,
                "p95Ns": ${format(compositionP95)},
                "gateNs": ${format(COMPOSITION_P95_GATE_NS)}
              }
            }
            """.trimIndent() + "\n",
        )
        println("M4 core benchmark written to ${report.absolutePath}")
    }

    private fun perLookup(samples: LongArray, lookups: Int, percentile: Double): Double {
        val sorted = samples.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble() / lookups
    }

    private fun recordCount(file: File): Int = file.inputStream().buffered().use { input ->
        val header = input.readNBytes(10)
        require(header.size == 10 && header.copyOfRange(0, 4).decodeToString() == "SPLX")
        ((header[6].toInt() and 0xFF) shl 24) or
            ((header[7].toInt() and 0xFF) shl 16) or
            ((header[8].toInt() and 0xFF) shl 8) or
            (header[9].toInt() and 0xFF)
    }

    private fun modelRecordCount(file: File): Int = file.inputStream().buffered().use { input ->
        val header = input.readNBytes(10)
        require(header.size == 10 && header.copyOfRange(0, 4).decodeToString() == "SBGM")
        ((header[6].toInt() and 0xFF) shl 24) or
            ((header[7].toInt() and 0xFF) shl 16) or
            ((header[8].toInt() and 0xFF) shl 8) or
            (header[9].toInt() and 0xFF)
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

    internal fun rankOf(candidates: List<Candidate>, expected: String): Int? =
        candidates.indexOfFirst { it.text == expected }
            .takeIf { it >= 0 }
            ?.plus(1)

    private const val SAMPLE_COUNT = 7
    private const val WARMUP_LOOKUPS = 500
    private const val PRODUCTION_WARMUP_LOOKUPS = 20
    private const val INITIALS_LOOKUPS = 20_000
    private const val PROGRESSIVE_LOOKUPS = 5_000
    private const val PRODUCTION_PROGRESSIVE_LOOKUPS = 100
    private const val COMPOSITION_LOOKUPS = 100
    private const val PRODUCTION_CANDIDATE_LIMIT = 255
    private const val INITIALS_P95_GATE_NS = 250_000.0
    private const val PROGRESSIVE_P95_GATE_NS = 500_000.0
    private const val PRODUCTION_PROGRESSIVE_P95_GATE_NS = 5_000_000.0
    private const val COMPOSITION_P95_GATE_NS = 5_000_000.0
}
