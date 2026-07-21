package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.system.measureNanoTime

/** Host-side regression baseline for the production 39k-key pinyin lexicon. */
object M1PinyinBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: M1PinyinBenchmark <lexicon> <report>" }
        val lexicon = File(args[0])
        val report = File(args[1])
        report.parentFile?.mkdirs()

        lateinit var decoder: PinyinDecoder
        val loadNs = measureNanoTime {
            decoder = lexicon.inputStream().buffered().use(PinyinDecoder::load)
        }
        val queries = listOf("ni", "nihao", "zhongwen", "shurufa", "xiansi", "gongzuo", "jintian", "women")
        check(decoder.decode("nihao").firstOrNull()?.text == "你好") { "Production lexicon smoke test failed" }
        check(decoder.decode("woshiyigeren").firstOrNull()?.text == "我是一个人") {
            "Production sentence composition smoke test failed"
        }

        val lookupsPerSample = 20_000
        val samples = LongArray(7) {
            measureNanoTime {
                repeat(lookupsPerSample) { index ->
                    check(decoder.decode(queries[index % queries.size], 6).isNotEmpty())
                }
            }
        }
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2].toDouble() / lookupsPerSample
        val p95 = sorted.last().toDouble() / lookupsPerSample

        report.writeText(
            """
            {
              "schemaVersion": 1,
              "stage": "M1",
              "generatedAt": "${Instant.now()}",
              "lexicon": {
                "bytes": ${lexicon.length()},
                "sha256": "${sha256(lexicon)}",
                "loadMs": ${"%.2f".format(Locale.US, loadNs / 1_000_000.0)}
              },
              "lookup": {
                "samples": ${samples.size},
                "lookupsPerSample": $lookupsPerSample,
                "medianNs": ${"%.2f".format(Locale.US, median)},
                "p95Ns": ${"%.2f".format(Locale.US, p95)}
              },
              "deviceMetrics": {
                "status": "pending-real-device"
              }
            }
            """.trimIndent() + "\n",
        )
        println("M1 pinyin baseline written to ${report.absolutePath}")
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
