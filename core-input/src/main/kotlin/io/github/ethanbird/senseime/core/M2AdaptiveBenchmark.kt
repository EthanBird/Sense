package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Regression gate for statistical short codes, typo correction and one-shot initials learning. */
object M2AdaptiveBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: M2AdaptiveBenchmark <lexicon> <syllables> <report>" }
        val lexiconFile = File(args[0])
        val syllablesFile = File(args[1])
        val report = File(args[2])
        report.parentFile?.mkdirs()

        lateinit var base: PinyinDecoder
        val loadNs = measureNanoTime {
            base = lexiconFile.inputStream().buffered().use(PinyinDecoder::load)
        }
        val shortCode = base.decode("w").firstOrNull()
        check(shortCode?.text == "我" && shortCode.matchKind == CandidateMatchKind.BASE_PREFIX) {
            "Statistical short-code smoke test failed: $shortCode"
        }
        check(base.decode("shi", 128).size > 64) { "Expanded homophone lexicon smoke test failed" }
        check(base.decode("xian", 128).none { it.text == "先思" }) { "Exact/prefix isolation smoke test failed" }
        val ambiguous = base.decode("fangan", 128).associateBy { it.text }
        check(ambiguous["方案"]?.canonicalInitials == "fa") { "方案 boundary metadata is invalid" }
        check(ambiguous["反感"]?.canonicalInitials == "fg") { "反感 boundary metadata is invalid" }
        val typoExpectations = mapOf(
            "nihoa" to "你好",
            "dagn" to "当",
            "tain" to "天",
            "nnihao" to "你好",
            "nijao" to "你好",
        )
        typoExpectations.forEach { (query, expected) ->
            val candidate = base.decode(query, 10).firstOrNull()
            check(candidate?.text == expected) { "Typo correction failed for $query: $candidate" }
        }
        val longTypoQuery = "woshiyigezhongguoern"
        val longTypo = base.decode(longTypoQuery).firstOrNull()
        check(longTypo?.text == "我是一个中国人") {
            "Long sentence typo correction smoke test failed: $longTypo"
        }

        val segmenter = PinyinSyllableSegmenter(syllablesFile.readLines())
        val learned = MemoryUserLexicon(clock = { 1_000L })
        val adaptive = AdaptivePinyinDecoder(base, learned, segmenter)
        val sentence = base.decode("woshiyigeren").first()
        check(sentence.text == "我是一个人")
        check(adaptive.learn("woshiyigeren", sentence) != null)
        check(adaptive.decode("wsygr").firstOrNull()?.text == "我是一个人") {
            "Initials learning smoke test failed"
        }

        val exactQueries = listOf("w", "ni", "nihao", "zhongwen", "shurufa", "xiansi")
        val exactLookups = 20_000
        val exactSamples = LongArray(7) {
            measureNanoTime {
                repeat(exactLookups) { index -> check(base.decode(exactQueries[index % exactQueries.size], 6).isNotEmpty()) }
            }
        }
        val typoQueries = typoExpectations.keys.toList()
        val typoLookups = 100
        val typoSamples = LongArray(5) {
            measureNanoTime {
                repeat(typoLookups) { index -> check(base.decode(typoQueries[index % typoQueries.size], 6).isNotEmpty()) }
            }
        }
        val longTypoLookups = 20
        val longTypoSamples = LongArray(5) {
            measureNanoTime {
                repeat(longTypoLookups) { check(base.decode(longTypoQuery, 6).firstOrNull()?.text == "我是一个中国人") }
            }
        }
        val largeUserLexicon = MemoryUserLexicon(
            initial = List(10_000) { index ->
                val suffix = alphaCode(index)
                LearnedPhrase("u$suffix", "x$suffix", "词$index", 1, 1_000L, 1_000L)
            },
        )
        val userLookups = 20_000
        val userSamples = LongArray(7) {
            measureNanoTime {
                repeat(userLookups) { check(largeUserLexicon.lookup("x${alphaCode(9_999)}", 6).isNotEmpty()) }
            }
        }

        report.writeText(
            """
            {
              "schemaVersion": 2,
              "stage": "M2",
              "generatedAt": "${Instant.now()}",
              "lexicon": {
                "keys": ${recordCount(lexiconFile)},
                "bytes": ${lexiconFile.length()},
                "loadMs": ${format(loadNs / 1_000_000.0)}
              },
              "exactLookup": {
                "medianNs": ${format(perLookup(exactSamples, exactLookups, percentile = 0.5))},
                "p95Ns": ${format(perLookup(exactSamples, exactLookups, percentile = 0.95))}
              },
              "typoLookup": {
                "medianMs": ${format(perLookup(typoSamples, typoLookups, percentile = 0.5) / 1_000_000.0)},
                "p95Ms": ${format(perLookup(typoSamples, typoLookups, percentile = 0.95) / 1_000_000.0)}
              },
              "longSentenceTypoLookup": {
                "medianMs": ${format(perLookup(longTypoSamples, longTypoLookups, percentile = 0.5) / 1_000_000.0)},
                "p95Ms": ${format(perLookup(longTypoSamples, longTypoLookups, percentile = 0.95) / 1_000_000.0)}
              },
              "userLexicon10kLookup": {
                "medianNs": ${format(perLookup(userSamples, userLookups, percentile = 0.5))},
                "p95Ns": ${format(perLookup(userSamples, userLookups, percentile = 0.95))}
              },
              "deviceMetrics": { "status": "pending-real-device" }
            }
            """.trimIndent() + "\n",
        )
        println("M2 adaptive baseline written to ${report.absolutePath}")
    }

    private fun perLookup(samples: LongArray, lookups: Int, percentile: Double): Double {
        val sorted = samples.sorted()
        val index = nearestRankIndex(sorted.size, percentile)
        return sorted[index].toDouble() / lookups
    }

    fun nearestRankIndex(sampleCount: Int, percentile: Double): Int {
        require(sampleCount > 0) { "At least one sample is required" }
        require(percentile in 0.0..1.0) { "Percentile must be between 0 and 1" }
        return (ceil(percentile * sampleCount).toInt() - 1).coerceIn(0, sampleCount - 1)
    }

    private fun format(value: Double): String = "%.2f".format(Locale.US, value)

    private fun recordCount(file: File): Int = file.inputStream().buffered().use { input ->
        val header = input.readNBytes(10)
        require(header.size == 10 && header.copyOfRange(0, 4).decodeToString() == "SPLX")
        ((header[6].toInt() and 0xFF) shl 24) or
            ((header[7].toInt() and 0xFF) shl 16) or
            ((header[8].toInt() and 0xFF) shl 8) or
            (header[9].toInt() and 0xFF)
    }

    private fun alphaCode(value: Int): String {
        var remaining = value
        return buildString {
            repeat(4) {
                append('a' + remaining % 26)
                remaining /= 26
            }
        }
    }
}
