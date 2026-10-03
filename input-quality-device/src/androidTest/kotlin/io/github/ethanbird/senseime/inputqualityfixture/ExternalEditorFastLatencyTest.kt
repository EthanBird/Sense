package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed known workload. The 类/累 baseline is deliberately not an accuracy label. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorFastLatencyTest : ExternalEditorTestFixture() {
    @Test fun fastConfirmationsAndContinuedTypingPreserveTheCompleteInput() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("noLearning"))
        val helper = "/data/local/tmp/sense-input-quality-touch/classes.dex"
        val digest = requireNotNull(InstrumentationRegistry.getArguments().getString("touchInjectorSha256"))
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(digest, shell("sha256sum $helper").substringBefore(' '))
        val uid = shell("cmd package list packages -U $SENSE").lineSequence()
            .single { it.startsWith("package:$SENSE uid:") }.substringAfter(" uid:").trim().toInt()
        val cases = listOf("nihao" to "你好", "woxihuanbeijing" to "我喜欢北京", "qingfageweizhigeiwo" to "请发个位置给我",
            "qingjianchayixiayoujian" to "请检查一下邮件", "zhegeyingyonghenhaoyong" to "这个应用很好用",
            "jintianyouyidianlei" to "今天有一点类", "tangmuhemalimeitianwanshangdoukandianshi" to "汤姆和玛丽每天晚上都看电视",
            "tangmumeiyouzhuyidaomalihuanlexinfaxing" to "汤姆没有注意到玛丽换了新发型")
        val timings = File(artifacts,"latency.tsv").apply {
            writeText("round\tquery\texpectedBaselineOutput\tactual\tspaceToEditorMs\tpassed\n")
        }
        val cadence = File(artifacts,"burst-cadence.tsv").apply {
            writeText("round\tquery\ttargetIntervalMs\tactualStartIntervalsMs\tspaceInjectionStartMs\tfirstExpectedTextMs\n")
        }
        val records = File(artifacts,"fast-input.jsonl").apply { writeText("") }
        val continued = File(artifacts,"continued-input.jsonl").apply { writeText("") }
        val failures = mutableListOf<String>()
        fun execute(sequence: String, expected: String): JSONObject {
            onMain { activity.first.text.clear() }; SystemClock.sleep(120); typingBounds = keyboardBounds()
            val changes = mutableListOf<Pair<Long,String>>()
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value=s?.toString().orEmpty()
                    if (changes.lastOrNull()?.second != value) changes += SystemClock.uptimeMillis() to value
                }
            }
            onMain { activity.first.addTextChangedListener(watcher) }
            try {
                val points=sequence.map { keyLocation(it) }.joinToString(",") { "${it.first}:${it.second}" }
                val transcript=shell("/system/bin/env CLASSPATH=$helper app_process / sense.fixture.TouchBurst $uid 32 $points")
                val encoded=transcript.lineSequence().singleOrNull { it.startsWith("{") }
                assertNotNull("Bounded shell source failed: $transcript",encoded)
                val source=JSONObject(encoded!!)
                assertEquals("ok",source.getString("status"));assertEquals(2000,source.getInt("sourceUid"));assertEquals(uid,source.getInt("targetUid"))
                val starts=source.getJSONArray("keyStartMs");assertEquals(sequence.length,starts.length())
                val end=starts.getLong(starts.length()-1)+8_000
                while ((text()!=expected || composing()) && SystemClock.uptimeMillis()<end) SystemClock.sleep(10)
                val snapshot=onMain { changes.toList() }
                return JSONObject().put("sequence",sequence).put("expected",expected).put("actual",text())
                    .put("composingFinished",!composing()).put("source",source)
                    .put("editorChanges",JSONArray(snapshot.map { JSONObject().put("uptimeMs",it.first).put("text",it.second) }))
                    .put("firstExpectedTextMs",snapshot.firstOrNull { it.second==expected }?.first ?: -1)
            } finally { onMain { activity.first.removeTextChangedListener(watcher) } }
        }
        repeat(2) { round ->
            for ((query,expected) in if(round==0) cases else cases.reversed()) {
                val record=execute(query+" ",expected).put("round",round).put("query",query)
                val starts=record.getJSONObject("source").getJSONArray("keyStartMs")
                val space=starts.getLong(query.length);val observed=record.getLong("firstExpectedTextMs")
                val changes=record.getJSONArray("editorChanges")
                val prefixes=(0 until changes.length()).map { changes.getJSONObject(it).getString("text") }
                    .filter { it.isNotEmpty() && query.startsWith(it) }
                val intact=prefixes==(1..query.length).map { query.take(it) }
                val passed=record.getString("actual")==expected && record.getBoolean("composingFinished") && intact && observed>=space
                record.put("prefixesIntact",intact).put("passed",passed);records.appendText(record.toString()+"\n")
                timings.appendText("$round\t$query\t$expected\t${record.getString("actual")}\t${observed-space}\t$passed\n")
                cadence.appendText("$round\t$query\t32\t${(1 until query.length).joinToString(",") { (starts.getLong(it)-starts.getLong(it-1)).toString() }}\t$space\t$observed\n")
                if(!passed) failures += "confirmation $round/$query"
                SystemClock.sleep(150)
            }
        }
        for ((query,expected) in listOf(cases[3],cases[7])) {
            val record=execute(query+" nihao ",expected+"你好")
            val passed=record.getString("actual")==expected+"你好" && record.getBoolean("composingFinished")
            record.put("passed",passed);continued.appendText(record.toString()+"\n")
            if(!passed) failures += "continued $query -> ${record.getString("actual") }"
        }
        assertTrue("Fast input delivery failed: $failures",failures.isEmpty())
    }
}
