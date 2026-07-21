package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.system.measureNanoTime

/** A deterministic, dependency-free host baseline. Device Macrobenchmark lives in :benchmark. */
object M0HostBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(requireNotNull(args.firstOrNull()) { "Output path is required" })
        output.parentFile?.mkdirs()

        val reducer = InputReducer()
        val actions = "senseinputmethod".map(InputAction::Type)
        repeat(5_000) {
            var warmState = InputState()
            actions.forEach { warmState = reducer.reduce(warmState, it) }
            warmState = reducer.reduce(warmState, InputAction.Reset)
        }

        val operationsPerSample = 100_000
        val samples = LongArray(7) { sampleIndex ->
            var state = InputState()
            measureNanoTime {
                repeat(operationsPerSample) { index ->
                    state = if (index % 32 == 31) {
                        reducer.reduce(state, InputAction.Reset)
                    } else {
                        reducer.reduce(state, actions[index % actions.size])
                    }
                }
            }.also {
                check(state.revision > 0) { "Reducer did not execute in sample $sampleIndex" }
            }
        }

        val sorted = samples.sorted()
        val medianNs = sorted[sorted.size / 2]
        val p95Ns = sorted.last()
        val medianNsPerOperation = medianNs.toDouble() / operationsPerSample
        val p95NsPerOperation = p95Ns.toDouble() / operationsPerSample

        output.writeText(
            """
            {
              "schemaVersion": 1,
              "stage": "M0",
              "generatedAt": "${Instant.now()}",
              "runtime": {
                "java": "${json(System.getProperty("java.version") ?: "unknown")}",
                "os": "${json(System.getProperty("os.name") ?: "unknown")}",
                "arch": "${json(System.getProperty("os.arch") ?: "unknown")}"
              },
              "reducer": {
                "samples": ${samples.size},
                "operationsPerSample": $operationsPerSample,
                "medianNsPerOperation": ${"%.2f".format(Locale.US, medianNsPerOperation)},
                "p95NsPerOperation": ${"%.2f".format(Locale.US, p95NsPerOperation)}
              },
              "deviceMetrics": {
                "status": "pending-real-device",
                "reason": "M0 release compiles Macrobenchmark; startup and frame metrics require an Android device."
              }
            }
            """.trimIndent() + "\n",
        )
        println("M0 host baseline written to ${output.absolutePath}")
    }

    private fun json(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
