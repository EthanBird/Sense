package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import kotlin.system.measureNanoTime

/** Known replay graph diagnosis, with the exact production inventory and explicit limits. */
object M16SpellingGraphBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "Usage: <root> <replay> <new report> <limits CSV> <operations CSV>" }
        val root = File(args[0]); val replay = File(args[1]); val output = File(args[2])
        require(!output.exists()) { "Keep prior graph evidence" }
        val limits = args[3].split(',').map(String::toInt).distinct()
        require(limits.isNotEmpty() && limits.all { it in 1..1024 })
        val operations = args[4].split(',').toSet()
        val all = M15TypoRecallBenchmark.readCases(replay)
        require(operations.all { name -> all.any { it.operation == name } })
        val rows = all.filter { it.operation in operations }
        val dictionary = File(root,"ime-service/src/main/assets/pinyin_lexicon.bin")
        val decoder = dictionary.inputStream().use { PinyinDecoder.load(it) }
        val graph = PinyinDecoder::class.java.getDeclaredField("spellingGraph").apply { isAccessible = true }
            .get(decoder) as PinyinSpellingGraph
        val observations = rows.map { row ->
            val ends = row.syllables.runningFold(0) { offset, s -> offset+s.length }.drop(1)
            // Reduced inventory answers only whether the edge model can express this known
            // operation. Its ranking/recall is NOT a substitute for the production graph.
            val reduced = PinyinSpellingGraph(row.syllables).paths(row.typed, 192)
                .firstOrNull { it.canonical == row.canonical && it.syllableEnds == ends }
            val results = limits.map { limit ->
                lateinit var paths: List<PinyinSpellingPath>
                val ns = measureNanoTime { paths = graph.paths(row.typed,limit) }
                val aligned = paths.indexOfFirst { it.canonical == row.canonical && it.syllableEnds == ends }+1
                val canonical = paths.indexOfFirst { it.canonical == row.canonical }+1
                val offsets = paths.groupingBy { it.firstEditOffset }.eachCount().toSortedMap()
                    .entries.joinToString { "\"${it.key}\":${it.value}" }
                """{"limit":$limit,"returned":${paths.size},"canonicalRank":$canonical,"alignedRank":$aligned,"firstEditOffsets":{$offsets},"graphNs":$ns}"""
            }
            """{"id":"${row.id}","sourceId":"${row.sourceId}","typed":"${row.typed}","canonical":"${row.canonical}","operation":"${row.operation}","zone":"${row.zone}","editOffset":${row.editOffset},"reducedInventoryTargetCost":${reduced?.cost},"results":[${results.joinToString()}]}"""
        }
        output.parentFile?.mkdirs()
        val source = File(root,"core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinSpellingGraph.kt")
        output.writeText("""{"schemaVersion":1,"scope":"Known E1 replay graph budget diagnosis; reduced inventory is expressibility only, not production ranking; host timing not Android","inputSha256":"${sha(replay)}","dictionarySha256":"${sha(dictionary)}","graphSourceSha256":"${sha(source)}","observations":[${observations.joinToString()}]}"""+"\n")
        println("Graph observations=${rows.size}; $output")
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
