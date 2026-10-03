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

/** Explicit research artifact in this test package only; never enables the IME factory. */
@RunWith(AndroidJUnit4::class)
class FourgramModelDeviceTest {
    @Test fun frozenExtensionCostAndRepeatedResultsOnArt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val runName = InstrumentationRegistry.getArguments().getString("fourgramRunName") ?: "e18"
        require(runName.matches(Regex("[a-z][a-z0-9-]{0,47}"))) { "Invalid run name" }
        val output = File(directory, "$runName-fourgram-art.json")
        check(!output.exists()) { "Keep previous measurement" }
        val baseBytes = context.assets.open("pinyin_character_lm.scng").use { it.readBytes() }
        val extension = File(directory, "e18-balanced-fourgram.scq4").readBytes()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("39ea5d90a38ce2b6f498a0dcc9f4b161ea92168759ebeedd03ce2f3340c45a7d", sha(baseBytes))
        assertEquals("75371c3861341bee9a0b0bb488ac1e95295aec317a8de1cc48360be13f818bb4", sha(extension))
        val loadSamples = Array(2) { mutableListOf<Long>() }
        val models = arrayOfNulls<CharacterLanguageModel>(2)
        repeat(4) { cycle ->
            for (mode in if (cycle % 2 == 0) listOf(0, 1) else listOf(1, 0)) {
                val start = SystemClock.elapsedRealtimeNanos()
                models[mode] = if (mode == 0) BinaryCharacterLanguageModel.fromBytes(baseBytes)
                    else BinaryFourgramLanguageModel.fromBytes(baseBytes, extension)
                loadSamples[mode] += SystemClock.elapsedRealtimeNanos() - start
            }
        }
        val base = context.assets.open("pinyin_lexicon.bin").use {
            PinyinDecoder.load(it, context.assets.open("pinyin_bigrams.bin").use(BinaryCharacterBigramModel::load))
        }
        val segmenter = PinyinSyllableSegmenter(context.assets.open("pinyin_syllables.txt").bufferedReader().use { it.readLines() })
        val english = context.assets.open("english_lexicon.txt").use(EnglishLexicon::load)
        val decoders = models.map { AdaptivePinyinDecoder(base.withLanguageModel(requireNotNull(it), .5f, -4f),
            MemoryUserLexicon(), segmenter, english) }
        val queries = listOf("nihao", "chengche", "zhinengti", "wox", "woxiangchifan", "womenmingtianjian",
            "qingfageweizhigeiwo", "tangmumeiyouzhuyidaomalihuanlexinfaxing")
        val compositions = queries.map { PinyinComposition(emptyList(), it) }
        val references = decoders.map { d -> compositions.map { d.decodeProgressively(it, "", 255) } }
        repeat(3) { for (d in decoders) for (c in compositions) d.decodeProgressively(c, "", 255) }
        val records = mutableListOf<String>()
        // ABBA per query; 8 measured repetitions for each decoder/query pair.
        repeat(4) { cycle ->
            for ((index, composition) in compositions.withIndex()) {
                for (mode in if (cycle % 2 == 0) listOf(0, 1, 1, 0) else listOf(1, 0, 0, 1)) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = decoders[mode].decodeProgressively(composition, "", 255)
                    val ns = SystemClock.elapsedRealtimeNanos() - start
                    assertEquals(references[mode][index], result)
                    records += """{"model":$mode,"cycle":$cycle,"query":"${queries[index]}","nanos":$ns,"first":"${result.wholeCandidates.firstOrNull()?.text.orEmpty()}"}"""
                }
            }
        }
        assertEquals(128, records.size)
        output.writeText("""{"schemaVersion":1,"scope":"Single API ${android.os.Build.VERSION.SDK_INT} emulator ART process; fixed queries, shared immutable dictionary, warm in-process decoding; not end-to-end IME latency or phone testing", "baseSha256":"${sha(baseBytes)}","extensionSha256":"${sha(extension)}","loadExcludesIoNanos":[[${loadSamples[0].joinToString()}],[${loadSamples[1].joinToString()}]],"extensionEstimatedRetainedBytes":${(models[1] as BinaryFourgramLanguageModel).estimatedRetainedBytes},"baselineEstimatedRetainedBytes":${(models[0] as BinaryCharacterLanguageModel).estimatedRetainedBytes},"records":[${records.joinToString()}],"repeatedResultsEqual":true,"factoryChanged":false}""")
    }
}
