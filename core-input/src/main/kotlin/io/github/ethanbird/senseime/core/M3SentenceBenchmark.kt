package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Deterministic replay and latency gate for M3 sentence ranking. */
object M3SentenceBenchmark {
    data class ReplayCase(val query: String, val expected: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "Usage: M3SentenceBenchmark <lexicon> <bigram> <replay-tsv> <report>"
        }
        val lexiconFile = File(args[0])
        val bigramFile = File(args[1])
        val replayFile = File(args[2])
        val report = File(args[3])
        report.parentFile?.mkdirs()

        lateinit var model: BinaryCharacterBigramModel
        val modelLoadNs = measureNanoTime {
            model = bigramFile.inputStream().buffered().use(BinaryCharacterBigramModel::load)
        }
        check(model.size in 40_000..65_536) { "Unexpected production bigram count: ${model.size}" }
        check(bigramFile.length() < 700 * 1024) { "Bigram asset exceeds the M3 size budget" }

        val baseline = lexiconFile.inputStream().buffered().use(PinyinDecoder::load)
        val contextual = lexiconFile.inputStream().buffered().use { PinyinDecoder.load(it, model) }
        val synthetic = verifySyntheticContextGate()

        val replay = readReplay(replayFile)
        check(replay.size >= 8) { "M3 replay must contain at least eight sentence cases" }
        val rows = replay.map { item ->
            val before = baseline.decode(item.query, CANDIDATE_LIMIT)
            val after = contextual.decode(item.query, CANDIDATE_LIMIT)
            val beforeRank = rankOf(before, item.expected)
            val afterRank = rankOf(after, item.expected)
            check(afterRank == 1) {
                "Sentence replay failed for ${item.query}: expected ${item.expected}, got ${after.take(5)}"
            }
            ReplayResult(
                item = item,
                baselineTop1 = before.firstOrNull()?.text.orEmpty(),
                contextualTop1 = after.firstOrNull()?.text.orEmpty(),
                baselineRank = beforeRank,
                contextualRank = afterRank,
            )
        }

        val cleanQueries = replay.map(ReplayCase::query)
        repeat(2) {
            cleanQueries.forEach { query ->
                baseline.decode(query, CANDIDATE_LIMIT)
                contextual.decode(query, CANDIDATE_LIMIT)
            }
        }
        val cleanLookupsPerSample = cleanQueries.size * CLEAN_REPEATS
        val baselineSamples = measureSamples(baseline, cleanQueries, CLEAN_REPEATS, SAMPLE_COUNT)
        val contextualSamples = measureSamples(contextual, cleanQueries, CLEAN_REPEATS, SAMPLE_COUNT)
        val baselineP95Ns = perLookup(baselineSamples, cleanLookupsPerSample, 0.95)
        val contextualP95Ns = perLookup(contextualSamples, cleanLookupsPerSample, 0.95)
        val latencyLimitNs = baselineP95Ns * CONTEXT_P95_GATE_MULTIPLIER + CONTEXT_P95_GATE_SLACK_NS
        check(contextualP95Ns <= latencyLimitNs) {
            "Context ranking latency regressed: $contextualP95Ns ns > $latencyLimitNs ns"
        }
        check(contextualP95Ns <= CLEAN_SENTENCE_P95_LIMIT_NS) {
            "Clean sentence lookup exceeded the host budget: $contextualP95Ns ns"
        }

        val typoQuery = "woshiyigezhongguoern"
        val typoExpected = "我是一个中国人"
        val typoSamples = LongArray(5) {
            measureNanoTime {
                repeat(5) {
                    check(contextual.decode(typoQuery, 6).firstOrNull()?.text == typoExpected) {
                        "Long sentence typo replay failed"
                    }
                }
            }
        }
        val typoP95Ns = perLookup(typoSamples, 5, 0.95)
        check(typoP95Ns <= LONG_TYPO_P95_LIMIT_NS) {
            "Long sentence typo lookup exceeded the host budget: $typoP95Ns ns"
        }

        val improved = rows.count {
            it.contextualRank != null && (it.baselineRank == null || it.contextualRank < it.baselineRank)
        }
        val degraded = rows.count {
            it.baselineRank != null && (it.contextualRank == null || it.contextualRank > it.baselineRank)
        }
        check(improved > 0) { "Production replay did not demonstrate a context-ranking improvement" }
        check(degraded == 0) { "Production replay contains a context-ranking regression" }
        val replayJson = rows.joinToString(",\n") { row ->
            """    {"query":"${json(row.item.query)}","expected":"${json(row.item.expected)}","baselineTop1":"${json(row.baselineTop1)}","contextualTop1":"${json(row.contextualTop1)}","baselineRank":${row.baselineRank ?: "null"},"contextualRank":${row.contextualRank ?: "null"}}"""
        }
        report.writeText(
            """
            {
              "schemaVersion": 1,
              "stage": "M3",
              "generatedAt": "${Instant.now()}",
              "bigramModel": {
                "records": ${model.size},
                "bytes": ${bigramFile.length()},
                "sha256": "${sha256(bigramFile)}",
                "loadMs": ${format(modelLoadNs / 1_000_000.0)}
              },
              "syntheticContextGate": {
                "query": "woshiren",
                "expected": "我是人",
                "baselineTop1": "${json(synthetic.first)}",
                "contextualTop1": "${json(synthetic.second)}",
                "status": "pass"
              },
              "replay": {
                "cases": ${rows.size},
                "contextualTop1Passes": ${rows.count { it.contextualRank == 1 }},
                "improvedExpectedRanks": $improved,
                "degradedExpectedRanks": $degraded,
                "results": [
            $replayJson
                ]
              },
              "cleanSentenceLookup": {
                "samples": $SAMPLE_COUNT,
                "lookupsPerSample": $cleanLookupsPerSample,
                "baselineMedianMs": ${format(perLookup(baselineSamples, cleanLookupsPerSample, 0.5) / 1_000_000.0)},
                "baselineP95Ms": ${format(baselineP95Ns / 1_000_000.0)},
                "contextualMedianMs": ${format(perLookup(contextualSamples, cleanLookupsPerSample, 0.5) / 1_000_000.0)},
                "contextualP95Ms": ${format(contextualP95Ns / 1_000_000.0)},
                "p95GateMultiplier": $CONTEXT_P95_GATE_MULTIPLIER,
                "p95GateSlackMs": ${format(CONTEXT_P95_GATE_SLACK_NS / 1_000_000.0)},
                "absoluteP95LimitMs": ${format(CLEAN_SENTENCE_P95_LIMIT_NS / 1_000_000.0)}
              },
              "longSentenceTypoLookup": {
                "medianMs": ${format(perLookup(typoSamples, 5, 0.5) / 1_000_000.0)},
                "p95Ms": ${format(typoP95Ns / 1_000_000.0)},
                "absoluteP95LimitMs": ${format(LONG_TYPO_P95_LIMIT_NS / 1_000_000.0)}
              },
              "deviceMetrics": {"status": "pending-real-device"}
            }
            """.trimIndent() + "\n",
        )
        println("M3 sentence replay written to ${report.absolutePath}")
    }

    fun readReplay(file: File): List<ReplayCase> {
        val values = file.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapIndexed { index, line ->
                val columns = line.split('\t')
                require(columns.size == 2) { "Replay row ${index + 1} must have query and expected text" }
                val query = columns[0]
                require(query.isNotEmpty() && query.all { it in 'a'..'z' }) {
                    "Replay query must contain lowercase ASCII pinyin: $query"
                }
                require(columns[1].isNotEmpty()) { "Replay expected text is empty for $query" }
                ReplayCase(query, columns[1])
            }
        require(values.map(ReplayCase::query).distinct().size == values.size) { "Replay queries must be unique" }
        return values
    }

    fun rankOf(candidates: List<Candidate>, expected: String): Int? =
        candidates.indexOfFirst { it.text == expected }.takeIf { it >= 0 }?.plus(1)

    fun nearestRankIndex(sampleCount: Int, percentile: Double): Int {
        require(sampleCount > 0)
        require(percentile in 0.0..1.0)
        return (ceil(percentile * sampleCount).toInt() - 1).coerceIn(0, sampleCount - 1)
    }

    private data class ReplayResult(
        val item: ReplayCase,
        val baselineTop1: String,
        val contextualTop1: String,
        val baselineRank: Int?,
        val contextualRank: Int?,
    )

    private fun verifySyntheticContextGate(): Pair<String, String> {
        val data = syntheticLexicon()
        val baseline = PinyinDecoder.fromBytes(data)
        val contextual = PinyinDecoder.fromBytes(data, CharacterBigramModel { previous, next ->
            if (previous == '我'.code && next == '是'.code) 2f else 0f
        })
        val baselineTop1 = baseline.decode("woshiren").firstOrNull()?.text.orEmpty()
        val contextualTop1 = contextual.decode("woshiren").firstOrNull()?.text.orEmpty()
        check(baselineTop1 == "我时人")
        check(contextualTop1 == "我是人")
        return baselineTop1 to contextualTop1
    }

    private fun syntheticLexicon(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            val records = listOf(
                "ren" to listOf("人" to 1000),
                "shi" to listOf("时" to 1200, "是" to 1000),
                "wo" to listOf("我" to 1000),
            )
            output.writeBytes("SPLX")
            output.writeShort(3)
            output.writeInt(records.size)
            records.forEach { (code, candidates) ->
                output.writeByte(code.length)
                output.writeBytes(code)
                output.writeByte(candidates.size)
                candidates.forEach { (text, weight) ->
                    val encoded = text.toByteArray(Charsets.UTF_8)
                    output.writeByte(encoded.size)
                    output.write(encoded)
                    output.writeInt(weight)
                    output.writeByte(1)
                    output.writeBytes(code.take(1))
                    output.writeByte(0)
                }
            }
        }
    }.toByteArray()

    private fun measureSamples(
        decoder: InputDecoder,
        queries: List<String>,
        repeats: Int,
        samples: Int,
    ): LongArray = LongArray(samples) {
        measureNanoTime {
            repeat(repeats) {
                queries.forEach { query -> check(decoder.decode(query, CANDIDATE_LIMIT).isNotEmpty()) }
            }
        }
    }

    private fun perLookup(samples: LongArray, lookups: Int, percentile: Double): Double =
        samples.sorted()[nearestRankIndex(samples.size, percentile)].toDouble() / lookups

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

    private fun json(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private fun format(value: Double): String = "%.2f".format(Locale.US, value)

    private const val CANDIDATE_LIMIT = 32
    private const val CLEAN_REPEATS = 5
    private const val SAMPLE_COUNT = 7
    private const val CONTEXT_P95_GATE_MULTIPLIER = 1.2
    private const val CONTEXT_P95_GATE_SLACK_NS = 250_000.0
    private const val CLEAN_SENTENCE_P95_LIMIT_NS = 35_000_000.0
    private const val LONG_TYPO_P95_LIMIT_NS = 30_000_000.0
}
