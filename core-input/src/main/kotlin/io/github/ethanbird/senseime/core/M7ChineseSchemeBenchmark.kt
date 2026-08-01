package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Host gate for the complete staged T9 decode and compact Wubi86 read path. */
object M7ChineseSchemeBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) {
            "Usage: M7ChineseSchemeBenchmark <wubi-lexicon> <pinyin-lexicon> " +
                "<pinyin-bigrams> <pinyin-syllables> <report>"
        }
        val wubiFile = File(args[0])
        val pinyinFile = File(args[1])
        val bigramFile = File(args[2])
        val syllableFile = File(args[3])
        val report = File(args[4])
        report.parentFile?.mkdirs()

        lateinit var wubi: Wubi86Lexicon
        val wubiLoadNs = measureNanoTime {
            wubi = wubiFile.inputStream().buffered().use(Wubi86Lexicon::load)
        }
        lateinit var t9: T9SyllableIndex
        val t9BuildNs = measureNanoTime {
            t9 = T9SyllableIndex(syllableFile.readLines())
        }

        val target = compositionOf("486743697")
        check(t9.paths(target).any { it.formatted == "hun'shen'x's" }) {
            "T9 production inventory lost hun'shen'x's"
        }
        check(wubi.lookup("a").exact.firstOrNull()?.text == "工") {
            "Wubi production asset smoke test failed"
        }

        val t9Queries = arrayOf("486", "64426", "94664936", "7487832", "486743697")
            .map(::compositionOf)
        val bigrams = bigramFile.inputStream().buffered().use(BinaryCharacterBigramModel::load)
        val pinyin = pinyinFile.inputStream().buffered().use { input ->
            AdaptivePinyinDecoder(
                base = PinyinDecoder.load(input, bigrams),
                userLexicon = MemoryUserLexicon(),
                segmenter = PinyinSyllableSegmenter(syllableFile.readLines()),
            )
        }
        val completeCases = listOf(
            CompleteT9Case("short", compositionOf("486")),
            CompleteT9Case("highAmbiguityLong", compositionOf("94664936")),
            CompleteT9Case("hunshenxs", target),
            CompleteT9Case(
                name = "forcedBoundaryNihao",
                composition = compositionWithForcedJoint("64426", joint = 2),
                expectedFirstText = "\u4F60\u597D",
            ),
            CompleteT9Case(
                "boundary7426x8",
                compositionOf(WORST_T9_QUAD.repeat(8)),
            ),
            CompleteT9Case(
                "worst7426x24",
                compositionOf(WORST_T9_QUAD.repeat(WORST_T9_REPETITIONS)),
                requiresCandidates = false,
            ),
        )
        val targetDecoded = T9AlternativeInputDecoder.decode(
            composition = target,
            pathSource = t9,
            pinyinDecoder = pinyin,
            leftContext = "",
            limit = COMPLETE_DECODE_LIMIT,
        )
        check(targetDecoded.candidates.firstOrNull()?.text == "浑身解数") {
            "T9 complete decode lost hunshenxs Top1: ${targetDecoded.candidates.take(3)}"
        }
        check(targetDecoded.composingLabel == "hun'shen'x's") {
            "T9 resolved label regressed: ${targetDecoded.composingLabel}"
        }
        val wubiQueries = arrayOf("a", "b", "q", "r", "aaaa")
        val adaptiveWubi = AdaptiveWubi86Decoder(
            baseDecoder = Wubi86Decoder(wubi),
            userLexicon = MemoryWubiUserLexicon(),
        )
        repeat(WARMUP_ITERATIONS) { index ->
            check(t9.paths(t9Queries[index % t9Queries.size]).isNotEmpty())
            check(adaptiveWubi.decode(wubiQueries[index % wubiQueries.size], 32).isNotEmpty())
        }
        repeat(COMPLETE_WARMUP_ITERATIONS) { index ->
            val value = completeCases[index % completeCases.size]
            val decoded = T9AlternativeInputDecoder.decode(
                composition = value.composition,
                pathSource = t9,
                pinyinDecoder = pinyin,
                leftContext = "",
                limit = COMPLETE_DECODE_LIMIT,
            )
            check(!value.requiresCandidates || decoded.candidates.isNotEmpty())
            value.expectedFirstText?.let { expected ->
                check(decoded.candidates.firstOrNull()?.text == expected)
            }
        }

        val t9Samples = samples(LOOKUPS_PER_SAMPLE) { index ->
            check(t9.paths(t9Queries[index % t9Queries.size]).isNotEmpty())
        }
        val wubiSamples = samples(LOOKUPS_PER_SAMPLE) { index ->
            check(adaptiveWubi.decode(wubiQueries[index % wubiQueries.size], 32).isNotEmpty())
        }
        val completeSamplesByCase = Array(completeCases.size) { ArrayList<Long>() }
        val completeSamples = LongArray(COMPLETE_SAMPLE_COUNT) { index ->
            val caseIndex = index % completeCases.size
            val value = completeCases[caseIndex]
            measureNanoTime {
                val decoded = T9AlternativeInputDecoder.decode(
                    composition = value.composition,
                    pathSource = t9,
                    pinyinDecoder = pinyin,
                    leftContext = "",
                    limit = COMPLETE_DECODE_LIMIT,
                )
                check(!value.requiresCandidates || decoded.candidates.isNotEmpty())
                value.expectedFirstText?.let { expected ->
                    check(decoded.candidates.firstOrNull()?.text == expected)
                }
            }.also(completeSamplesByCase[caseIndex]::add)
        }
        val worstComposition = completeCases.last().composition
        val worstPathSamples = LongArray(WORST_PATH_SAMPLE_COUNT) {
            measureNanoTime { check(t9.paths(worstComposition).isNotEmpty()) }
        }
        val t9Median = percentile(t9Samples, 0.50) / LOOKUPS_PER_SAMPLE.toDouble()
        val t9P95 = percentile(t9Samples, 0.95) / LOOKUPS_PER_SAMPLE.toDouble()
        val t9Max = t9Samples.max().toDouble() / LOOKUPS_PER_SAMPLE
        val wubiMedian = percentile(wubiSamples, 0.50) / LOOKUPS_PER_SAMPLE.toDouble()
        val wubiP95 = percentile(wubiSamples, 0.95) / LOOKUPS_PER_SAMPLE.toDouble()
        val wubiMax = wubiSamples.max().toDouble() / LOOKUPS_PER_SAMPLE
        val completeP50 = percentile(completeSamples, 0.50).toDouble()
        val completeP95 = percentile(completeSamples, 0.95).toDouble()
        val completeP99 = percentile(completeSamples, 0.99).toDouble()
        val completeMax = completeSamples.max().toDouble()
        val worstPathP50 = percentile(worstPathSamples, 0.50).toDouble()
        val worstPathP95 = percentile(worstPathSamples, 0.95).toDouble()
        val worstPathMax = worstPathSamples.max().toDouble()
        val completeDiagnostics = completeCases.map { value ->
            value to T9AlternativeInputDecoder.decode(
                composition = value.composition,
                pathSource = t9,
                pinyinDecoder = pinyin,
                leftContext = "",
                limit = COMPLETE_DECODE_LIMIT,
            )
        }
        val completeCaseLatencies = completeCases.indices.map { index ->
            val values = completeSamplesByCase[index].toLongArray()
            CompleteCaseLatency(
                p50Ns = percentile(values, 0.50).toDouble(),
                p95Ns = percentile(values, 0.95).toDouble(),
                maxNs = values.max().toDouble(),
            )
        }

        check(wubiLoadNs <= MAX_WUBI_LOAD_NS) { "Wubi cold load exceeded 5 seconds" }
        check(t9BuildNs <= MAX_T9_BUILD_NS) { "T9 index build exceeded 2 seconds" }
        check(t9P95 <= MAX_T9_P95_NS) { "T9 DAG p95 exceeded 10 ms: $t9P95 ns" }
        check(worstPathP95 <= MAX_WORST_T9_PATH_P95_NS) {
            "T9 96-digit 7426 path p95 exceeded 50 ms: $worstPathP95 ns"
        }
        check(completeP95 <= MAX_COMPLETE_T9_P95_NS) {
            "T9 complete decode p95 exceeded 20 ms: $completeP95 ns"
        }
        completeCases.zip(completeCaseLatencies).forEach { (value, latency) ->
            check(latency.p95Ns <= MAX_COMPLETE_CASE_T9_P95_NS) {
                "T9 ${value.name} complete decode p95 exceeded 20 ms: ${latency.p95Ns} ns"
            }
        }
        check(wubiP95 <= MAX_WUBI_P95_NS) { "Wubi lookup p95 exceeded 2 ms: $wubiP95 ns" }
        check(wubi.metrics.estimatedRetainedBytes <= MAX_WUBI_RETAINED_BYTES) {
            "Wubi compact asset retained estimate exceeded 9 MiB"
        }

        report.writeText(
            """
            {
              "schemaVersion": 2,
              "stage": "M7",
              "generatedAt": "${Instant.now()}",
              "wubi": {
                "bytes": ${wubiFile.length()},
                "sha256": "${sha256(wubiFile)}",
                "coldLoadMs": ${decimal(wubiLoadNs / 1_000_000.0)},
                "estimatedRetainedBytes": ${wubi.metrics.estimatedRetainedBytes},
                "exactGroups": ${wubi.metrics.exactGroups},
                "prefixGroups": ${wubi.metrics.prefixGroups},
                "reverseEntries": ${wubi.metrics.reverseEntries},
                "medianLookupNs": ${decimal(wubiMedian)},
                "p95LookupNs": ${decimal(wubiP95)},
                "maxSampleLookupNs": ${decimal(wubiMax)}
              },
              "t9": {
                "inventorySha256": "${sha256(syllableFile)}",
                "indexBuildMs": ${decimal(t9BuildNs / 1_000_000.0)},
                "beamLimit": ${T9SyllableIndex.DEFAULT_MAX_PATHS},
                "medianPathNs": ${decimal(t9Median)},
                "p95PathNs": ${decimal(t9P95)},
                "maxSamplePathNs": ${decimal(t9Max)},
                "worstPath": {
                  "digits": "${worstComposition.rawDigits}",
                  "sampleCount": $WORST_PATH_SAMPLE_COUNT,
                  "p50Ns": ${decimal(worstPathP50)},
                  "p95Ns": ${decimal(worstPathP95)},
                  "maxNs": ${decimal(worstPathMax)}
                },
                "alternativeInputDecoder": {
                  "sampleUnit": "single-complete-decode",
                  "sampleCount": $COMPLETE_SAMPLE_COUNT,
                  "limit": $COMPLETE_DECODE_LIMIT,
                  "p50Ns": ${decimal(completeP50)},
                  "p95Ns": ${decimal(completeP95)},
                  "p99Ns": ${decimal(completeP99)},
                  "maxNs": ${decimal(completeMax)},
                  "cases": [
${completeDiagnostics.mapIndexed { index, (value, decoding) ->
                    val latency = completeCaseLatencies[index]
                    "                    { \"name\": \"${value.name}\", " +
                        "\"digits\": \"${value.composition.rawDigits}\", " +
                        "\"availablePaths\": ${decoding.availablePathCount}, " +
                        "\"probedQueries\": ${decoding.decodedQueryCount}, " +
                        "\"expandedQueries\": ${decoding.expandedQueryCount}, " +
                        "\"decodeInvocations\": ${decoding.decodeInvocationCount}, " +
                        "\"p50Ns\": ${decimal(latency.p50Ns)}, " +
                        "\"p95Ns\": ${decimal(latency.p95Ns)}, " +
                        "\"maxNs\": ${decimal(latency.maxNs)} }"
                }.joinToString(",\n")}
                  ]
                }
              },
              "deviceMetrics": {
                "status": "pending-real-device"
              }
            }
            """.trimIndent() + "\n",
        )
        println("M7 Chinese scheme gate written to ${report.absolutePath}")
    }

    private fun samples(iterations: Int, operation: (Int) -> Unit): LongArray =
        LongArray(SAMPLE_COUNT) {
            measureNanoTime { repeat(iterations, operation) }
        }

    private fun percentile(values: LongArray, fraction: Double): Long {
        val sorted = values.sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun compositionOf(digits: String): T9Composition =
        digits.fold(T9Composition()) { state, digit -> state.typeDigit(digit) }

    private fun compositionWithForcedJoint(digits: String, joint: Int): T9Composition {
        require(joint in 1 until digits.length)
        val prefix = compositionOf(digits.take(joint)).forceJoint()
        return digits.drop(joint).fold(prefix) { state, digit -> state.typeDigit(digit) }
    }

    private data class CompleteT9Case(
        val name: String,
        val composition: T9Composition,
        val requiresCandidates: Boolean = true,
        val expectedFirstText: String? = null,
    )

    private data class CompleteCaseLatency(
        val p50Ns: Double,
        val p95Ns: Double,
        val maxNs: Double,
    )

    private fun decimal(value: Double): String = "%.2f".format(Locale.US, value)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
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

    private const val SAMPLE_COUNT = 7
    private const val WARMUP_ITERATIONS = 300
    private const val LOOKUPS_PER_SAMPLE = 2_000
    private const val COMPLETE_WARMUP_ITERATIONS = 12
    private const val COMPLETE_SAMPLE_COUNT = 100
    private const val COMPLETE_DECODE_LIMIT = 64
    private const val WORST_PATH_SAMPLE_COUNT = 100
    private const val WORST_T9_QUAD = "7426"
    private const val WORST_T9_REPETITIONS = 24
    private const val MAX_WUBI_LOAD_NS = 5_000_000_000L
    private const val MAX_T9_BUILD_NS = 2_000_000_000L
    private const val MAX_T9_P95_NS = 10_000_000.0
    private const val MAX_WORST_T9_PATH_P95_NS = 50_000_000.0
    private const val MAX_COMPLETE_T9_P95_NS = 20_000_000.0
    private const val MAX_COMPLETE_CASE_T9_P95_NS = 20_000_000.0
    private const val MAX_WUBI_P95_NS = 2_000_000.0
    private const val MAX_WUBI_RETAINED_BYTES = 9L * 1024L * 1024L
}
