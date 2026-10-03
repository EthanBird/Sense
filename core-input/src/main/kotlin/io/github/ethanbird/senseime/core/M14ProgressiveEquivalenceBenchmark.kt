package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import kotlin.system.measureNanoTime

/** No-learning, LM 0.5, every M8 key and M12 query; optionally all known E1 mutations. */
object M14ProgressiveEquivalenceBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3) { "Usage: <repository root> <new output file> [include known typos]" }
        val root = File(args[0])
        val out = File(args[1])
        require(!out.exists()) { "Keep prior evidence; use a new output file" }
        fun asset(name: String) = File(root, "ime-service/src/main/assets/$name")
        val bigrams = asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it, bigrams) }
        val model = asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load)
        val decoder = AdaptivePinyinDecoder(base.withLanguageModel(model, .5f), MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val daily = M8DailyInputBenchmark.readCases(File(root, "benchmarks/replay/m8-daily-full-pinyin.tsv"))
        val known = M12P2cBenchmark.readCases(File(root, "benchmarks/corpus/p2c-v1/test.tsv"))
        val includeKnownTypos = args.getOrNull(2)?.toBooleanStrict() ?: false
        val typoFiles = if (includeKnownTypos) listOf("dev", "test").map {
            "known-typo-$it" to File(root, "benchmarks/corpus/typo-v1/$it.tsv")
        } else emptyList()
        val typos = typoFiles.map { (group, file) -> group to M15TypoRecallBenchmark.readCases(file) }
        val inputs = listOf(File(root, "benchmarks/replay/m8-daily-full-pinyin.tsv"),
            File(root, "benchmarks/corpus/p2c-v1/test.tsv")) + typoFiles.map { it.second }
        val inputHashes = inputs.joinToString { "\"${it.relativeTo(root).invariantSeparatorsPath}\":\"${sha(it.readBytes())}\"" }
        val assetHashes = listOf("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_character_lm.scng",
            "pinyin_syllables.txt", "english_lexicon.txt").joinToString { "\"$it\":\"${sha(asset(it).readBytes())}\"" }
        val observations = ArrayList<String>()
        repeat(2) { pass ->
            fun observe(group: String, query: String, context: String, composition: PinyinComposition) {
                lateinit var result: ProgressivePinyinDecoding
                val ns = measureNanoTime { result = decoder.decodeProgressively(composition, context, 255) }
                // Full data-class serialization includes every whole/prefix candidate, order,
                // canonical spelling, provenance and round-trip float scores, not merely top-5.
                val hash = sha(result.toString().toByteArray(Charsets.UTF_8))
                observations += """{"pass":$pass,"group":"$group","query":"$query","context":"$context","keyCount":${composition.remainingPinyin.length},"wholeCount":${result.wholeCandidates.size},"prefixCount":${result.prefixCandidates.size},"resultSha256":"$hash","decodeNs":$ns}"""
            }
            daily.forEach { row ->
                var composing = PinyinComposition()
                row.query.forEach { key ->
                    composing = composing.type(key)
                    observe("daily", row.query, row.context, composing)
                }
            }
            known.forEach { row -> observe("known-p2c", row.query, "", PinyinComposition(remainingPinyin = row.query)) }
            typos.forEach { (group, rows) -> rows.forEach { row ->
                observe(group, row.typed, "", PinyinComposition(remainingPinyin = row.typed))
            } }
            println("Pass $pass complete; observations=${observations.size}")
        }
        val core = File(root, "core-input/src/main/kotlin")
        val sources = core.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.relativeTo(core).invariantSeparatorsPath }
            .joinToString { "\"${it.relativeTo(core).invariantSeparatorsPath}\":\"${sha(it.readBytes())}\"" }
        out.parentFile?.mkdirs()
        out.writeText("""{"schemaVersion":2,"scope":"Known diagnostic corpus, full progressive result equivalence; host JVM, not Android latency or fresh held-out accuracy","candidateLimit":255,"weight":0.5,"learning":false,"inputs":{$inputHashes},"assets":{$assetHashes},"sources":{$sources},"observations":[${observations.joinToString()}]}""" + "\n")
        println("Full-result observations=${observations.size}; $out")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
