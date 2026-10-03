package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest

/** Synthetic, fixed-clock learning on the production LM path; never reads a user's dictionary. */
object M18LexiconLearningBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: <root> <lexicon asset> <new report>" }
        val root = File(args[0]); val lexicon = File(args[1]); val report = File(args[2])
        require(!report.exists()) { "Retain earlier evidence" }
        fun asset(name: String) = File(root, "ime-service/src/main/assets/$name")
        val input = File(root, "benchmarks/replay/m9-personal-word-sentences.tsv")
        val rows = M9PersonalSentenceBenchmark.readCases(input)
        val model = asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load)
        val base = lexicon.inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }.withLanguageModel(model, .5f, -4f)
        val segmenter = PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines())
        val english = asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load)
        fun decoder(store: UserLexicon) = AdaptivePinyinDecoder(base, store, segmenter, english)
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val store = MemoryUserLexicon(clock = { 1_000L }, onRecord = { journal[it.fullPinyin to it.text] = it })
        val adaptive = decoder(store)
        fun decode(d: AdaptivePinyinDecoder) = rows.map {
            d.decodeProgressively(PinyinComposition(remainingPinyin = it.query), "", 255).wholeCandidates
        }
        val before = decode(adaptive)
        val seeds = rows.distinctBy { it.code to it.text }
        fun learn(evidence: UserLearningEvidence) = seeds.forEach {
            check(adaptive.learn(it.code, Candidate(it.text, canonicalPinyin = it.code, canonicalInitials = it.initials), evidence) != null)
        }
        learn(UserLearningEvidence.EXPLICIT_SELECTION)
        val learned = decode(adaptive)
        learn(UserLearningEvidence.DEFAULT_ACCEPT)
        val reused = decode(adaptive)
        val restored = decode(decoder(MemoryUserLexicon(initial = journal.values, clock = { 1_000L })))
        seeds.forEach { check(store.forget(it.code, it.text)) }
        val forgotten = decode(adaptive)
        val observations = rows.mapIndexed { i, row ->
            val stages = listOf("before" to before[i], "learned" to learned[i], "reused" to reused[i], "restored" to restored[i], "forgotten" to forgotten[i])
            val values = stages.joinToString { (name, candidates) ->
                "${quote(name)}:{\"rank\":${candidates.indexOfFirst { it.text == row.expected } + 1},\"top5\":[${candidates.take(5).joinToString { quote(it.text) }}]}"
            }
            """{"query":${quote(row.query)},"expected":${quote(row.expected)},"seed":${quote(row.text)},"required":${row.required},"category":${quote(row.category)},$values}"""
        }
        val required = rows.indices.filter { rows[it].required }
        fun passed(values: List<List<Candidate>>) = required.all { values[it].firstOrNull()?.text == rows[it].expected }
        val sources = File(root, "core-input/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }.joinToString { quote(it.relativeTo(root).invariantSeparatorsPath) + ":" + quote(sha(it)) }
        report.parentFile?.mkdirs()
        report.writeText("""{"schemaVersion":1,"scope":"Fixed synthetic learning, all M9 seeds, full progressive 26-key production LM; not real SQLite or user data","lexiconSha256":${quote(sha(lexicon))},"lmSha256":${quote(sha(asset("pinyin_character_lm.scng")))},"inputSha256":${quote(sha(input))},"lmWeight":0.5,"oovFeature":-4,"candidateLimit":255,"sources":{$sources},"requiredRows":${required.size},"learnedPassed":${passed(learned)},"reusedPassed":${passed(reused)},"restoredPassed":${passed(restored)},"restoreCompleteOutputEqual":${reused == restored},"forgetCompleteOutputEqual":${before == forgotten},"observations":[${observations.joinToString()}]}""" + "\n")
        println("Required=${required.size} learned=${passed(learned)} reused=${passed(reused)} restored=${passed(restored)} restoreEqual=${reused == restored} forgetEqual=${before == forgotten}")
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
