package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import kotlin.system.measureNanoTime

/** Paired synthetic-error diagnostics; source labels never participate in decoder search. */
object M15TypoRecallBenchmark {
    data class Case(val id: String, val sourceId: String, val typed: String, val canonical: String,
        val expected: String, val stratum: String, val syllables: List<String>, val operation: String,
        val zone: String, val editOffset: Int)

    fun readCases(file: File): List<Case> = file.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
        val f = line.split('\t')
        require(f.size == 10 && f[0].matches(Regex("[a-f0-9]{64}")) && f[1].matches(Regex("[a-f0-9]{64}")))
        val row = Case(f[0],f[1],f[2],f[3],f[4],f[5],f[6].split(' '),f[7],f[8],f[9].toInt())
        require(row.canonical == row.syllables.joinToString("") && row.canonical.matches(Regex("[a-z]{1,96}")))
        require(row.typed.matches(Regex("[a-z']{1,192}")))
        require(row.expected.codePointCount(0,row.expected.length) == row.syllables.size)
        require(row.operation in setOf("clean","joints","delete","repeat","neighbor","transpose","fuzzy"))
        require(row.zone in setOf("none","head","middle","tail","phonetic"))
        row
    }.also { require(it.isNotEmpty() && it.map(Case::id).distinct().size == it.size) }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 4..7) { "Usage: <root> <replay> <new output> <graph limit> [correction boost] [lexicon asset] [OOV feature]" }
        val root = File(args[0]); val input = File(args[1]); val out = File(args[2]); val graphLimit = args[3].toInt()
        require(!out.exists()) { "Retain prior evidence; use a new report" }
        require(graphLimit in 1..1024)
        val rows = readCases(input)
        fun asset(name: String) = if (name == "pinyin_lexicon.bin" && args.size >= 6) File(args[5])
            else File(root,"ime-service/src/main/assets/$name")
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
        val correctionBoost = args.getOrNull(4)?.toFloat() ?: 12f
        val oovFeature = args.getOrNull(6)?.toFloat() ?: -4f
        val languageModel = asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load)
        val bound = base.withLanguageModel(languageModel, .5f, oovFeature)
            .withCorrectionCalibration(correctionBoost)
        val decoder = AdaptivePinyinDecoder(bound, MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
        // Diagnose exactly the inventory loaded by the production lexicon, not a substitute
        // inventory. Reflection is confined to this host benchmark, no shipping test hook.
        val graph = PinyinDecoder::class.java.getDeclaredField("spellingGraph").apply { isAccessible = true }.get(base) as PinyinSpellingGraph
        val observations = rows.mapIndexed { index, row ->
            val effectiveQuery = PinyinSyllableSegmenter.normalize(row.typed)
            lateinit var decoding: ProgressivePinyinDecoding
            var correctionTrace: CorrectionSearchDiagnostics.Trace? = null
            val ns = measureNanoTime {
                CorrectionSearchDiagnostics.observe({ if (it.query == row.typed) correctionTrace = it }) {
                    decoding = decoder.decodeProgressively(PinyinComposition(remainingPinyin = row.typed), "", 255)
                }
            }
            val candidates = decoding.wholeCandidates
            val paths = graph.paths(row.typed, graphLimit)
            val ends = row.syllables.runningFold(0) { offset, unit -> offset+unit.length }.drop(1)
            val rank = candidates.indexOfFirst { it.text == row.expected }+1
            val graphRank = paths.indexOfFirst { it.canonical == row.canonical }+1
            val alignedRank = paths.indexOfFirst { it.canonical == row.canonical && it.syllableEnds == ends }+1
            val top = candidates.take(5).joinToString { """{"text":${quote(it.text)},"canonical":${quote(it.canonicalPinyin.orEmpty())},"kind":${quote(it.matchKind.name)},"score":${it.score}}""" }
            val selected = correctionTrace?.selected.orEmpty().joinToString { """{"canonical":${quote(it.canonical)},"cost":${it.cost},"syllableEnds":${it.syllableEnds}}""" }
            if ((index+1)%100 == 0) println("Processed ${index+1}/${rows.size}")
            """{"id":${quote(row.id)},"sourceId":${quote(row.sourceId)},"typed":${quote(row.typed)},"effectiveQuery":${quote(effectiveQuery)},"decoderInput":${quote(row.typed)},"canonical":${quote(row.canonical)},"expected":${quote(row.expected)},"operation":${quote(row.operation)},"zone":${quote(row.zone)},"stratum":${quote(row.stratum)},"editOffset":${row.editOffset},"rank":$rank,"graphCanonicalRank":$graphRank,"graphAlignedRank":$alignedRank,"probeRank":${correctionTrace?.probes?.indexOfFirst { it.canonical == row.canonical }?.plus(1) ?: 0},"selectedExpectedSpelling":${correctionTrace?.selected?.any { it.canonical == row.canonical } == true},"selectedProbes":[$selected],"canonicalCandidateCount":${candidates.count { it.canonicalPinyin == row.canonical }},"candidateCount":${candidates.size},"characterErrors":${M8DailyInputBenchmark.editDistance(candidates.firstOrNull()?.text.orEmpty(),row.expected)},"decodeNs":$ns,"top5":[$top]}"""
        }
        val core = File(root,"core-input/src/main/kotlin")
        val sources = core.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.relativeTo(core).invariantSeparatorsPath }
            .joinToString { quote(it.relativeTo(core).invariantSeparatorsPath)+":"+quote(sha(it)) }
        val assets = listOf("pinyin_lexicon.bin","pinyin_bigrams.bin","pinyin_character_lm.scng","pinyin_syllables.txt","english_lexicon.txt")
            .joinToString { quote(it)+":"+quote(sha(asset(it))) }
        out.parentFile?.mkdirs()
        out.writeText("""{"schemaVersion":4,"oovFeature":$oovFeature,"correctionCompositionBoost":$correctionBoost,"progressiveJoints":"Explicit joints are passed unchanged to progressive decoding; effectiveQuery denotes only normalized dictionary lookup","scope":"Known source reconstruction with frozen synthetic errors; host final progressive decode, not Android latency or natural-error accuracy","inputSha256":${quote(sha(input))},"candidateLimit":255,"graphDiagnosticLimit":$graphLimit,"lmWeight":0.5,"learning":false,"assets":{$assets},"sources":{$sources},"observations":[${observations.joinToString()}]}"""+"\n")
        println("Wrote ${rows.size} observations to $out") // No held-out scores printed before freeze.
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun quote(value: String) = "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""
}
