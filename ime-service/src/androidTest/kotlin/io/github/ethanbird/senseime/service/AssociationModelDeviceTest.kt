package io.github.ethanbird.senseime.service

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ethanbird.senseime.core.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** ART asset/parser/query diagnostic; separate from real system IME touch-to-editor tests. */
@RunWith(AndroidJUnit4::class)
class AssociationModelDeviceTest {
    @Test fun packagedContinuationLookupHasBoundedCostAndKeepsPersonalHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = SystemClock.elapsedRealtimeNanos()
        val result = BundledAssociationModel.load { context.assets.open(it) }
        val loadNs = SystemClock.elapsedRealtimeNanos() - before
        assertEquals(BundledAssociationModel.State.READY, result.state)
        val bigrams = context.assets.open("pinyin_bigrams.bin").use(BinaryCharacterBigramModel::load)
        val history = MemoryUserAssociationLexicon(clock = { 1_000L })
        val current = LocalAssociationEngine(history, bigrams, requireNotNull(result.model))
        val legacy = LocalAssociationEngine(MemoryUserAssociationLexicon(), bigrams)
        assertEquals("早上", current.suggest("今天", includeUserHistory = false).first().text)
        assertTrue(current.suggest("你好", includeUserHistory = false).isEmpty())
        current.observe("你好", "朋友")
        assertEquals("朋友", current.suggest("你好").first().text)
        assertTrue(current.suggest("你好", includeUserHistory = false).isEmpty())
        val contexts = listOf("今天", "你好", "今天我们", "今天我", "你好世界", "喜欢", "天气", "正在",
            "未知𠮷", "谢谢", "北京", "到了今天")
        val engines = listOf("legacy" to legacy, "selected" to current)
        repeat(500) { i -> engines.forEach { it.second.suggest(contexts[i % contexts.size], includeUserHistory = false) } }
        var checksum = 0
        val samples = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val timings = Array(2) { LongArray(2_000) }
        // Alternate method order to avoid placing one permanently in the first JIT/GC phase.
        repeat(2_000) { i ->
            for (index in if (i % 2 == 0) listOf(0, 1) else listOf(1, 0)) {
                val start = SystemClock.elapsedRealtimeNanos()
                val words = engines[index].second.suggest(contexts[i % contexts.size], includeUserHistory = false)
                timings[index][i] = SystemClock.elapsedRealtimeNanos() - start
                checksum += words.sumOf { it.text.length }
            }
        }
        engines.forEachIndexed { i, (name, _) ->
            val sorted = timings[i].sorted()
            summaries += "\"$name\":{\"queries\":2000,\"medianNs\":${sorted[999]},\"p95Ns\":${sorted[1899]},\"maxNs\":${sorted.last()}}"
            samples += "\"$name\":[${timings[i].joinToString(",")}]"
        }
        assertTrue(checksum > 0)
        val hash = context.assets.open(BundledAssociationModel.ASSET).use { stream ->
            MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") { "%02x".format(it) }
        }
        File(context.filesDir, "f1-association-art.json").writeText("""
            {"schemaVersion":1,"scope":"ART microbenchmark in library instrumentation; 12 fixed contexts, no user history during timed lookup; not IME latency, heap profiling, or phone certification",
            "modelSha256":"$hash","sdk":${android.os.Build.VERSION.SDK_INT},"loadIncludingReadHashAndValidationNs":$loadNs,
            "checksum":$checksum,"summary":{${summaries.joinToString(",")}},"samplesNs":{${samples.joinToString(",")}}}
        """.trimIndent())
    }
}
