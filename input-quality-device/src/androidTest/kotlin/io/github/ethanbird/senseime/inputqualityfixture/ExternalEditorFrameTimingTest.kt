package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** External input + actual IME-process HWUI frames; never conflates editor acknowledgement with a frame. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorFrameTimingTest : ExternalEditorTestFixture() {
    @Test fun fastTypingAndExpandedScrollingCaptureTheirOwnImeFrames() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("noLearning"))
        val helper = "/data/local/tmp/sense-input-quality-touch/classes.dex"
        val digest = requireNotNull(InstrumentationRegistry.getArguments().getString("touchInjectorSha256"))
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(digest, shell("sha256sum $helper").substringBefore(' '))
        val uid = shell("cmd package list packages -U $SENSE").lineSequence()
            .single { it.startsWith("package:$SENSE uid:") }.substringAfter(" uid:").trim().toInt()
        val pid = shell("pidof $SENSE:ime").trim()
        assertTrue(pid.matches(Regex("[0-9]+")))
        val records = File(artifacts, "frame-workload.jsonl").apply { writeText("") }
        val cases = listOf("nihao" to "你好", "woxihuanbeijing" to "我喜欢北京",
            "tangmumeiyouzhuyidaomalihuanlexinfaxing" to "汤姆没有注意到玛丽换了新发型")

        fun begin(label: String): Long {
            assertEquals("IME process changed during the fixed workload", pid, shell("pidof $SENSE:ime").trim())
            File(artifacts, "$label-reset.txt").writeText(shell("dumpsys gfxinfo $SENSE:ime reset"))
            return System.nanoTime()
        }
        fun finishFrames(label: String, start: Long): JSONObject {
            // Finish already-issued HWUI work before reading the ring; no concurrent CPU profiler.
            SystemClock.sleep(200)
            val end = System.nanoTime()
            val data = shell("dumpsys gfxinfo $SENSE:ime framestats")
            File(artifacts, "$label-frames.txt").writeText(data)
            assertTrue("Expected frames from the IME process, not the settings Activity", data.contains("[$SENSE:ime]"))
            assertTrue("HWUI frame stream absent", data.contains("---PROFILEDATA---"))
            return JSONObject().put("label", label).put("startNanos", start).put("endNanos", end)
                .put("imePid", pid).put("frameFile", "$label-frames.txt")
        }

        repeat(2) { round ->
            for ((index, pair) in (if (round == 0) cases else cases.reversed()).withIndex()) {
                val (query, expected) = pair
                onMain { activity.first.text.clear() }; SystemClock.sleep(200)
                typingBounds = keyboardBounds()
                val changes = mutableListOf<Pair<Long, String>>()
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val value = s?.toString().orEmpty()
                        if (changes.lastOrNull()?.second != value) changes += SystemClock.uptimeMillis() to value
                    }
                }
                onMain { activity.first.addTextChangedListener(watcher) }
                try {
                    val label = "typing-$round-$index"
                    val start = begin(label)
                    val sequence = "$query "
                    val points = sequence.map { keyLocation(it) }.joinToString(",") { "${it.first}:${it.second}" }
                    val transcript = shell("/system/bin/env CLASSPATH=$helper app_process / sense.fixture.TouchBurst $uid 32 $points")
                    val source = JSONObject(transcript.lineSequence().single { it.startsWith("{") })
                    assertEquals("ok", source.getString("status"))
                    await("Complete external editor confirmation") { text() == expected && !composing() }
                    val snapshot = onMain { changes.toList() }
                    val prefixes = snapshot.map { it.second }.filter { it.isNotEmpty() && query.startsWith(it) }
                    assertEquals("All raw prefixes delivered in order", (1..query.length).map { query.take(it) }, prefixes)
                    val row = finishFrames(label, start).put("kind", "typing").put("round", round)
                        .put("query", query).put("expected", expected).put("actual", text())
                        .put("sequence", sequence).put("source", source).put("passed", true)
                        .put("editorChanges", JSONArray(snapshot.map { JSONObject().put("uptimeMs", it.first).put("text", it.second) }))
                        .put("firstExpectedTextMs", snapshot.first { it.second == expected }.first)
                    records.appendText(row.toString() + "\n")
                    assertEquals(typingBounds, keyboardBounds())
                } finally { onMain { activity.first.removeTextChangedListener(watcher) } }
            }

            onMain { activity.first.text.clear() }; SystemClock.sleep(200)
            typingBounds = keyboardBounds(); type("shi", 20)
            requireNotNull(device.wait(Until.findObject(By.desc("展开候选")), 5_000)).click()
            assertNotNull(device.wait(Until.findObject(By.desc("收起候选")), 3_000))
            val before = device.findObjects(By.descStartsWith("候选词，")).map { it.contentDescription }
            SystemClock.sleep(200)
            val label = "expanded-$round"; val start = begin(label)
            val box = keyboardBounds(); val x = box.centerX()
            assertTrue(device.swipe(x, box.bottom - dp(105f).toInt(), x, box.top + dp(60f).toInt(), 25))
            SystemClock.sleep(600)
            val row = finishFrames(label, start).put("kind", "expanded").put("round", round)
            val visible = device.findObjects(By.descStartsWith("候选词，"))
            assertTrue("Continuous scroll reveals new source candidates", visible.any { it.contentDescription !in before })
            val selected = visible.first { it.visibleBounds.height() >= dp(16f) }
            val word = selected.text
            device.takeScreenshot(File(artifacts, "$label.png"))
            selected.click()
            await("Scrolled candidate commits exactly once") { text() == word && !composing() }
            records.appendText(row.put("selectedWord", word).put("actual", text()).put("passed", true).toString() + "\n")
            assertEquals(typingBounds, keyboardBounds())
        }
    }
}
