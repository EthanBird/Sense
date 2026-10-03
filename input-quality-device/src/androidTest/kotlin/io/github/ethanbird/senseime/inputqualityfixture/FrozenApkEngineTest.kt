package io.github.ethanbird.senseime.inputqualityfixture

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** APK bytecode diagnosis, not an IME/UI benchmark. No dependency on the shipping core module. */
@RunWith(AndroidJUnit4::class)
class FrozenApkEngineTest {
    @Test fun uncancelledWholeQueriesFromOneFrozenApk() {
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val mode = requireNotNull(args.getString("engineMode")).also { require(it in listOf("reference", "candidate")) }
        val hash = requireNotNull(args.getString("engineSha256")).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val run = requireNotNull(args.getString("evidenceRun")).also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        val output = File(context.getExternalFilesDir(null), "input-quality/$run").apply { check(mkdirs()) }
        val source = File(context.getExternalFilesDir(null), "e22-apks/$mode.apk")
        assertEquals(hash, sha(source))
        val apk = File(context.filesDir, "engine-$hash.apk")
        if (!apk.exists()) {
            // Android 14+: mark the destination read-only before writing through the open FD.
            FileOutputStream(apk).use { target ->
                check(apk.setReadOnly())
                source.inputStream().use { it.copyTo(target) }
            }
        }
        assertEquals(hash, sha(apk))
        assertFalse(apk.canWrite())
        val loader = DexClassLoader(apk.absolutePath, context.codeCacheDir.absolutePath, null, context.classLoader.parent)
        fun cls(name: String) = loader.loadClass("io.github.ethanbird.senseime.core.$name")
        fun companion(name: String) = cls(name).getField("Companion").get(null)
        fun companionCall(name: String, method: String, types: Array<Class<*>>, vararg values: Any): Any {
            val c = companion(name)
            return c.javaClass.getMethod(method, *types).invoke(c, *values)!!
        }
        val data = ZipFile(apk).use { zip ->
            listOf("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_character_lm.scng", "pinyin_syllables.txt", "english_lexicon.txt")
                .associateWith { name -> zip.getInputStream(zip.getEntry("assets/$name")).use { it.readBytes() } }
        }
        val bigram = companionCall("BinaryCharacterBigramModel", "fromBytes", arrayOf(ByteArray::class.java), data.getValue("pinyin_bigrams.bin"))
        val budget = companionCall("CorrectionSearchBudget", "getPRODUCTION", emptyArray())
        val base = companionCall("PinyinDecoder", "fromBytes",
            arrayOf(ByteArray::class.java, cls("CharacterBigramModel"), cls("CorrectionSearchBudget")),
            data.getValue("pinyin_lexicon.bin"), bigram, budget)
        val model = companionCall("BinaryCharacterLanguageModel", "fromBytes", arrayOf(ByteArray::class.java), data.getValue("pinyin_character_lm.scng"))
        val bound = cls("PinyinDecoder").getMethod("withLanguageModel", cls("CharacterLanguageModel"), Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(base, model, .5f, -4f)
        val memory = cls("MemoryUserLexicon").getConstructor().newInstance()
        val syllables = data.getValue("pinyin_syllables.txt").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        val segmenter = cls("PinyinSyllableSegmenter").getConstructor(Collection::class.java).newInstance(syllables)
        val emptyEnglish = companionCall("EnglishWordUsageStore", "getEMPTY", emptyArray())
        val english = companionCall("EnglishLexicon", "load", arrayOf(InputStream::class.java, Int::class.javaPrimitiveType!!, cls("EnglishWordUsageStore")),
            data.getValue("english_lexicon.txt").inputStream(), 80_000, emptyEnglish)
        val decoder = cls("AdaptivePinyinDecoder").getConstructor(cls("InputDecoder"), cls("UserLexicon"), cls("PinyinSyllableSegmenter"), cls("EnglishLexicon"))
            .newInstance(bound, memory, segmenter, english)
        assertSame(loader, decoder.javaClass.classLoader)
        assertSame(loader, loader.loadClass("kotlin.Unit").classLoader)
        assertNotSame(Unit::class.java.classLoader, loader.loadClass("kotlin.Unit").classLoader)
        val composition = cls("PinyinComposition").getConstructor(List::class.java, String::class.java, Long::class.javaPrimitiveType)
        val decode = cls("AdaptivePinyinDecoder").getMethod("decodeProgressively", cls("PinyinComposition"), CharSequence::class.java, Int::class.javaPrimitiveType)
        val whole = cls("ProgressivePinyinDecoding").getMethod("getWholeCandidates")
        val text = cls("Candidate").getMethod("getText")
        val cases = listOf(
            "nihao" to "你好", "woxihuanbeijing" to "我喜欢北京", "qingfageweizhigeiwo" to "请发个位置给我",
            "qingjianchayixiayoujian" to "请检查一下邮件", "zhegeyingyonghenhaoyong" to "这个应用很好用",
            "jintianyouyidianlei" to "今天有一点类", // retain the existing semantic error
            "tangmuhemalimeitianwanshangdoukandianshi" to "汤姆和玛丽每天晚上都看电视",
            "tangmumeiyouzhuyidaomalihuanlexinfaxing" to "汤姆没有注意到玛丽换了新发型",
            "nime" to if (mode == "reference") "你的" else "你们",
            "renme" to if (mode == "reference") "人的" else "人们",
        )
        val identities = mutableMapOf<String, String>()
        val header = JSONObject().put("type", "header").put("mode", mode).put("apkSha256", hash)
            .put("pid", android.os.Process.myPid()).put("repetitions", 6).put("queries", JSONArray(cases.map { it.first }))
            .put("classLoader", loader.javaClass.name).put("parent", loader.parent.javaClass.name)
            .put("configuration", JSONObject().put("lmWeight", .5).put("oovFeature", -4).put("limit", 255).put("learning", false))
            .put("assets", JSONObject(data.mapValues { sha(it.value) }))
            .put("scope", "Uncancelled ART engine calls from frozen APK. Not system IME timing; excludes loading/serialization.")
        File(output, "engine.jsonl").bufferedWriter().use { writer ->
            writer.appendLine(header.toString()); writer.flush()
            repeat(6) { round ->
                for ((query, expected) in if (round % 2 == 0) cases else cases.reversed()) {
                    val composing = composition.newInstance(emptyList<Any>(), query, 0L)
                    val startCpu = Debug.threadCpuTimeNanos()
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = decode.invoke(decoder, composing, "", 255)!!
                    val elapsed = SystemClock.elapsedRealtimeNanos() - start
                    val cpu = Debug.threadCpuTimeNanos() - startCpu
                    val candidates = whole.invoke(result) as List<*>
                    val actual = text.invoke(candidates.first()) as String
                    val digest = sha(result.toString().toByteArray(Charsets.UTF_8))
                    val stable = identities.getOrPut(query) { digest } == digest
                    val row = JSONObject().put("type", "row").put("round", round).put("query", query)
                        .put("expected", expected).put("actual", actual).put("candidateCount", candidates.size)
                        .put("resultSha256", digest).put("stable", stable).put("passed", actual == expected && stable)
                        .put("wallNs", elapsed).put("threadCpuNs", cpu)
                        .put("gcCount", Debug.getRuntimeStat("art.gc.gc-count"))
                    writer.appendLine(row.toString()); writer.flush()
                    assertEquals(expected, actual)
                    assertTrue("Repeated complete candidate result changed for $query", stable)
                }
            }
            writer.appendLine(JSONObject().put("type", "summary").put("rows", 60).put("passed", true).toString())
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
