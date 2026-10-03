package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class ContextAssociationModelTest {
    @Test fun longestContextUsesWholeWordsAndKnownStopSuppressesShorterFallback() {
        val bytes = model("天" to listOf("气" to -2f), "今天" to listOf("早上" to -1f), "明天" to emptyList(),
            "𠮷今天" to listOf("晚上" to -1f))
        val model = BinaryContextAssociationModel.fromBytes(bytes)
        bytes.fill(0) // Parser owns its own immutable storage.
        assertEquals(listOf("早上"), model.suggest("到了今天", 8).map { it.text })
        assertEquals(listOf("晚上"), model.suggest("𠮷今天", 8).map { it.text })
        assertEquals(listOf("气"), model.suggest("昨天", 8).map { it.text })
        assertTrue(model.suggest("明天", 8).isEmpty())
        assertTrue(model.suggest("今天。", 8).isEmpty())
        assertTrue(model.suggest("今天", 0).isEmpty())
        assertEquals(2f, model.suggest("今天", 1).single().score, 0f)
    }

    @Test fun malformedBudgetOrderingUtf8AndScoresAreRejected() {
        val valid = model("天" to listOf("早上" to -1f))
        val bad = listOf(byteArrayOf(), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            ByteArray(BinaryContextAssociationModel.MAX_BYTES + 1),
            valid.copyOf().also { it[6] = 127 },
            model("天" to emptyList(), "天" to emptyList()),
            model("x" to emptyList()),
            model("天" to listOf("abc" to -1f)),
            model("天" to listOf("早上" to Float.NaN)),
            model("天" to listOf("早上" to 1f)),
            model("天" to listOf("早上" to -2f, "晚上" to -1f)),
            model("天" to listOf("早上" to -1f, "早上" to -1f)),
            valid.copyOf().also { it[20] = 0xff.toByte() })
        bad.forEach { bytes ->
            try { BinaryContextAssociationModel.fromBytes(bytes); fail("Accepted malformed ${bytes.size}-byte model") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun allFrozenDevelopmentContextsMatchThePythonExporterThroughProductionEngine() {
        val model = BinaryContextAssociationModel.fromBytes(asset("ime-service/src/main/assets/association_words.snwp").readBytes())
        val engine = LocalAssociationEngine(MemoryUserAssociationLexicon(), CharacterBigramModel.EMPTY, model)
        var rows = 0
        GZIPInputStream(asset("benchmarks/association/dev-parity.tsv.gz").inputStream()).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                val expected = parts.drop(1).filter { it.isNotEmpty() }
                assertEquals(parts[0], expected, engine.suggest(parts[0], 8, false).map { it.text })
                rows++
            }
        }
        assertTrue(rows > 10_000)
    }

    @Test fun strictUtf8RejectsOverlongSurrogateTruncatedAndOutOfRangeScalars() {
        val prefix = model("天" to emptyList()).copyOf().also { it[18] = 1 }
        val malformed = listOf(
            byteArrayOf(0xe0.toByte(), 0x81.toByte(), 0x81.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x84.toByte(), 0xb8.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xe4.toByte(), 0x81.toByte()),
            byteArrayOf(0xe4.toByte(), 0x01.toByte(), 0x81.toByte()),
        )
        malformed.forEach { word ->
            val value = prefix + byteArrayOf(word.size.toByte()) + word + byteArrayOf(0xbf.toByte(), 0x80.toByte(), 0, 0)
            try { BinaryContextAssociationModel.fromBytes(value); fail("Accepted non-canonical UTF-8") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun freshHistorySurvivesStopAndPrivateQueriesNeverReadHistory() {
        var now = 1_000L
        val store = MemoryUserAssociationLexicon(clock = { now })
        val context = BinaryContextAssociationModel.fromBytes(model("天" to listOf("早上" to -.1f, "晚上" to -.2f), "好" to emptyList()))
        val engine = LocalAssociationEngine(store, CharacterBigramModel.EMPTY, context)
        engine.observe("好", "朋友")
        engine.observe("天", "吃饭")
        assertEquals("朋友", engine.suggest("你好").first().text)
        assertTrue(engine.suggest("你好", includeUserHistory = false).isEmpty())
        assertEquals("吃饭", engine.suggest("今天").first().text)
        now += 365L * 24L * 60L * 60L * 1_000L
        assertEquals("早上", engine.suggest("今天").first().text)
        assertEquals(1, engine.suggest("今天", limit = 1).size)
    }

    private fun asset(path: String): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, path) }.first { it.isFile }

    private fun model(vararg rows: Pair<String, List<Pair<String, Float>>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SNWP"); out.writeShort(1); out.writeInt(rows.size)
            rows.forEach { (context, words) ->
                var key = 0L
                context.codePoints().forEach { key = (key shl 21) or it.toLong() }
                out.writeLong(key); out.writeByte(words.size)
                words.forEach { (word, score) ->
                    val text = word.toByteArray(Charsets.UTF_8)
                    out.writeByte(text.size); out.write(text); out.writeFloat(score)
                }
            }
        }
    }.toByteArray()
}
