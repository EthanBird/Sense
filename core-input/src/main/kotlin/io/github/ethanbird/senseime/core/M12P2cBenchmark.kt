package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Frozen source reconstruction evaluation. Use tools/evaluate_p2c.py to enforce dev/test pins. */
object M12P2cBenchmark {
    data class Case(val id: String, val query: String, val expected: String, val stratum: String, val syllables: List<String>)
    fun readCases(file: File): List<Case> = file.readLines().mapIndexedNotNull { index, line ->
        if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
        val f = line.split('\t')
        require(f.size == 5 && f[0].matches(Regex("[a-f0-9]{64}"))) { "Invalid P2C row ${index + 1}" }
        val syllables = f[4].split(' ')
        require(f[1].matches(Regex("[a-z]{1,96}")) && syllables.joinToString("") == f[1])
        require(f[2].codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN })
        require(f[2].codePointCount(0, f[2].length) == syllables.size && f[3] in listOf("short", "long"))
        Case(f[0], f[1], f[2], f[3], syllables)
    }.also { rows -> require(rows.isNotEmpty() && rows.map { it.id }.distinct().size == rows.size) }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 9) { "Usage: <lexicon> <bigrams> <syllables> <english> <replay> <model> <modes> <partition> <report>" }
        val f = args.take(6).map(::File)
        val modes = args[6].split(',')
        require(modes.isNotEmpty() && modes.first() == "legacy" && modes.distinct().size == modes.size)
        require(modes.all { it == "legacy" || it in listOf("lm0.25", "lm0.5", "lm1.0", "lm2.0") })
        require(args[7] in listOf("dev", "test"))
        val cases = readCases(f[4])
        val syllables = f[2].readLines().toSet()
        require(cases.all { it.syllables.all(syllables::contains) })
        val bigram = f[1].inputStream().use(BinaryCharacterBigramModel::load)
        val base = f[0].inputStream().use { PinyinDecoder.load(it, bigram) }
        val model = f[5].inputStream().use(BinaryCharacterLanguageModel::load)
        val segmenter = PinyinSyllableSegmenter(syllables)
        val english = f[3].inputStream().use(EnglishLexicon::load)
        var baseline = emptyList<Int>()
        val reports = modes.map { mode ->
            val decoder = AdaptivePinyinDecoder(if (mode == "legacy") base else base.withLanguageModel(model, mode.removePrefix("lm").toFloat()), MemoryUserLexicon(), segmenter, english)
            val ranks = ArrayList<Int>()
            val times = ArrayList<Long>()
            val errors = ArrayList<Int>()
            val byStratum = linkedMapOf<String, IntArray>()
            val observations = cases.map { case ->
                val composing = PinyinComposition(remainingPinyin = case.query)
                lateinit var result: ProgressivePinyinDecoding
                times += measureNanoTime { result = decoder.decodeProgressively(composing, "", 255) }
                val values = result.wholeCandidates
                val rank = values.indexOfFirst { it.text == case.expected } + 1
                val first = values.firstOrNull()?.text.orEmpty()
                val error = M8DailyInputBenchmark.editDistance(first, case.expected)
                ranks += rank; errors += error
                val group = byStratum.getOrPut(case.stratum) { IntArray(4) }
                group[0]++; if (rank == 1) group[1]++; group[2] += error; group[3] += case.expected.codePointCount(0, case.expected.length)
                """{"id":${quote(case.id)},"query":${quote(case.query)},"expected":${quote(case.expected)},"stratum":${quote(case.stratum)},"rank":$rank,"characterErrors":$error,"top5":[${values.take(5).joinToString { quote(it.text) }}]}"""
            }
            if (mode == "legacy") baseline = ranks
            val gains = ranks.indices.count { ranks[it] == 1 && baseline[it] != 1 }
            val losses = ranks.indices.count { ranks[it] != 1 && baseline[it] == 1 }
            val characters = cases.sumOf { it.expected.codePointCount(0, it.expected.length) }
            val p95 = times.sorted()[(ceil(times.size * .95).toInt() - 1).coerceIn(times.indices)] / 1e6
            val top1 = ranks.count { it == 1 }
            println("${args[7]} $mode: top1=$top1/${cases.size}, top3=${ranks.count { it in 1..3 }}, errors=${errors.sum()}/$characters, gains=$gains losses=$losses, p95=${p95}ms")
            """{"mode":${quote(mode)},"top1":$top1,"top3":${ranks.count { it in 1..3 }},"top10":${ranks.count { it in 1..10 }},"covered":${ranks.count { it > 0 }},"mrr":${ranks.sumOf { if (it == 0) 0.0 else 1.0/it }/ranks.size},"characterErrors":${errors.sum()},"characters":$characters,"cer":${errors.sum().toDouble()/characters},"gains":$gains,"losses":$losses,"finalDecodeP95Ms":$p95,"byStratum":{${byStratum.entries.joinToString { (name,c) -> quote(name)+":{\"cases\":${c[0]},\"top1\":${c[1]},\"characterErrors\":${c[2]},\"characters\":${c[3]}}" }}},"observations":[${observations.joinToString()}]}"""
        }
        val sourceRoot = generateSequence(f[0].absoluteFile.parentFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val core = File(sourceRoot, "core-input/src/main/kotlin")
        val sources = core.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.relativeTo(core).invariantSeparatorsPath }
            .joinToString { quote(it.relativeTo(core).invariantSeparatorsPath) + ":" + quote(sha(it)) }
        val output = File(args[8]); output.parentFile?.mkdirs()
        output.writeText("""{"schemaVersion":1,"generatedAt":${quote(Instant.now().toString())},"partition":${quote(args[7])},"purpose":"Exact source reconstruction on pre-reviewed source text; empty context, no aliases. Host final progressive decode only, not per-key Android latency.","candidateLimit":255,"cases":${cases.size},"assets":{${f.joinToString { quote(it.name)+":"+quote(sha(it)) }}},"sources":{$sources},"modes":[${reports.joinToString()}]}""" + "\n")
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
