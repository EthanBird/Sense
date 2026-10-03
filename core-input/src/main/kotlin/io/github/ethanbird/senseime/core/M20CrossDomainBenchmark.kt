package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest

/** Actual production ranking with only the model bytes substituted. No score diagnostic observer. */
object M20CrossDomainBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4) { "Usage: <root> <five-column replay> <model> <new jsonl>" }
        val root = File(args[0]); val input = File(args[1]); val modelFile = File(args[2]); val output = File(args[3])
        require(!output.exists()) { "Preserve prior evidence" }
        fun asset(name: String) = File(root, "ime-service/src/main/assets/$name")
        val model = modelFile.inputStream().use(BinaryCharacterLanguageModel::load)
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
            .withLanguageModel(model, .5f, -4f)
        val decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val rows = input.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            line.split('\t').also { require(it.size == 5) }
        }
        require(rows.isNotEmpty() && rows.map { it[0] }.distinct().size == rows.size)
        output.parentFile?.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            val sources = File(root, "core-input/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }.joinToString { quote(it.relativeTo(root).invariantSeparatorsPath) + ":" + quote(sha(it.readBytes())) }
            val assets = listOf("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_syllables.txt", "english_lexicon.txt")
                .joinToString { quote(it) + ":" + quote(sha(asset(it).readBytes())) }
            writer.appendLine("""{"type":"header","schemaVersion":1,"mode":"whole-and-midpoint","inputSha256":${quote(sha(input.readBytes()))},"modelSha256":${quote(sha(modelFile.readBytes()))},"sources":{$sources},"assets":{$assets},"configuration":{"lmWeight":0.5,"oovFeature":-4,"correctionBoost":12,"limit":255},"personalization":"empty; no learn","scope":"Source reconstruction, midpoint oracle context; not natural mobile input or Android latency"}""")
            var count = 0
            for (row in rows) {
                val text = row[2].codePoints().toArray(); val units = row[4].split(' ')
                require(text.size >= 2 && text.size == units.size && units.joinToString("") == row[1] && row[1].length <= 96)
                for (cut in listOf(0, text.size / 2).distinct()) {
                    val left = String(text, maxOf(0, cut - 2), minOf(2, cut))
                    val expected = String(text, cut, text.size - cut)
                    val query = units.drop(cut).joinToString("")
                    val start = System.nanoTime()
                    val result = decoder.decodeProgressively(PinyinComposition(emptyList(), query), left, 255)
                    val nanos = System.nanoTime() - start
                    val candidates = result.wholeCandidates
                    check(candidates.map { it.text }.distinct().size == candidates.size)
                    writer.appendLine("""{"type":"row","id":${quote(row[0])},"stratum":${quote(row[3])},"cut":$cut,"mode":${quote(if (cut == 0) "empty" else "editor")},"context":${quote(left)},"query":${quote(query)},"expected":${quote(expected)},"rank":${candidates.indexOfFirst { it.text == expected } + 1},"candidates":[${candidates.joinToString { quote(it.text) }}],"firstKind":${quote(candidates.firstOrNull()?.matchKind?.name.orEmpty())},"resultSha256":${quote(sha(result.toString().toByteArray(Charsets.UTF_8)))},"hostNanos":$nanos}""")
                    count++
                    if (count % 100 == 0) println("Decoded $count states")
                }
            }
            writer.appendLine("""{"type":"summary","rows":$count,"sentences":${rows.size}}""")
            println("Decoded $count states from ${rows.size} sentences to $output")
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
}
