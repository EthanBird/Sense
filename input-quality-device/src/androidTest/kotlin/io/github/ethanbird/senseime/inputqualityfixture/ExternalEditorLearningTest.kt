package io.github.ethanbird.senseime.inputqualityfixture

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the disposable input-quality AVD with a fresh debug profile, not a user's profile. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorLearningTest : ExternalEditorTestFixture() {
    @Test fun recalledCompletionAliasSurvivesDefaultReuseAndProcessRestart() {
        type("beiji", 25)
        assertEquals("北极", firstCandidate())
        evidence("before_completion_alias_first=${firstCandidate()}")
        chooseCandidate("北京")
        await("Explicit completion commits into the external editor") { text() == "北京" && !composing() }
        repeat(2) { index ->
            clearExternalEditor()
            assertFirstThenCommit("beiji", "北京", "completion_alias_reuse_${index + 1}")
        }
        restartImeProcess()
        clearExternalEditor()
        assertFirstThenCommit("beiji", "北京", "completion_alias_after_process_restart")
        clearExternalEditor()
        assertFirstThenCommit("beijing", "北京", "completion_canonical_after_process_restart")
    }

    @Test fun explicitlySelectedCorrectionAliasSurvivesReuseAndProcessRestart() {
        type("nime", 25)
        assertEquals("The ordinary literal completion is the starting point", "你们", firstCandidate())
        evidence("before_alias_first=${firstCandidate()}")
        chooseCandidate("你的")
        await("Explicit correction commits into the external editor") { text() == "你的" && !composing() }
        repeat(2) { index ->
            clearExternalEditor()
            assertFirstThenCommit("nime", "你的", "alias_reuse_${index + 1}")
        }
        restartImeProcess()
        clearExternalEditor()
        assertFirstThenCommit("nime", "你的", "alias_after_process_restart")
    }

    @Test fun noPersonalizedLearningEditorDoesNotPromoteItsPrivateSelections() {
        type("chengche", 25)
        val baseline = firstCandidate()
        evidence("ordinary_before_private_first=$baseline")
        assertNotEquals("A fresh private phrase is required", "程澈", baseline)
        reopenExternalEditor(noLearning = true)
        type("chengche", 25)
        chooseCandidate("程")
        chooseCandidate("澈")
        await("Private field still supports progressive input") { text() == "程澈" && !composing() }
        evidence("private_commit=程澈")
        clearExternalEditor()
        type("chengche", 25)
        assertEquals("Private selections should not update in-memory personal ranking", baseline, firstCandidate())
        evidence("private_after_selection_first=${firstCandidate()}")
        reopenExternalEditor(noLearning = false)
        restartImeProcess()
        type("chengche", 25)
        assertEquals("Private selections should not update restored personal ranking", baseline, firstCandidate())
        evidence("ordinary_after_restart_first=${firstCandidate()}")
    }

    @Test fun progressiveNameSelectionSurvivesImmediateReuseAndProcessRestart() {
        type("chengche", 25)
        val baseline = firstCandidate()
        evidence("before_learning_first=$baseline")
        assertNotEquals("A fresh profile is required for this learning test", "程彻", baseline)
        chooseCandidate("程")
        chooseCandidate("彻")
        await("Progressive selection commits the complete name") { text() == "程彻" && !composing() }
        evidence("learned_through=touchscreen progressive candidates")

        repeat(2) { index ->
            clearExternalEditor()
            assertFirstThenCommit("chengche", "程彻", "immediate_${index + 1}")
        }
        restartImeProcess()
        clearExternalEditor()
        assertFirstThenCommit("chengche", "程彻", "after_process_restart")

        clearExternalEditor()
        type("zhinengti", 25)
        chooseCandidate("智能体")
        await("Explicit 智能体 selection") { text() == "智能体" && !composing() }
        clearExternalEditor()
        assertFirstThenCommit("zhinengti", "智能体", "intelligent_agent_reuse")
        restartImeProcess()
        clearExternalEditor()
        assertFirstThenCommit("zhinengti", "智能体", "intelligent_agent_restart")
        clearExternalEditor()
        assertFirstThenCommit("zhinengtikeyibangmang", "智能体可以帮忙", "layered_word_sentence_start")
        clearExternalEditor()
        assertFirstThenCommit("wodezhinengti", "我的智能体", "layered_word_sentence_end")
    }

    private fun assertFirstThenCommit(spelling: String, expected: String, phase: String) {
        type(spelling, 25)
        val first = firstCandidate()
        evidence("${phase}_first=$first")
        assertEquals("Learned phrase should remain the first full-spelling candidate ($phase)", expected, first)
        if (phase == "after_process_restart" || phase == "intelligent_agent_restart") {
            device.takeScreenshot(File(artifacts, "${name.methodName}-$phase.png"))
            device.dumpWindowHierarchy(File(artifacts, "${name.methodName}-$phase.xml"))
        }
        key(' ')
        await("Space commits learned phrase ($phase)") { text() == expected && !composing() }
    }

    private fun firstCandidate(): String {
        val node = device.wait(Until.findObject(By.descStartsWith("候选词，1，")), 5_000)
        assertNotNull("First candidate should be ready", node)
        return node.text
    }

    private fun chooseCandidate(value: String) {
        val selector = By.text(value).descStartsWith("候选词，")
        // Pending batches expose no clickable words. Wait for the current ready batch.
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("候选词，")), 5_000))
        var node = device.findObject(selector)
        if (node == null) {
            device.findObject(By.desc("展开候选"))?.click()
            SystemClock.sleep(150)
            node = device.findObject(selector)
        }
        var lastVisible = ""
        repeat(35) {
            if (node != null) {
                evidence("selected=${node!!.contentDescription}")
                if (value == "程") {
                    val ordinal = node!!.contentDescription.split('，')[1].toInt()
                    assertTrue("Progressive name selection must stay near the list head: $ordinal", ordinal <= 32)
                }
                node!!.click()
                SystemClock.sleep(200)
                return
            }
            val visibleNodes = device.findObjects(By.descStartsWith("候选词，"))
            val visible = visibleNodes.joinToString("|") { it.contentDescription }
            if (visible == lastVisible) {
                evidence("missing_${value}_last_visible=$visible")
                fail("Candidate $value absent after continuous expanded scrolling")
            }
            lastVisible = visible
            // The root accessibility bounds also include the bottom system inset/footer.
            // Use actual candidate cells so DOWN lands in the scrolling grid, not its footer.
            val cells = visibleNodes.map { it.visibleBounds }.filter { !it.isEmpty }
            assertTrue("Visible candidate cells are required for a grid swipe", cells.isNotEmpty())
            val top = cells.minOf { it.top }
            val bottom = cells.maxOf { it.bottom }
            val x = keyboardBounds().centerX()
            val margin = minOf(dp(16f).toInt(), (bottom - top) / 4)
            evidence("grid_swipe=$x,${bottom-margin}->$x,${top+margin}; visible_cells=${cells.size}")
            device.swipe(x, bottom - margin, x, top + margin, 35)
            SystemClock.sleep(200)
            node = device.findObject(selector)
        }
        fail("Candidate $value not found within the bounded scroll search")
    }

    private fun clearExternalEditor() {
        onMain { activity.first.text.clear() }
        SystemClock.sleep(250)
        typingBounds = keyboardBounds()
    }

    private fun reopenExternalEditor(noLearning: Boolean) {
        onMain { activity.finish() }
        instrumentation.waitForIdleSync()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ExternalEditorActivity::class.java)
            .putExtra("noLearning", noLearning)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ExternalEditorActivity
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("先思键盘")), 10_000))
        await("Runtime ready after editor privacy change", 20_000) { runtimeState().contains("ready=true") }
        SystemClock.sleep(300)
        typingBounds = keyboardBounds()
    }

    private fun restartImeProcess() {
        val previous = shell("pidof $SENSE:ime").trim()
        assertTrue(previous.isNotBlank())
        device.pressBack()
        shell("am force-stop $SENSE")
        val barrier = shell("am wait-for-broadcast-idle")
        evidence("restart_stop_barrier=$barrier")
        assertTrue("Drain package-stop broadcasts before reselecting the IME",
            barrier.contains("All broadcast queues are idle"))
        shell("ime enable $IME")
        shell("ime set $IME")
        focus("First external editor")
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("先思键盘")), 10_000))
        await("Restarted production runtime is ready", 20_000) {
            val state = runtimeState()
            state.contains("ready=true") && state.contains("characterModel=READY")
        }
        val current = shell("pidof $SENSE:ime").trim()
        evidence("process_restart=$previous->$current")
        assertTrue(current.isNotBlank())
        assertNotEquals("This must be a real IME process restart", previous, current)
        SystemClock.sleep(300)
        typingBounds = keyboardBounds()
    }

    private fun evidence(value: String) = File(artifacts, "${name.methodName}.txt").appendText("$value\n")
}
