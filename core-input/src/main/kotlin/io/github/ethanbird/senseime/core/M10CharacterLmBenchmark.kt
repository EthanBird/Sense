package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Experimental real-corpus model loader/transition gate, not a P2C ranking or Android-frame gate. */
object M10CharacterLmBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: M10CharacterLmBenchmark <SCNG model> <reference TSV> <report>" }
        val modelFile = File(args[0])
        val referenceFile = File(args[1])
        val modelHash = hash(modelFile)
        val reference = referenceFile.readLines()
        check(reference.first() == "# model-sha256=$modelHash") { "Reference belongs to another model" }
        lateinit var model: BinaryCharacterLanguageModel
        val loadNs = measureNanoTime { model = modelFile.inputStream().use(BinaryCharacterLanguageModel::load) }
        val probes = reference.filter { it.isNotBlank() && !it.startsWith('#') }.map { row ->
            val f = row.split('\t'); require(f.size == 4)
            Probe(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toFloat())
        }
        require(probes.size >= 64)
        var maxError = 0f
        probes.forEach { p -> maxError = maxOf(maxError, abs(model.logProbability(p.a, p.b, p.c) - p.expected)) }
        repeat(20) { probes.forEach { model.logProbability(it.a, it.b, it.c) } }
        var checksum = 0.0
        val samples = LongArray(10_000) { i ->
            val p = probes[i % probes.size]
            measureNanoTime { checksum += model.logProbability(p.a, p.b, p.c) }
        }.sorted()
        fun percentile(p: Double) = samples[(ceil(p * samples.size).toInt() - 1).coerceIn(samples.indices)]
        fun number(n: Number) = String.format(Locale.ROOT, "%.8f", n.toDouble())
        val passed = maxError <= 0.00001f && checksum.isFinite()
        val report = """
            {
              "schemaVersion":1,"modelSha256":"$modelHash","referenceSha256":"${hash(referenceFile)}",
              "device":"host JVM; conditional transition includes timer overhead; not Android latency",
              "modelBytes":${modelFile.length()},"estimatedRetainedPayloadBytes":${model.estimatedRetainedBytes},
              "loadMs":${number(loadNs / 1e6)},"vocabulary":${model.vocabularySize},"bigrams":${model.bigramCount},"trigrams":${model.trigramCount},
              "referenceTransitions":${probes.size},"maxAbsoluteLogScoreError":${number(maxError)},"passed":$passed,
              "transitionNs":{"samples":${samples.size},"p50":${percentile(.5)},"p95":${percentile(.95)},"p99":${percentile(.99)},"max":${samples.last()}},
              "checksum":${number(checksum)},"productionEnabled":false
            }
        """.trimIndent()
        File(args[2]).also { it.parentFile?.mkdirs(); it.writeText(report + "\n") }
        check(passed) { "Character LM interoperability regression" }
        println("SCNG interop: ${probes.size} transitions, max error=$maxError, bytes=${modelFile.length()}")
    }

    private data class Probe(val a: Int, val b: Int, val c: Int, val expected: Float)
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
