package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest

/** Exports pre-ranker evidence from the production decoder, labels used only after decoding. */
object M17CandidateScoreBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: <root> <replay> <new output>" }
        val root = File(args[0]); val input = File(args[1]); val out = File(args[2])
        require(!out.exists()) { "Keep previous evidence" }
        fun asset(name: String) = File(root, "ime-service/src/main/assets/$name")
        val model = asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load)
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
            .withLanguageModel(model, .5f).withCorrectionCalibration(0f)
        val decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        out.parentFile?.mkdirs()
        val rows = M15TypoRecallBenchmark.readCases(input)
        out.bufferedWriter().use { writer ->
            val sources = File(root, "core-input/src/main/kotlin").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
                .joinToString { quote(it.relativeTo(root).invariantSeparatorsPath) + ":" + quote(sha(it)) }
            val assets = listOf("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_character_lm.scng",
                "pinyin_syllables.txt", "english_lexicon.txt").joinToString { quote(it)+":"+quote(sha(asset(it))) }
            writer.appendLine("""{"type":"header","schemaVersion":2,"correctionCompositionBoost":0,"progressiveJoints":"Apostrophes removed; joints rows are duplicate clean controls, not forced-boundary coverage","rows":${rows.size},"inputSha256":${quote(sha(input))},"sources":{$sources},"assets":{$assets},"scope":"Known synthetic-error reconstruction; full pre-ranker pool, no personal learning; not natural input accuracy"}""")
            rows.forEachIndexed { index, row ->
                var captured: CandidateRankingDiagnostics.Trace? = null
                val normalized = PinyinSyllableSegmenter.normalize(row.typed)
                val actual = CandidateRankingDiagnostics.observe({ if (it.query == normalized) captured = it }) {
                    decoder.decodeProgressively(PinyinComposition(remainingPinyin = row.typed), "", 255)
                }.wholeCandidates
                val trace = requireNotNull(captured)
                val reranked = CandidateRanker.rank(trace.candidates, 255, trace.exact, trace.composed)
                val actualChinese = actual.filter { it.matchKind != CandidateMatchKind.ENGLISH_EXACT && it.matchKind != CandidateMatchKind.ENGLISH_PREFIX }
                check(actualChinese.map { it.text } == reranked.take(actualChinese.size).map { it.text }) {
                    "Export differs from progressive Chinese ranking: ${row.id}"
                }
                val scorer = PinyinLanguageScorer(PinyinLanguageBinding(model, .5f), row.typed.replace("'", ""), PinyinLanguageScorer.context(""))
                // Same-source duplicate raw scores can only lose under every source-prior ablation.
                val unique = trace.candidates.filter { it.score.isFinite() }.groupBy { it.text to it.matchKind }
                    .values.map { values -> values.maxBy { it.score } }
                val pool = unique.joinToString { c ->
                    """[${quote(c.text)},${c.score},${quote(c.matchKind.name)},${scorer.feature(c.text)},${quote(c.canonicalPinyin.orEmpty())}]"""
                }
                writer.appendLine("""{"type":"row","id":${quote(row.id)},"sourceId":${quote(row.sourceId)},"typed":${quote(row.typed)},"effectiveQuery":${quote(normalized)},"expected":${quote(row.expected)},"operation":${quote(row.operation)},"exact":${trace.exact},"composed":${trace.composed},"rank":${actual.indexOfFirst { it.text == row.expected }+1},"actual":[${actual.take(10).joinToString { quote(it.text) }}],"pool":[$pool]}""")
                if ((index + 1) % 100 == 0) println("Exported ${index+1}/${rows.size}")
            }
        }
        println("Exported ${rows.size} rows to $out")
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
