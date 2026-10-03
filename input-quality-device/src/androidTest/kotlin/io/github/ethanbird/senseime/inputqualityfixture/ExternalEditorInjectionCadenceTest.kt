package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measures event-source overhead separately from delivery to a different UID's ordinary editor. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorInjectionCadenceTest : ExternalEditorTestFixture() {
    @Test fun compareEventSourcesWithoutChangingTheProductionKeyboard() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("noLearning"))
        val output = File(artifacts, "injection-cadence.jsonl")
        output.writeText("")
        // Android hides the targeted InputManager overload from ordinary application reflection.
        // Run the independent bounded source as adb shell instead; no device-wide policy changes.
        val helperPath = "/data/local/tmp/sense-input-quality-touch/classes.dex"
        val helperHash = requireNotNull(InstrumentationRegistry.getArguments().getString("touchInjectorSha256"))
        assertTrue(helperHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(helperHash, shell("sha256sum $helperPath").substringBefore(' '))
        val uiInjection = instrumentation.uiAutomation.javaClass.getMethod("injectInputEvent",
            InputEvent::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val uidLine = shell("cmd package list packages -U $SENSE").lineSequence()
            .single { it.startsWith("package:$SENSE uid:") }
        val senseUid = uidLine.substringAfter(" uid:").trim().toInt()
        val cases = listOf("nihao" to "你好", "woxihuanbeijing" to "我喜欢北京",
            "qingjianchayixiayoujian" to "请检查一下邮件")
        val failures = mutableListOf<String>()
        run {
            for ((block, channel) in listOf("ui-automation", "input-manager", "input-manager", "ui-automation").withIndex()) {
                for ((query, expected) in cases) {
                    onMain { activity.first.text.clear() }
                    SystemClock.sleep(150)
                    typingBounds = keyboardBounds()
                    val editorChanges = mutableListOf<Pair<Long, String>>()
                    val watcher = object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val value = s?.toString().orEmpty()
                            if (editorChanges.lastOrNull()?.second != value) editorChanges += SystemClock.uptimeMillis() to value
                        }
                    }
                    var actions = JSONArray()
                    val starts = mutableListOf<Long>()
                    onMain { activity.first.addTextChangedListener(watcher) }
                    try {
                        if (channel == "input-manager") {
                            val points = (query + " ").map { keyLocation(it) }
                                .joinToString(",") { "${it.first}:${it.second}" }
                            // UiAutomation executes argv directly; an assignment prefix is not a shell command here.
                            val transcript = shell("/system/bin/env CLASSPATH=$helperPath app_process / sense.fixture.TouchBurst $senseUid 32 $points")
                            val encoded = transcript.lineSequence().singleOrNull { it.startsWith("{") }
                            assertNotNull("Shell source failed: $transcript", encoded)
                            val data = JSONObject(encoded!!)
                            assertEquals("ok", data.getString("status"))
                            assertEquals(2000, data.getInt("sourceUid"))
                            assertEquals(senseUid, data.getInt("targetUid"))
                            actions = data.getJSONArray("actions")
                            val recordedStarts = data.getJSONArray("keyStartMs")
                            assertEquals(query.length + 1, recordedStarts.length())
                            for (i in 0 until recordedStarts.length()) starts += recordedStarts.getLong(i)
                        } else for ((index, char) in (query + " ").withIndex()) {
                            val (x, y) = keyLocation(char)
                            val start = SystemClock.uptimeMillis()
                            starts += start
                            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                                val begin = SystemClock.elapsedRealtimeNanos()
                                val accepted: Boolean
                                try {
                                    accepted = uiInjection.invoke(instrumentation.uiAutomation, event, false, false) as Boolean
                                } finally { event.recycle() }
                                val end = SystemClock.elapsedRealtimeNanos()
                                actions.put(JSONObject().put("keyIndex", index).put("action", action)
                                    .put("beginNs", begin).put("endNs", end).put("accepted", accepted))
                                assertTrue("Injection call rejected $channel index=$index action=$action", accepted)
                                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(8)
                            }
                            val remaining = 32 - (SystemClock.uptimeMillis() - start)
                            if (remaining > 0 && index < query.length) SystemClock.sleep(remaining)
                        }
                        val deadline = starts.last() + 8_000
                        while ((text() != expected || composing()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
                        val actual = text()
                        val finished = actual == expected && !composing()
                        val changes = onMain { editorChanges.toList() }
                        val observedPrefixes = changes.filter { it.second.isNotEmpty() && query.startsWith(it.second) }
                        val expectedPrefixes = (1..query.length).map { query.take(it) }
                        val prefixesIntact = observedPrefixes.map { it.second } == expectedPrefixes
                        val firstCommit = changes.firstOrNull { it.second == expected }?.first ?: -1
                        val delivered = finished && prefixesIntact && firstCommit >= starts.last()
                        val row = JSONObject().put("block", block).put("channel", channel).put("query", query)
                            .put("expected", expected).put("actual", actual).put("targetIntervalMs", 32)
                            .put("keyStartMs", JSONArray(starts)).put("actions", actions)
                            .put("editorChanges", JSONArray(changes.map { JSONObject().put("uptimeMs", it.first).put("text", it.second) }))
                            .put("prefixesIntact", prefixesIntact).put("firstCommitMs", firstCommit)
                            .put("composingFinished", !composing()).put("passed", delivered)
                        output.appendText(row.toString() + "\n")
                        if (!delivered) failures += "$block/$channel/$query: actual=$actual, prefixesIntact=$prefixesIntact"
                    } finally { onMain { activity.first.removeTextChangedListener(watcher) } }
                }
            }
        }
        assertTrue("Event-source calibration delivery failures: $failures", failures.isEmpty())
    }
}
