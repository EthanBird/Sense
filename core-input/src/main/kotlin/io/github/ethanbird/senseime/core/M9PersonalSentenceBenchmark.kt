package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Production decoder, synthetic learning events; this never reads a user's private dictionary. */
object M9PersonalSentenceBenchmark {
    private const val NOW = 1_000_000L
    private const val LIMIT = 255
    data class Case(val code: String, val text: String, val initials: String, val query: String,
        val expected: String, val category: String, val required: Boolean)

    fun readCases(file: File): List<Case> = file.readLines().mapIndexedNotNull { i, line ->
        if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
        val f = line.split('\t')
        require(f.size == 7 && f[6] in listOf("true", "false")) { "Invalid M9 row ${i + 1}" }
        require(f[0].isNotEmpty() && f[0].all { it in 'a'..'z' } && f[3].all { it in 'a'..'z' })
        require(f[1].codePointCount(0, f[1].length) == f[2].length)
        require(f[3].contains(f[0]) && f[4].contains(f[1]))
        Case(f[0], f[1], f[2], f[3], f[4], f[5], f[6].toBooleanStrict())
    }.also { rows -> require(rows.isNotEmpty() && rows.distinctBy { it.query }.size == rows.size) }

    /** 10,000 unique, valid full-pinyin three-character fixtures for capacity timing only. */
    fun capacityRows(): List<LearnedPhrase> {
        val units = listOf("ba" to "巴", "bo" to "波", "bu" to "布", "ca" to "擦", "ce" to "策",
            "cu" to "粗", "da" to "达", "de" to "德", "di" to "迪", "du" to "杜",
            "fa" to "发", "fo" to "佛", "fu" to "福", "ga" to "嘎", "ge" to "歌",
            "gu" to "古", "ha" to "哈", "he" to "何", "hu" to "胡", "ji" to "季",
            "ju" to "居", "ka" to "卡", "ke" to "可", "ku" to "库", "la" to "拉")
        return buildList {
            for (a in units) for (b in units) for (c in units) {
                if (size == 10_000) return@buildList
                add(LearnedPhrase(a.first + b.first + c.first, "${a.first[0]}${b.first[0]}${c.first[0]}",
                    a.second + b.second + c.second, 1, NOW, NOW, positiveEvidence = 1.75f))
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 7) { "Usage: M9PersonalSentenceBenchmark <lexicon> <bigram> <syllables> <english> <personal-replay> <daily-replay> <report>" }
        val f = args.map(::File)
        val rows = readCases(f[4])
        val syllables = f[2].readLines().toSet()
        rows.forEach { row ->
            require(M8DailyInputBenchmark.hasSyllableCount(row.query, row.expected.codePointCount(0, row.expected.length), syllables))
        }
        val bigram = f[1].inputStream().use(BinaryCharacterBigramModel::load)
        val base = f[0].inputStream().use { PinyinDecoder.load(it, bigram) }
        val segmenter = PinyinSyllableSegmenter(syllables)
        val english = f[3].inputStream().use(EnglishLexicon::load)
        fun decoder(store: UserLexicon) = AdaptivePinyinDecoder(base, store, segmenter, english)
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val store = MemoryUserLexicon(clock = { NOW }, onRecord = { journal[it.fullPinyin to it.text] = it })
        val adaptive = decoder(store)
        val before = rows.map { adaptive.decode(it.query, LIMIT) }
        val seeds = rows.distinctBy { it.code to it.text }
        seeds.forEach { row -> check(adaptive.learn(row.code, Candidate(row.text, canonicalPinyin = row.code, canonicalInitials = row.initials)) != null) }
        val after = rows.map { adaptive.decode(it.query, LIMIT) }
        val restoredDecoder = decoder(MemoryUserLexicon(initial = journal.values, clock = { NOW }))
        val restored = rows.map { restoredDecoder.decode(it.query, LIMIT) }
        seeds.forEach { check(store.forget(it.code, it.text)) }
        val forgotten = rows.map { adaptive.decode(it.query, LIMIT) }
        fun rank(values: List<Candidate>, expected: String): Int? = values.indexOfFirst { it.text == expected }.takeIf { it >= 0 }?.plus(1)
        val requiredPassed = rows.indices.all { !rows[it].required || rank(after[it], rows[it].expected) == 1 }
        val restorePassed = after == restored
        val forgetPassed = before == forgotten
        val observations = rows.indices.map { i ->
            val r = rows[i]
            """{"query":${quote(r.query)},"expected":${quote(r.expected)},"category":${quote(r.category)},"requiredTop1":${r.required},"beforeRank":${rank(before[i], r.expected)},"afterRank":${rank(after[i], r.expected)},"beforeTop3":[${before[i].take(3).joinToString { quote(it.text) }}],"afterTop3":[${after[i].take(3).joinToString { quote(it.text) }}]}"""
        }
        // Different topic sentences protect against unrelated-user-data cross contamination.
        val daily = M8DailyInputBenchmark.readCases(f[5])
        val plain = decoder(MemoryUserLexicon(clock = { NOW }))
        // 'store' is now forgotten. Recreate the seeded dictionary for the isolation check.
        val seededStore = MemoryUserLexicon(initial = journal.values, clock = { NOW })
        val seeded = decoder(seededStore)
        val disjoint = daily.filter { seededStore.matchFullPinyin(it.query).isEmpty() }
        val collateralPassed = disjoint.all {
            val input = PinyinComposition(remainingPinyin = it.query)
            plain.decodeProgressively(input, it.context, LIMIT) == seeded.decodeProgressively(input, it.context, LIMIT)
        }

        val capacityRows = capacityRows()
        lateinit var capacity: MemoryUserLexicon
        val capacityLoadNs = measureNanoTime { capacity = MemoryUserLexicon(initial = capacityRows, clock = { NOW }) }
        val exactCapacity = capacityRows.all { r -> capacity.matchFullPinyin(r.fullPinyin).any { it.phrase.text == r.text } }
        val queries = daily.map { it.query } + capacityRows.filterIndexed { i, _ -> i % 100 == 0 }.map { "wo" + it.fullPinyin + "zaigongsi" } +
            listOf("babo".repeat(24), "zhinengti".repeat(10))
        repeat(2) { queries.forEach { capacity.matchFullPinyin(it) } }
        val matchTimes = buildList { repeat(10) { queries.forEach { q -> add(measureNanoTime { capacity.matchFullPinyin(q) }) } } }
        fun keyTimes(d: AdaptivePinyinDecoder): List<Long> {
            var times = emptyList<Long>()
            repeat(2) {
                times = buildList {
                    daily.forEach { r ->
                        var composition = PinyinComposition()
                        r.query.forEach { key ->
                            composition = composition.type(key)
                            add(measureNanoTime { d.decodeProgressively(composition, r.context, LIMIT) })
                        }
                    }
                }
            }
            return times
        }
        val emptyTimes = keyTimes(plain)
        val seededTimes = keyTimes(seeded)
        val capacityTimes = keyTimes(decoder(capacity))
        val passed = requiredPassed && restorePassed && forgetPassed && collateralPassed && exactCapacity
        fun top1(values: List<List<Candidate>>) = rows.indices.count { rank(values[it], rows[it].expected) == 1 }
        val report = """
            {
              "schemaVersion":1,"generatedAt":${quote(Instant.now().toString())},
              "environment":{"vm":${quote(System.getProperty("java.vm.name"))},"os":${quote(System.getProperty("os.name"))},"device":"host JVM; synthetic capacity data; not Android frame latency"},
              "assets":{${f.take(6).joinToString { quote(it.name) + ":" + quote(sha256(it)) }}},
              "candidateLimit":$LIMIT,"seedCount":${seeds.size},"selectionEventsPerSeed":1,
              "quality":{"cases":${rows.size},"beforeTop1":${top1(before)},"afterTop1":${top1(after)}},
              "regressionGate":{"passed":$passed,"requiredCasesTop1":$requiredPassed,"restoredAllCandidatesEqual":$restorePassed,"forgottenAllCandidatesEqual":$forgetPassed,"disjointCases":${disjoint.size},"disjointAllCandidatesEqual":$collateralPassed,"capacityAllRowsReachable":$exactCapacity},
              "capacity":{"rows":${capacityRows.size},"constructMs":${number(capacityLoadNs / 1e6)},"matchFullPinyin":${latency(matchTimes)}},
              "warmedPerKeystroke":{"emptyUserDictionary":${latency(emptyTimes)},"seededUserDictionary":${latency(seededTimes)},"capacityUserDictionary":${latency(capacityTimes)}},
              "observations":[${observations.joinToString(",\n")}]
            }
        """.trimIndent()
        f[6].parentFile?.mkdirs(); f[6].writeText(report + "\n")
        println("Personal sentence: ${top1(before)} -> ${top1(after)}/${rows.size}; gate=$passed; report=${f[6]}")
        check(passed) { "Personalized sentence regression; inspect per-case report before changing gates" }
    }

    private fun latency(samples: List<Long>): String {
        val sorted = samples.sorted()
        fun percentile(p: Double) = number(sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)] / 1e6)
        return """{"samples":${samples.size},"p50Ms":${percentile(.5)},"p95Ms":${percentile(.95)},"p99Ms":${percentile(.99)},"maxMs":${number(sorted.last() / 1e6)}}"""
    }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun number(value: Double) = String.format(Locale.ROOT, "%.4f", value)
    private fun quote(value: String) = "\"" + value.flatMap { ch -> when (ch) {
        '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
        else -> if (ch.code < 32) "\\u%04x".format(ch.code) else ch.toString()
    }.toList() }.joinToString("") + "\""
}
