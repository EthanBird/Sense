package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Intrusive ART sampling of one real touch workload; never an acceptance timer. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorFastProfileTest : ExternalEditorTestFixture() {
    @Test fun firstAndRepeatedSentenceUseTheSameRealInputPath() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("noLearning"))
        val helper = "/data/local/tmp/sense-input-quality-touch/classes.dex"
        val digest = requireNotNull(InstrumentationRegistry.getArguments().getString("touchInjectorSha256"))
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(digest, shell("sha256sum $helper").substringBefore(' '))
        val uid = shell("cmd package list packages -U $SENSE").lineSequence()
            .single { it.startsWith("package:$SENSE uid:") }.substringAfter(" uid:").trim().toInt()
        val pid = shell("pidof $SENSE:ime").trim().also { require(it.matches(Regex("[0-9]+"))) }
        val records = File(artifacts, "profile-input.jsonl").apply { writeText("") }
        for ((index, pair) in listOf("nihao" to "你好", "woxihuanbeijing" to "我喜欢北京", "woxihuanbeijing" to "我喜欢北京").withIndex()) {
            val (query, expected) = pair
            onMain { activity.first.text.clear() }; SystemClock.sleep(120); typingBounds = keyboardBounds()
            val remote = "/data/local/tmp/$evidenceRun-$index.methods"
            if (index > 0) {
                assertEquals("IME process must stay alive", pid, shell("pidof $SENSE:ime").trim())
                val start = shell("am profile start --sampling 1000 --clock-type dual --profiler-output-version 3 $pid $remote")
                File(artifacts, "profile-$index-start.txt").writeText(start)
                assertFalse("Profiler start failed: $start", start.contains("Error") || start.contains("Exception"))
            }
            try {
                val sequence = query + " "
                val points = sequence.map { keyLocation(it) }.joinToString(",") { "${it.first}:${it.second}" }
                val transcript = shell("/system/bin/env CLASSPATH=$helper app_process / sense.fixture.TouchBurst $uid 32 $points")
                val source = JSONObject(requireNotNull(transcript.lineSequence().singleOrNull { it.startsWith("{") }))
                assertEquals("ok", source.getString("status")); assertEquals(uid, source.getInt("targetUid"))
                val deadline = SystemClock.uptimeMillis() + 8_000
                while ((text() != expected || composing()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
                val row = JSONObject().put("index", index).put("query", query).put("expected", expected).put("actual", text())
                    .put("composingFinished", !composing()).put("pid", pid).put("source", source)
                    .put("profiled", index > 0).put("remoteProfile", if (index > 0) remote else "")
                records.appendText(row.toString() + "\n")
                assertEquals(expected, text()); assertFalse(composing())
            } finally {
                if (index > 0) File(artifacts, "profile-$index-stop.txt").writeText(shell("am profile stop $SENSE:ime"))
            }
        }
    }
}
