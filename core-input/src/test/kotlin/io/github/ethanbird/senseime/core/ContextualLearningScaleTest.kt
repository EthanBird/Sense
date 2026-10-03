package io.github.ethanbird.senseime.core

import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Assert.*
import org.junit.Test

/** Capacity fixtures only: these rows are synthetic, not a corpus or a user's dictionary. */
class ContextualLearningScaleTest {
    @Test fun contextualLookupAndStorageStayCorrectAtThePersonalDictionaryCap() {
        val now = 10_000_000L
        val contexts = listOf("人民", "获得", "今天", "明天", "公司", "项目", "家里", "学校")
        val observations = mutableListOf<String>()
        for (size in listOf(1_000, 10_000)) {
            val rows = M9PersonalSentenceBenchmark.capacityRows().take(size - 2).mapIndexed { i, row ->
                row.copy(aliases = setOf("qqq"), contextSelections = contexts.associateWith { now - i - 100 })
            } + listOf(
                LearnedPhrase("quanli", "ql", "权力", 1, now, now, aliases = setOf("qvanli"),
                    contextSelections = mapOf("人民" to now, "获得" to now - 1)),
                LearnedPhrase("quanli", "ql", "权利", 10, now, now, aliases = setOf("qvanli"),
                    contextSelections = mapOf("获得" to now, "人民" to now - 1)),
            )
            // Round-trip every persisted context map, not only the two expectation rows.
            val decodedRows = rows.map { row ->
                val encoded = UserContextSelection.encode(row.contextSelections)
                val restored = UserContextSelection.decode(encoded)
                assertEquals(row.contextSelections, restored)
                row.copy(contextSelections = restored)
            }
            val store = MemoryUserLexicon(initial = decodedRows, clock = { now })
            assertEquals(size, decodedRows.count { row -> store.lookup(row.fullPinyin, 64).any { it.text == row.text } })
            for (limit in listOf(1, 12, 255)) for (query in listOf("quanli", "qvanli")) {
                assertEquals("权力", store.lookupInContext(query, "人民", limit).first().text)
                assertEquals("权利", store.lookupInContext(query, "获得", limit).first().text)
            }
            assertEquals(128, store.indexedCandidateCount("qqq"))
            val queries = listOf("quanli", "qvanli", "ql", "qqq", "bbb", "bababa", "unknown")
            for (query in queries) {
                assertTrue(store.indexedCandidateCount(query) <= 320)
                assertEquals(store.lookup(query, 12), store.lookupInContext(query, "人民。", 12))
                assertFalse(store.lookupInContext("ql", "人民", 12).any { it.preferredInContext })
                for (contextual in listOf(false, true)) {
                    fun lookup() = if (contextual) store.lookupInContext(query, "人民", 12) else store.lookup(query, 12)
                    repeat(50) { lookup() }
                    val samples = LongArray(500) { measureNanoTime { lookup() } }
                    val sorted = samples.sorted()
                    val row = """{"rows":$size,"query":"$query","contextual":$contextual,"indexedHits":${store.indexedCandidateCount(query)},"p50Ns":${sorted[249]},"p95Ns":${sorted[474]},"maxNs":${sorted.last()},"samplesNs":[${samples.joinToString()}]}"""
                    observations += row
                    println(row.substringBefore(",\"samplesNs\"") + "}")
                }
            }
        }
        // Optional raw capture; ordinary unit runs do not mutate checked-in benchmark evidence.
        System.getenv("SENSE_CONTEXT_SCALE_REPORT")?.let { path ->
            val report = File(path)
            check(!report.exists()) { "Keep previous raw measurements" }
            report.parentFile?.mkdirs()
            report.writeText("""{"scope":"Windows host JVM synthetic capacity; lookup only, not Android end-to-end latency; fixed 50 warmups and 500 samples, no timing pass gate","observations":[${observations.joinToString()}]}""" + "\n")
        }
    }
}
