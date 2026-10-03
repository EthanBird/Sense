package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Frozen-pool host export. Reference text supplies oracle contexts/labels, never score features. */
object M19ScoreFeatureBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4) { "Usage: <root> <annotated five-column replay> <new jsonl.gz> <sentences|suffixes|sampled>" }
        val root = File(args[0]); val input = File(args[1]); val output = File(args[2]); val mode = args[3]
        require(!output.exists()) { "Keep previous evidence" }
        require(mode in listOf("sentences", "suffixes", "sampled"))
        fun asset(name: String) = File(root, "ime-service/src/main/assets/$name")
        val model = asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load)
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
            .withLanguageModel(model, .5f, -4f)
        val decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        val rows = input.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            it.split('\t').also { fields -> require(fields.size == 5) }
        }
        output.parentFile?.mkdirs()
        var count = 0; var paths = 0; var maxError = 0.0
        GZIPOutputStream(output.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            val sources = File(root, "core-input/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }.joinToString { quote(it.relativeTo(root).invariantSeparatorsPath) + ":" + quote(sha(it.readBytes())) }
            val assets = listOf("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_character_lm.scng",
                "pinyin_syllables.txt", "english_lexicon.txt").joinToString { quote(it) + ":" + quote(sha(asset(it).readBytes())) }
            writer.appendLine("""{"type":"header","schemaVersion":1,"featureVersion":"actual-path-v1","mode":${quote(mode)},"inputSha256":${quote(sha(input.readBytes()))},"sources":{$sources},"assets":{$assets},"features":[${PinyinScoreDiagnostics.Feature.entries.joinToString { quote(it.name) }}],"configuration":{"lmWeight":0.5,"oovFeature":-4,"correctionBoost":12,"limit":255},"scope":"Fixed production candidate pool; oracle prefix reconstruction, not natural typing. No fitted ranking weights. LM/clipping/insertion prior remain one named component. Arithmetic error is not a feature. Learned-word outer adapter is excluded from this empty-personal-store export."}""")
            for (row in rows) {
                val text = row[2].codePoints().toArray(); val syllables = row[4].split(' ')
                require(text.size == syllables.size && syllables.joinToString("") == row[1] && row[1].length <= 96)
                // Predeclared, label-independent workload: whole sentence plus its midpoint.
                // Annotation provenance (weak automatic versus reviewed) belongs to the input manifest.
                val cuts: Iterable<Int> = when (mode) {
                    "sentences" -> 0..0
                    "sampled" -> listOf(0, text.size / 2).distinct()
                    else -> 1 until text.size
                }
                for (cut in cuts) {
                    val context = String(text, maxOf(0, cut - 2), minOf(2, cut))
                    val expected = String(text, cut, text.size - cut)
                    val query = syllables.drop(cut).joinToString("")
                    val modes = when (mode) {
                        "sentences" -> listOf("empty")
                        "sampled" -> listOf(if (cut == 0) "empty" else "editor")
                        else -> listOf("empty", "editor", "boundary", "accepted")
                    }
                    for (contextMode in modes) {
                        var left = when (contextMode) { "empty" -> ""; "boundary" -> context + "。"; else -> context }
                        val accepted = if (contextMode == "accepted") {
                            left = if (cut > 1) String(text, cut - 2, 1) else ""
                            listOf(AcceptedPinyinSegment(String(text, cut - 1, 1), syllables[cut - 1]))
                        } else emptyList()
                        val composition = PinyinComposition(accepted, query)
                        val before = decoder.decodeProgressively(composition, left, 255)
                        var captured: PinyinScoreDiagnostics.Trace? = null
                        val observed = PinyinScoreDiagnostics.observe({
                            if (it.query == query && it.seam == "decode") { check(captured == null); captured = it }
                        }) { decoder.decodeProgressively(composition, left, 255) }
                        check(before == observed) { "Observation changed the complete progressive result" }
                        val trace = checkNotNull(captured)
                        check(trace.ranked == CandidateRanker.rank(trace.entries.map { it.candidate }, 255, trace.exact, trace.composed))
                        val actual = observed.wholeCandidates
                        val chinese = actual.filter { it.matchKind != CandidateMatchKind.ENGLISH_EXACT && it.matchKind != CandidateMatchKind.ENGLISH_PREFIX }
                        check(chinese.map { it.text } == trace.ranked.take(chinese.size).map { it.text })
                        val pool = trace.entries.joinToString { entry ->
                            val c = entry.candidate; val e = entry.evidence
                            maxError = maxOf(maxError, kotlin.math.abs(entry.arithmeticError))
                            check(e.edges.joinToString("") { it.text } == c.text)
                            val features = PinyinScoreDiagnostics.Feature.entries.joinToString { e[it].toString() }
                            val edges = e.edges.joinToString { """[${quote(it.text)},${quote(it.code)},${it.weight},${it.tier}]""" }
                            """{"text":${quote(c.text)},"kind":${quote(c.matchKind.name)},"canonical":${quote(c.canonicalPinyin.orEmpty())},"score":${c.score},"prior":${entry.sourcePrior},"total":${entry.total},"arithmeticError":${entry.arithmeticError},"features":[$features],"edges":[$edges],"segments":${e.segments},"normalizer":${e.normalizer}}"""
                        }
                        val winning = trace.ranked.joinToString { c -> trace.entries.indexOfFirst {
                            it.candidate.text == c.text && it.candidate.score == c.score && it.candidate.matchKind == c.matchKind
                        }.also { check(it >= 0) }.toString() }
                        writer.appendLine("""{"type":"row","id":${quote(row[0])},"stratum":${quote(row[3])},"cut":$cut,"mode":${quote(contextMode)},"context":${quote(context)},"query":${quote(query)},"expected":${quote(expected)},"exact":${trace.exact},"composed":${trace.composed},"rank":${actual.indexOfFirst { it.text == expected } + 1},"actual":[${actual.take(5).joinToString { quote(it.text) }}],"resultSha256":${quote(sha(observed.toString().toByteArray(Charsets.UTF_8)))},"winnerIndices":[$winning],"pool":[$pool]}""")
                        count++; paths += trace.entries.size
                        if (count % 100 == 0) println("Exported $count rows; $paths actual paths; max arithmetic error=$maxError")
                    }
                }
            }
            writer.appendLine("""{"type":"summary","rows":$count,"paths":$paths,"maxArithmeticError":$maxError,"observationEquivalent":true}""")
        }
        println("Exported $count rows, $paths paths to $output; max arithmetic error=$maxError")
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
