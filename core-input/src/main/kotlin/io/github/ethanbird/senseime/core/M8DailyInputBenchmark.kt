package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Measures the actual full-pinyin progressive API, including unfinished keystrokes and prefixes. */
object M8DailyInputBenchmark {
    private const val LIMIT = 255

    data class Case(val query: String, val expected: String, val category: String, val context: String, val aliases: List<String>)

    fun readCases(file: File): List<Case> = file.readLines().mapIndexedNotNull { index, line ->
        if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
        val fields = line.split('\t')
        require(fields.size in 3..5) { "Invalid daily-input row ${index + 1}" }
        val query = fields[0].replace(" ", "")
        require(query.isNotEmpty() && query.all { it in 'a'..'z' }) { "Invalid full pinyin at row ${index + 1}" }
        require(fields[1].isNotEmpty() && fields[2].isNotBlank())
        Case(query, fields[1], fields[2], fields.getOrElse(3) { "" }, fields.getOrElse(4) { "" }.split('|').filter(String::isNotBlank))
    }.also { cases ->
        require(cases.isNotEmpty())
        require(cases.distinctBy { it.query to it.context }.size == cases.size) { "Duplicate daily-input case" }
    }

    /** Unicode scalar edit distance; never treats a supplementary Han character as two errors. */
    fun editDistance(actual: String, expected: String): Int {
        val a = actual.codePoints().toArray()
        val b = expected.codePoints().toArray()
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 6) { "Usage: M8DailyInputBenchmark <lexicon> <bigram> <syllables> <english> <replay> <report>" }
        val files = args.map(::File)
        val cases = readCases(files[4])
        val syllables = files[2].readLines().toSet()
        val segmenter = PinyinSyllableSegmenter(syllables)
        cases.forEach { case ->
            require(hasSyllableCount(case.query, case.expected.codePointCount(0, case.expected.length), syllables)) {
                "Pinyin/gold length mismatch: ${case.query} -> ${case.expected}"
            }
        }
        val bigrams = files[1].inputStream().buffered().use(BinaryCharacterBigramModel::load)
        lateinit var decoder: AdaptivePinyinDecoder
        val loadNs = measureNanoTime {
            val base = files[0].inputStream().buffered().use { PinyinDecoder.load(it, bigrams) }
            decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(), segmenter, EnglishLexicon.load(files[3].inputStream()))
        }
        val firstPass = ArrayList<Long>()
        val warmedPass = ArrayList<Long>()
        val observations = ArrayList<String>()
        var top1 = 0
        var top3 = 0
        var top10 = 0
        var covered = 0
        var reciprocalRanks = 0.0
        var characterErrors = 0
        var characters = 0
        val categoryCounts = linkedMapOf<String, IntArray>()
        repeat(2) { pass ->
            cases.forEach { case ->
                var composing = PinyinComposition()
                var result: ProgressivePinyinDecoding? = null
                case.query.forEach { key ->
                    composing = composing.type(key)
                    val duration = measureNanoTime { result = decoder.decodeProgressively(composing, case.context, LIMIT) }
                    (if (pass == 0) firstPass else warmedPass).add(duration)
                    check(result!!.revision == composing.revision && result!!.remainingPinyin == composing.remainingPinyin)
                }
                if (pass == 0) {
                    val candidates = result!!.wholeCandidates
                    val accepted = listOf(case.expected) + case.aliases
                    val rank = candidates.indexOfFirst { it.text in accepted }.takeIf { it >= 0 }?.plus(1)
                    val first = candidates.firstOrNull()?.text.orEmpty()
                    if (rank == 1) top1++
                    if (rank != null && rank <= 3) top3++
                    if (rank != null && rank <= 10) top10++
                    if (rank != null) { covered++; reciprocalRanks += 1.0 / rank }
                    characterErrors += accepted.minOf { editDistance(first, it) }
                    characters += case.expected.codePointCount(0, case.expected.length)
                    val counts = categoryCounts.getOrPut(case.category) { IntArray(2) }
                    counts[0]++; if (rank == 1) counts[1]++
                    observations += """{"query":${quote(case.query)},"expected":${quote(case.expected)},"category":${quote(case.category)},"context":${quote(case.context)},"aliases":[${case.aliases.joinToString { quote(it) }}],"rank":$rank,"top3":[${candidates.take(3).joinToString { quote(it.text) }}]}"""
                }
            }
        }
        val qualityGatePassed = cases.size == 40 && top1 >= 28 && top3 >= 34 && top10 >= 39 && covered == 40
        val report = """
            {
              "schemaVersion": 1,
              "generatedAt": ${quote(Instant.now().toString())},
              "environment": {"vm":${quote(System.getProperty("java.vm.name"))},"os":${quote(System.getProperty("os.name"))},"device":"host JVM; not Android frame latency"},
              "candidateLimit": $LIMIT,
              "assets": {${files.take(5).joinToString { quote(it.name) + ":" + quote(sha256(it)) }}},
              "decoderLoadMs": ${number(loadNs / 1e6)},
              "quality": {"cases":${cases.size},"top1":$top1,"top3":$top3,"top10":$top10,"covered":$covered,"mrr":${number(reciprocalRanks / cases.size)},"characterErrorRate":${number(characterErrors.toDouble() / characters)}},
              "regressionGate": {"passed":$qualityGatePassed,"minimumTop1":28,"minimumTop3":34,"minimumTop10":39,"minimumCovered":40,"timing":"observational; host timings are not Android frame gates"},
              "byCategory": {${categoryCounts.entries.joinToString { (name, counts) -> quote(name) + ":{\"cases\":${counts[0]},\"top1\":${counts[1]}}" }}},
              "firstPassKeystrokes": ${latency(firstPass)},
              "warmedPassKeystrokes": ${latency(warmedPass)},
              "observations": [${observations.joinToString(",\n")}]
            }
        """.trimIndent()
        files[5].parentFile?.mkdirs()
        files[5].writeText(report + "\n")
        println("Daily full-pinyin: top1=$top1/${cases.size}, top3=$top3, top10=$top10; report=${files[5]}")
        check(qualityGatePassed) { "Daily-input quality regressed; inspect the written per-case report before changing gates" }
    }

    fun hasSyllableCount(query: String, count: Int, syllables: Set<String>): Boolean {
        if (count < 1 || count > query.length) return false
        var offsets = setOf(0)
        repeat(count) {
            offsets = buildSet {
                for (start in offsets) for (end in start + 1..minOf(query.length, start + 6)) {
                    if (query.substring(start, end) in syllables) add(end)
                }
            }
        }
        return query.length in offsets
    }

    private fun latency(samples: List<Long>): String {
        val sorted = samples.sorted()
        fun percentile(p: Double) = number(sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)] / 1e6)
        return """{"samples":${samples.size},"p50Ms":${percentile(.50)},"p95Ms":${percentile(.95)},"p99Ms":${percentile(.99)},"maxMs":${number(sorted.last() / 1e6)}}"""
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun number(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
    private fun quote(value: String): String = "\"" + buildString {
        for (ch in value) append(when (ch) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (ch.code < 32) "\\u%04x".format(ch.code) else ch.toString()
        })
    } + "\""
}
