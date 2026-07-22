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
        val initialsP95 = perLookup(initialsSamples, INITIALS_LOOKUPS, 0.95)
        val progressiveP95 = perLookup(progressiveSamples, PROGRESSIVE_LOOKUPS, 0.95)
        check(initialsP95 <= INITIALS_P95_GATE_NS) { "ygz p95 latency regression: $initialsP95 ns" }
        check(progressiveP95 <= PROGRESSIVE_P95_GATE_NS) { "progressive p95 latency regression: $progressiveP95 ns" }

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
                "pipeiPrefix": "匹|pei",
                "enter": "匹pei",
                "space": "匹配"
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

    private const val SAMPLE_COUNT = 7
    private const val WARMUP_LOOKUPS = 500
    private const val INITIALS_LOOKUPS = 20_000
    private const val PROGRESSIVE_LOOKUPS = 5_000
    private const val INITIALS_P95_GATE_NS = 250_000.0
    private const val PROGRESSIVE_P95_GATE_NS = 500_000.0
}
