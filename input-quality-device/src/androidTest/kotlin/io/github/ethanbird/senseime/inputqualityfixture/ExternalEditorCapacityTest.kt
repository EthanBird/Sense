package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual IME process and seeded SQLite, not an in-fixture decoder clone. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorCapacityTest : ExternalEditorTestFixture() {
    @Test fun seededPersonalDictionarySurvivesDefaultReuseAndProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        assertNotEquals("true", args.getString("noLearning"))
        val seedFile = File(artifacts, "seed.json")
        assertEquals(args.getString("seedSha256"), sha(seedFile.readBytes()))
        val seed = JSONObject(seedFile.readText())
        assertTrue(seed.getInt("size") in listOf(1000, 10000))
        val helper = "/data/local/tmp/sense-input-quality-touch/classes.dex"
        assertEquals(args.getString("touchInjectorSha256"), shell("sha256sum $helper").substringBefore(' '))
        val uid = shell("cmd package list packages -U $SENSE").lineSequence()
            .single { it.startsWith("package:$SENSE uid:") }.substringAfter(" uid:").trim().toInt()
        val output = File(artifacts, "capacity.jsonl").apply { writeText("") }
        val failures = mutableListOf<String>()
        fun record(value: JSONObject) { output.appendText(value.toString()+"\n") }
        record(JSONObject().put("type","header").put("size",seed.getInt("size"))
            .put("seedSha256",args.getString("seedSha256")).put("initialPid",shell("pidof $SENSE:ime").trim())
            .put("runtime",runtimeState()).put("scope","Real shell touch -> current IME -> external editor; synthetic SQLite capacity, not natural accuracy or phone frames"))

        repeat(2) { phase ->
            val cases = seed.getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val query = case.getString("query")
                val context = case.getString("context")
                val expected = context + case.getString("expected")
                onMain { activity.first.setText(context); activity.first.setSelection(context.length) }
                SystemClock.sleep(150)
                typingBounds = keyboardBounds()
                val changes = mutableListOf<Pair<Long,String>>()
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
                    val sequence = query + " "
                    val points = sequence.map { keyLocation(it) }.joinToString(",") { "${it.first}:${it.second}" }
                    val transcript = shell("/system/bin/env CLASSPATH=$helper app_process / sense.fixture.TouchBurst $uid 32 $points")
                    val source = JSONObject(transcript.lineSequence().single { it.startsWith("{") })
                    assertEquals("ok", source.getString("status"))
                    assertEquals(2000, source.getInt("sourceUid")); assertEquals(uid, source.getInt("targetUid"))
                    val starts = source.getJSONArray("keyStartMs")
                    assertEquals(sequence.length, starts.length())
                    val deadline = starts.getLong(query.length)+8_000
                    while ((text()!=expected || composing()) && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(10)
                    val snapshot = onMain { changes.toList() }
                    val prefixes = snapshot.map { it.second }.filter { it != context &&
                        it.startsWith(context) && query.startsWith(it.removePrefix(context)) }
                    val intact = prefixes == (1..query.length).map { context + query.take(it) }
                    val committed = snapshot.firstOrNull { it.second==expected }?.first ?: -1
                    val passed = text()==expected && !composing() && intact && committed>=starts.getLong(query.length)
                    record(JSONObject().put("type","row").put("phase",phase).put("query",query)
                        .put("context",context).put("expected",expected).put("actual",text())
                        .put("sequence",sequence).put("source",source).put("prefixesIntact",intact)
                        .put("editorChanges",JSONArray(snapshot.map { JSONObject().put("uptimeMs",it.first).put("text",it.second) }))
                        .put("composingFinished",!composing()).put("firstExpectedTextMs",committed)
                        .put("spaceToEditorMs",committed-starts.getLong(query.length)).put("passed",passed))
                    if (!passed) failures += "phase=$phase query=$query expected=$expected actual=${text()} intact=$intact"
                } finally { onMain { activity.first.removeTextChangedListener(watcher) } }
            }
            if (phase == 0) {
                // Allow the existing asynchronous writer to drain, then observe a real new PID.
                SystemClock.sleep(600)
                val previous = shell("pidof $SENSE:ime").trim()
                assertTrue(previous.isNotBlank())
                // Hiding the keyboard leaves the selected IME bound. Select the previous
                // IME first so system rebinding does not race our stopped-process assertion.
                assertTrue("Capacity fixture needs a different original IME", previousIme.isNotBlank() && previousIme != IME && previousIme != "null")
                device.pressBack(); shell("ime set $previousIme"); shell("am force-stop $SENSE")
                val barrier = shell("am wait-for-broadcast-idle")
                assertTrue(barrier.contains("All broadcast queues are idle"))
                val stoppedPid = shell("pidof $SENSE:ime || true").trim()
                assertTrue("Expected stopped IME after unbinding, actual PID=$stoppedPid", stoppedPid.isEmpty())
                val start = SystemClock.uptimeMillis()
                shell("ime enable $IME"); shell("ime set $IME"); focus("First external editor")
                assertNotNull(device.wait(Until.findObject(By.descStartsWith("先思键盘")),10_000))
                await("Fresh production runtime ready",20_000) {
                    val state=runtimeState(); state.contains("ready=true") && state.contains("characterModel=READY")
                }
                val observed = SystemClock.uptimeMillis()
                val current = shell("pidof $SENSE:ime").trim()
                assertTrue(current.isNotBlank()); assertNotEquals(previous,current)
                record(JSONObject().put("type","restart").put("oldPid",previous).put("newPid",current)
                    .put("selectionToObservedReadyMs",observed-start).put("runtime",runtimeState())
                    .put("scope","Includes IME selection, shell, UI and polling; not isolated SQLite loading or rendered frame timing"))
                SystemClock.sleep(600)
            }
        }
        SystemClock.sleep(600)
        record(JSONObject().put("type","summary").put("rows",14).put("failures",JSONArray(failures)).put("passed",failures.isEmpty()))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
