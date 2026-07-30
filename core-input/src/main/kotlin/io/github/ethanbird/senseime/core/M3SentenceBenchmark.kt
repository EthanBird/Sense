package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/**
 * Deterministic quality replay and bounded-latency gate for the production pinyin decoder.
 *
 * The replay intentionally keeps intent metadata separate from decoder mechanics. Modes and
 * frequency buckets make regressions diagnosable; every case still enters the same production
 * decoder API. A context column exercises [ContextualInputDecoder] when the fixture provides one.
 */
object M3SentenceBenchmark {
    enum class ReplayMode(val wireName: String) {
        FULL_PINYIN("full"),
        LONG_SENTENCE("sentence"),
        NAME("name"),
        COLLOQUIAL("colloquial"),
        TECHNICAL("technical"),
        TYPO("typo"),
        INITIALS("initials"),
        HYBRID("hybrid"),
        ;

        companion object {
            fun parse(value: String?): ReplayMode {
                val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
                if (normalized.isEmpty()) return FULL_PINYIN
                return entries.firstOrNull { it.wireName == normalized }
                    ?: error(
                        "Unknown replay mode '$value'; expected " +
                            entries.joinToString { it.wireName },
                    )
            }
        }
    }

    enum class FrequencyBucket(val wireName: String) {
        HEAD("head"),
        MID("mid"),
        TAIL("tail"),
        UNKNOWN("unknown"),
        ;

        companion object {
            fun parse(value: String?): FrequencyBucket {
                val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
                if (normalized.isEmpty()) return UNKNOWN
                return entries.firstOrNull { it.wireName == normalized }
                    ?: error(
                        "Unknown frequency bucket '$value'; expected " +
                            entries.joinToString { it.wireName },
                    )
            }
        }
    }

    data class ReplayCase(
        val query: String,
        val expected: String,
        val mode: ReplayMode = ReplayMode.FULL_PINYIN,
        val context: String? = null,
        val aliases: List<String> = emptyList(),
        val frequencyBucket: FrequencyBucket = FrequencyBucket.UNKNOWN,
    ) {
        val acceptedTexts: List<String> = listOf(expected) + aliases
    }

    data class QualityMetrics(
        val cases: Int,
        val top1: Int,
        val top3: Int,
        val top10: Int,
        val covered: Int,
        val meanReciprocalRank: Double,
    ) {
        val top1Rate: Double get() = rate(top1)
        val top3Rate: Double get() = rate(top3)
        val top10Rate: Double get() = rate(top10)
        val coverageRate: Double get() = rate(covered)

        private fun rate(value: Int): Double = if (cases == 0) 0.0 else value.toDouble() / cases
    }

    private data class ReplayObservation(
        val item: ReplayCase,
        val top1: String,
        val matchedText: String?,
        val rank: Int?,
        val returnedCandidates: Int,
    )

    private data class LimitEvaluation(
        val limit: Int,
        val baseline: List<ReplayObservation>,
        val contextual: List<ReplayObservation>,
    )

    private data class LatencySummary(
        val samples: Int,
        val p50Ns: Long,
        val p95Ns: Long,
        val p99Ns: Long,
    )

    private data class LimitLatency(
        val limit: Int,
        val baseline: LatencySummary,
        val contextual: LatencySummary,
    )

    private data class RankedMatch(val rank: Int, val text: String)

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
        check(model.size in MIN_BIGRAM_RECORDS..MAX_BIGRAM_RECORDS) {
            "Unexpected production bigram count: ${model.size}"
        }
        check(bigramFile.length() <= BIGRAM_SIZE_BUDGET_BYTES) {
            "Bigram asset exceeds the M3 size budget: ${bigramFile.length()} bytes"
        }

        val baseline = lexiconFile.inputStream().buffered().use(PinyinDecoder::load)
        val contextual = lexiconFile.inputStream().buffered().use { PinyinDecoder.load(it, model) }
        val synthetic = verifySyntheticContextGate()
        val replay = readReplay(replayFile)
        validateCorpus(replay)

        val evaluations = CANDIDATE_LIMITS.map { limit ->
            LimitEvaluation(
                limit = limit,
                baseline = evaluate(baseline, replay, limit),
                contextual = evaluate(contextual, replay, limit),
            )
        }
        val production = evaluations.single { it.limit == PRODUCTION_CANDIDATE_LIMIT }
        val baselineProductionMetrics = summarizeRanks(production.baseline.map { it.rank })
        val contextualProductionMetrics = summarizeRanks(production.contextual.map { it.rank })
        validateQualityGate(baselineProductionMetrics, contextualProductionMetrics)
        validateHybridGate(production)

        val cleanCases = replay.filter { it.mode != ReplayMode.TYPO }
        val latencyByLimit = CANDIDATE_LIMITS.map { limit ->
            warmUp(baseline, cleanCases, limit)
            warmUp(contextual, cleanCases, limit)
            LimitLatency(
                limit = limit,
                baseline = summarizeLatency(
                    measureCaseLatencies(baseline, cleanCases, limit, LATENCY_REPEATS),
                ),
                contextual = summarizeLatency(
                    measureCaseLatencies(contextual, cleanCases, limit, LATENCY_REPEATS),
                ),
            )
        }
        val typoCases = replay.filter { it.mode == ReplayMode.TYPO }
        warmUp(baseline, typoCases, PRODUCTION_CANDIDATE_LIMIT)
        warmUp(contextual, typoCases, PRODUCTION_CANDIDATE_LIMIT)
        val baselineTypoLatency = summarizeLatency(
            measureCaseLatencies(
                baseline,
                typoCases,
                PRODUCTION_CANDIDATE_LIMIT,
                TYPO_LATENCY_REPEATS,
            ),
        )
        val contextualTypoLatency = summarizeLatency(
            measureCaseLatencies(
                contextual,
                typoCases,
                PRODUCTION_CANDIDATE_LIMIT,
                TYPO_LATENCY_REPEATS,
            ),
        )
        validateLatencyGate(
            latencyByLimit.single { it.limit == PRODUCTION_CANDIDATE_LIMIT },
            contextualTypoLatency,
        )

        val improved = production.indices().count { index ->
            rankImproved(production.baseline[index].rank, production.contextual[index].rank)
        }
        val degraded = production.indices().count { index ->
            rankImproved(production.contextual[index].rank, production.baseline[index].rank)
        }

        report.writeText(
            buildReport(
                lexiconFile = lexiconFile,
                bigramFile = bigramFile,
                model = model,
                modelLoadNs = modelLoadNs,
                replay = replay,
                evaluations = evaluations,
                production = production,
                latencyByLimit = latencyByLimit,
                baselineTypoLatency = baselineTypoLatency,
                contextualTypoLatency = contextualTypoLatency,
                improved = improved,
                degraded = degraded,
                synthetic = synthetic,
            ),
        )
        println("M3 quality replay written to ${report.absolutePath}")
    }

    /**
     * Reads the six-column replay format while retaining compatibility with legacy two-column rows.
     *
     * Columns are: query, expected, mode, context, aliases separated by `|`, frequency bucket.
     * An optional un-commented header row is accepted. Blank optional cells retain their position.
     */
    fun readReplay(file: File): List<ReplayCase> {
        val values = ArrayList<ReplayCase>()
        file.useLines { lines ->
            lines.forEachIndexed { zeroBasedIndex, rawLine ->
                val lineNumber = zeroBasedIndex + 1
                if (rawLine.isBlank() || rawLine.trimStart().startsWith('#')) return@forEachIndexed
                require(rawLine.count { it == '\t' } < MAX_REPLAY_COLUMNS) {
                    "Replay row $lineNumber has more than $MAX_REPLAY_COLUMNS columns"
                }
                val columns = rawLine.trimEnd('\r').split('\t').map(String::trim)
                if (
                    columns.getOrNull(0).equals("query", ignoreCase = true) &&
                    columns.getOrNull(1).equals("expected", ignoreCase = true)
                ) {
                    return@forEachIndexed
                }
                require(columns.size >= LEGACY_REPLAY_COLUMNS) {
                    "Replay row $lineNumber must have query and expected text"
                }

                val query = columns[0]
                require(query.isNotEmpty() && query.all { it in 'a'..'z' }) {
                    "Replay query must contain lowercase ASCII pinyin at row $lineNumber: $query"
                }
                val expected = columns[1]
                require(expected.isNotEmpty()) {
                    "Replay expected text is empty for $query at row $lineNumber"
                }
                val mode = ReplayMode.parse(columns.getOrNull(2))
                val context = columns.getOrNull(3)?.takeIf(String::isNotEmpty)
                val aliases = columns.getOrNull(4)
                    .orEmpty()
                    .split(ALIAS_SEPARATOR)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                require(expected !in aliases) {
                    "Replay aliases repeat expected text for $query at row $lineNumber"
                }
                val frequencyBucket = FrequencyBucket.parse(columns.getOrNull(5))
                values += ReplayCase(
                    query = query,
                    expected = expected,
                    mode = mode,
                    context = context,
                    aliases = aliases,
                    frequencyBucket = frequencyBucket,
                )
            }
        }

        val duplicate = values
            .groupBy { Triple(it.query, it.mode, it.context) }
            .entries
            .firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            val (query, mode, context) = duplicate!!.key
            "Replay identity must be unique: query=$query, mode=${mode.wireName}, context=$context"
        }
        return values
    }

    fun rankOf(candidates: List<Candidate>, expected: String): Int? =
        candidates.indexOfFirst { it.text == expected }.takeIf { it >= 0 }?.plus(1)

    fun rankOf(candidates: List<Candidate>, item: ReplayCase): Int? =
        findAcceptedMatch(candidates, item).firstOrNull()?.rank

    fun summarizeRanks(ranks: List<Int?>): QualityMetrics {
        val reciprocalRank = ranks.sumOf { rank -> rank?.let { 1.0 / it } ?: 0.0 }
        return QualityMetrics(
            cases = ranks.size,
            top1 = ranks.count { it == 1 },
            top3 = ranks.count { it != null && it <= 3 },
            top10 = ranks.count { it != null && it <= 10 },
            covered = ranks.count { it != null },
            meanReciprocalRank = if (ranks.isEmpty()) 0.0 else reciprocalRank / ranks.size,
        )
    }

    fun nearestRankIndex(sampleCount: Int, percentile: Double): Int {
        require(sampleCount > 0)
        require(percentile in 0.0..1.0)
        return (ceil(percentile * sampleCount).toInt() - 1).coerceIn(0, sampleCount - 1)
    }

    private fun validateCorpus(replay: List<ReplayCase>) {
        check(replay.size in MIN_REPLAY_CASES..MAX_REPLAY_CASES) {
            "Quality replay must contain $MIN_REPLAY_CASES..$MAX_REPLAY_CASES cases; got ${replay.size}"
        }
        ReplayMode.entries.forEach { mode ->
            val cases = replay.count { it.mode == mode }
            check(cases >= MIN_CASES_PER_MODE) {
                "Quality replay mode ${mode.wireName} needs at least $MIN_CASES_PER_MODE cases; got $cases"
            }
        }
        check(replay.any { it.context != null }) { "Quality replay must exercise editor context" }
        check(replay.any { it.aliases.isNotEmpty() }) { "Quality replay must exercise accepted aliases" }
        check(replay.any { it.frequencyBucket == FrequencyBucket.TAIL }) {
            "Quality replay must include tail-frequency cases"
        }
    }

    private fun validateQualityGate(
        baseline: QualityMetrics,
        contextual: QualityMetrics,
    ) {
        check(contextual.coverageRate >= MIN_PRODUCTION_COVERAGE_RATE) {
            "Production coverage regression: ${formatRate(contextual.coverageRate)}"
        }
        check(contextual.top10Rate >= MIN_PRODUCTION_TOP10_RATE) {
            "Production Top-10 regression: ${formatRate(contextual.top10Rate)}"
        }
        check(contextual.top1Rate >= MIN_PRODUCTION_TOP1_RATE) {
            "Production Top-1 regression: ${formatRate(contextual.top1Rate)}"
        }
        check(contextual.meanReciprocalRank >= MIN_PRODUCTION_MRR) {
            "Production MRR regression: ${formatRate(contextual.meanReciprocalRank)}"
        }
        check(contextual.covered + MAX_ALLOWED_CONTEXTUAL_COVERAGE_LOSS >= baseline.covered) {
            "Context model lost too many covered expectations: ${baseline.covered} -> ${contextual.covered}"
        }
        check(contextual.top10 + MAX_ALLOWED_CONTEXTUAL_TOP10_LOSS >= baseline.top10) {
            "Context model lost too many Top-10 expectations: ${baseline.top10} -> ${contextual.top10}"
        }
        check(contextual.top1 >= baseline.top1) {
            "Context model lost Top-1 expectations: ${baseline.top1} -> ${contextual.top1}"
        }
        check(contextual.meanReciprocalRank + MAX_ALLOWED_CONTEXTUAL_MRR_LOSS >= baseline.meanReciprocalRank) {
            "Context model regressed MRR: ${formatRate(baseline.meanReciprocalRank)} -> " +
                formatRate(contextual.meanReciprocalRank)
        }
    }

    private fun validateHybridGate(production: LimitEvaluation) {
        val baselineHybrid = production.baseline.filter { it.item.mode == ReplayMode.HYBRID }
        val contextualHybrid = production.contextual.filter { it.item.mode == ReplayMode.HYBRID }
        check(baselineHybrid.count { it.rank == 1 } >= MIN_PRODUCTION_HYBRID_TOP1) {
            "Baseline hybrid Top-1 regression"
        }
        check(contextualHybrid.count { it.rank == 1 } >= MIN_PRODUCTION_HYBRID_TOP1) {
            "Contextual hybrid Top-1 regression"
        }
        val sentinelIndex = baselineHybrid.indexOfFirst { it.item.query == HYBRID_CORRECTION_SENTINEL_QUERY }
        check(sentinelIndex >= 0) {
            "Hybrid replay is missing $HYBRID_CORRECTION_SENTINEL_QUERY"
        }
        check(baselineHybrid[sentinelIndex].rank == 1 && contextualHybrid[sentinelIndex].rank == 1) {
            "Spelling correction displaced the exact hybrid sentinel"
        }
    }

    private fun validateLatencyGate(
        production: LimitLatency,
        contextualTypo: LatencySummary,
    ) {
        val relativeP95Limit = (
            production.baseline.p95Ns * CONTEXT_P95_GATE_MULTIPLIER +
                CONTEXT_P95_GATE_SLACK_NS
            ).toLong()
        check(production.contextual.p95Ns <= relativeP95Limit) {
            "Context ranking latency regressed: ${production.contextual.p95Ns} ns > " +
                "$relativeP95Limit ns"
        }
        check(production.contextual.p95Ns <= CLEAN_SENTENCE_P95_LIMIT_NS) {
            "Clean production replay exceeded p95 host budget: ${production.contextual.p95Ns} ns"
        }
        check(production.contextual.p99Ns <= CLEAN_SENTENCE_P99_LIMIT_NS) {
            "Clean production replay exceeded p99 host budget: ${production.contextual.p99Ns} ns"
        }
        check(contextualTypo.p95Ns <= TYPO_P95_LIMIT_NS) {
            "Typo replay exceeded p95 host budget: ${contextualTypo.p95Ns} ns"
        }
        check(contextualTypo.p99Ns <= TYPO_P99_LIMIT_NS) {
            "Typo replay exceeded p99 host budget: ${contextualTypo.p99Ns} ns"
        }
    }

    private fun evaluate(
        decoder: InputDecoder,
        replay: List<ReplayCase>,
        limit: Int,
    ): List<ReplayObservation> = replay.map { item ->
        val candidates = decodeCase(decoder, item, limit)
        val match = findAcceptedMatch(candidates, item).firstOrNull()
        ReplayObservation(
            item = item,
            top1 = candidates.firstOrNull()?.text.orEmpty(),
            matchedText = match?.text,
            rank = match?.rank,
            returnedCandidates = candidates.size,
        )
    }

    private fun findAcceptedMatch(
        candidates: List<Candidate>,
        item: ReplayCase,
    ): Sequence<RankedMatch> {
        val accepted = item.acceptedTexts.toHashSet()
        return candidates.asSequence()
            .mapIndexedNotNull { index, candidate ->
                candidate.text.takeIf(accepted::contains)?.let { RankedMatch(index + 1, it) }
            }
    }

    private fun decodeCase(
        decoder: InputDecoder,
        item: ReplayCase,
        limit: Int,
    ): List<Candidate> {
        val context = item.context
        return if (context != null && decoder is ContextualInputDecoder) {
            decoder.decodeAfter(context.codePointBefore(context.length), item.query, limit)
        } else {
            decoder.decode(item.query, limit)
        }
    }

    private fun warmUp(decoder: InputDecoder, replay: List<ReplayCase>, limit: Int) {
        repeat(WARMUP_REPEATS) {
            replay.forEach { decodeCase(decoder, it, limit) }
        }
    }

    private fun measureCaseLatencies(
        decoder: InputDecoder,
        replay: List<ReplayCase>,
        limit: Int,
        repeats: Int,
    ): LongArray {
        if (replay.isEmpty()) return longArrayOf(0L)
        return LongArray(replay.size * repeats) { sample ->
            val item = replay[sample % replay.size]
            measureNanoTime { decodeCase(decoder, item, limit) }
        }
    }

    private fun summarizeLatency(samples: LongArray): LatencySummary {
        val sorted = samples.sortedArray()
        return LatencySummary(
            samples = sorted.size,
            p50Ns = sorted[nearestRankIndex(sorted.size, 0.50)],
            p95Ns = sorted[nearestRankIndex(sorted.size, 0.95)],
            p99Ns = sorted[nearestRankIndex(sorted.size, 0.99)],
        )
    }

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

    private fun buildReport(
        lexiconFile: File,
        bigramFile: File,
        model: BinaryCharacterBigramModel,
        modelLoadNs: Long,
        replay: List<ReplayCase>,
        evaluations: List<LimitEvaluation>,
        production: LimitEvaluation,
        latencyByLimit: List<LimitLatency>,
        baselineTypoLatency: LatencySummary,
        contextualTypoLatency: LatencySummary,
        improved: Int,
        degraded: Int,
        synthetic: Pair<String, String>,
    ): String {
        val qualityByLimit = evaluations.joinToString(",\n") { evaluation ->
            val baselineMetrics = summarizeRanks(evaluation.baseline.map { it.rank })
            val contextualMetrics = summarizeRanks(evaluation.contextual.map { it.rank })
            """        "${evaluation.limit}": {"baseline":${metricsJson(baselineMetrics)},"contextual":${metricsJson(contextualMetrics)}}"""
        }
        val latencyLimits = latencyByLimit.joinToString(",\n") { latency ->
            """        "${latency.limit}": {"baseline":${latencyJson(latency.baseline)},"contextual":${latencyJson(latency.contextual)}}"""
        }
        val resultRows = production.baseline.indices.joinToString(",\n") { index ->
            val before = production.baseline[index]
            val after = production.contextual[index]
            val item = before.item
            val aliases = item.aliases.joinToString(",", "[", "]") { "\"${json(it)}\"" }
            """          {"query":"${json(item.query)}","expected":"${json(item.expected)}","mode":"${item.mode.wireName}","context":${nullableJson(item.context)},"aliases":$aliases,"frequencyBucket":"${item.frequencyBucket.wireName}","baselineTop1":"${json(before.top1)}","contextualTop1":"${json(after.top1)}","baselineRank":${before.rank ?: "null"},"contextualRank":${after.rank ?: "null"},"baselineMatched":${nullableJson(before.matchedText)},"contextualMatched":${nullableJson(after.matchedText)},"baselineCandidates":${before.returnedCandidates},"contextualCandidates":${after.returnedCandidates}}"""
        }
        val contextualProductionMetrics = summarizeRanks(production.contextual.map { it.rank })
        val modeMetrics = dimensionMetricsJson(production) { it.mode.wireName }
        val frequencyMetrics = dimensionMetricsJson(production) { it.frequencyBucket.wireName }
        val modeCounts = ReplayMode.entries.joinToString(",") { mode ->
            """"${mode.wireName}":${replay.count { it.mode == mode }}"""
        }
        val frequencyCounts = FrequencyBucket.entries.joinToString(",") { bucket ->
            """"${bucket.wireName}":${replay.count { it.frequencyBucket == bucket }}"""
        }
        return """
            {
              "schemaVersion": 2,
              "stage": "quality-replay",
              "generatedAt": "${Instant.now()}",
              "assets": {
                "lexiconBytes": ${lexiconFile.length()},
                "lexiconSha256": "${sha256(lexiconFile)}",
                "bigramRecords": ${model.size},
                "bigramBytes": ${bigramFile.length()},
                "bigramSha256": "${sha256(bigramFile)}",
                "bigramLoadMs": ${formatMs(modelLoadNs)}
              },
              "corpus": {
                "cases": ${replay.size},
                "contextCases": ${replay.count { it.context != null }},
                "aliasCases": ${replay.count { it.aliases.isNotEmpty() }},
                "modeCounts": {$modeCounts},
                "frequencyBucketCounts": {$frequencyCounts}
              },
              "syntheticContextGate": {
                "query": "woshiren",
                "expected": "我是人",
                "baselineTop1": "${json(synthetic.first)}",
                "contextualTop1": "${json(synthetic.second)}",
                "status": "pass"
              },
              "quality": {
                "productionCandidateLimit": $PRODUCTION_CANDIDATE_LIMIT,
                "candidateLimits": [${CANDIDATE_LIMITS.joinToString()}],
                "byLimit": {
$qualityByLimit
                },
                "byModeAtProductionLimit": $modeMetrics,
                "byFrequencyBucketAtProductionLimit": $frequencyMetrics,
                "rankMovement": {
                  "improved": $improved,
                  "degraded": $degraded,
                  "unchanged": ${replay.size - improved - degraded}
                },
                "results": [
$resultRows
                ]
              },
              "latency": {
                "unit": "nanoseconds",
                "cleanCasesExcludeMode": "typo",
                "cleanByCandidateLimit": {
$latencyLimits
                },
                "typoAtProductionLimit": {
                  "baseline": ${latencyJson(baselineTypoLatency)},
                  "contextual": ${latencyJson(contextualTypoLatency)}
                }
              },
              "gates": {
                "minimumCoverageRate": $MIN_PRODUCTION_COVERAGE_RATE,
                "minimumTop10Rate": $MIN_PRODUCTION_TOP10_RATE,
                "minimumTop1Rate": $MIN_PRODUCTION_TOP1_RATE,
                "minimumMrr": $MIN_PRODUCTION_MRR,
                "observedCoverageRate": ${formatRate(contextualProductionMetrics.coverageRate)},
                "observedTop10Rate": ${formatRate(contextualProductionMetrics.top10Rate)},
                "observedTop1Rate": ${formatRate(contextualProductionMetrics.top1Rate)},
                "observedMrr": ${formatRate(contextualProductionMetrics.meanReciprocalRank)},
                "status": "pass"
              },
              "deviceMetrics": {"status": "pending-real-device"}
            }
        """.trimIndent() + "\n"
    }

    private fun dimensionMetricsJson(
        evaluation: LimitEvaluation,
        dimension: (ReplayCase) -> String,
    ): String {
        val baselineByKey = evaluation.baseline.groupBy { dimension(it.item) }
        val contextualByKey = evaluation.contextual.groupBy { dimension(it.item) }
        return baselineByKey.keys.sorted().joinToString(",", "{", "}") { key ->
            val baseline = summarizeRanks(baselineByKey.getValue(key).map { it.rank })
            val contextual = summarizeRanks(contextualByKey.getValue(key).map { it.rank })
            """"${json(key)}":{"baseline":${metricsJson(baseline)},"contextual":${metricsJson(contextual)}}"""
        }
    }

    private fun metricsJson(metrics: QualityMetrics): String =
        """{"cases":${metrics.cases},"top1":${metrics.top1},"top3":${metrics.top3},"top10":${metrics.top10},"covered":${metrics.covered},"top1Rate":${formatRate(metrics.top1Rate)},"top3Rate":${formatRate(metrics.top3Rate)},"top10Rate":${formatRate(metrics.top10Rate)},"coverageRate":${formatRate(metrics.coverageRate)},"mrr":${formatRate(metrics.meanReciprocalRank)}}"""

    private fun latencyJson(latency: LatencySummary): String =
        """{"samples":${latency.samples},"p50Ns":${latency.p50Ns},"p95Ns":${latency.p95Ns},"p99Ns":${latency.p99Ns},"p50Ms":${formatMs(latency.p50Ns)},"p95Ms":${formatMs(latency.p95Ns)},"p99Ms":${formatMs(latency.p99Ns)}}"""

    private fun rankImproved(before: Int?, after: Int?): Boolean =
        after != null && (before == null || after < before)

    private fun LimitEvaluation.indices(): IntRange = baseline.indices

    private fun nullableJson(value: String?): String =
        value?.let { "\"${json(it)}\"" } ?: "null"

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

    private fun formatMs(nanoseconds: Long): String =
        "%.3f".format(Locale.US, nanoseconds / 1_000_000.0)

    private fun formatRate(value: Double): String = "%.4f".format(Locale.US, value)

    private const val LEGACY_REPLAY_COLUMNS = 2
    private const val MAX_REPLAY_COLUMNS = 6
    private const val ALIAS_SEPARATOR = '|'
    private const val MIN_REPLAY_CASES = 80
    private const val MAX_REPLAY_CASES = 500
    private const val MIN_CASES_PER_MODE = 8
    private const val MIN_BIGRAM_RECORDS = 40_000
    private const val MAX_BIGRAM_RECORDS = 65_536
    private const val BIGRAM_SIZE_BUDGET_BYTES = 800L * 1024L
    private val CANDIDATE_LIMITS = listOf(10, 64, 255)
    private const val PRODUCTION_CANDIDATE_LIMIT = 255
    private const val WARMUP_REPEATS = 1
    private const val LATENCY_REPEATS = 2
    private const val TYPO_LATENCY_REPEATS = 3
    private const val MIN_PRODUCTION_COVERAGE_RATE = 0.93
    private const val MIN_PRODUCTION_TOP10_RATE = 0.85
    private const val MIN_PRODUCTION_TOP1_RATE = 0.70
    private const val MIN_PRODUCTION_MRR = 0.75
    private const val MIN_PRODUCTION_HYBRID_TOP1 = 10
    private const val HYBRID_CORRECTION_SENTINEL_QUERY = "rengzn"
    private const val MAX_ALLOWED_CONTEXTUAL_COVERAGE_LOSS = 1
    private const val MAX_ALLOWED_CONTEXTUAL_TOP10_LOSS = 2
    private const val MAX_ALLOWED_CONTEXTUAL_MRR_LOSS = 0.01
    private const val CONTEXT_P95_GATE_MULTIPLIER = 1.35
    private const val CONTEXT_P95_GATE_SLACK_NS = 500_000.0
    private const val CLEAN_SENTENCE_P95_LIMIT_NS = 35_000_000L
    private const val CLEAN_SENTENCE_P99_LIMIT_NS = 60_000_000L
    private const val TYPO_P95_LIMIT_NS = 40_000_000L
    private const val TYPO_P99_LIMIT_NS = 70_000_000L
}
