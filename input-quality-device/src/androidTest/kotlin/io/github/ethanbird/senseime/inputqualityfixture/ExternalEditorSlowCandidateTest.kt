package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The host runner pauses ONLY the idle candidate worker via JDWP, not the UI thread. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorSlowCandidateTest : ExternalEditorTestFixture() {
    private fun marker(suffix: String) = File(artifacts, "${name.methodName}.$suffix")
    private fun pausedWorker(block: () -> Unit) {
        require(InstrumentationRegistry.getArguments().getString("hostWorkerPause") == "true") {
            "Run tools/test_slow_candidate.py; this test requires host-side thread control"
        }
        type("nihao", 20) // creates the lazy worker, then leaves it parked without owning a lock
        assertNotNull(device.wait(Until.findObject(By.text("你好").descStartsWith("候选词，")), 5_000))
        key('\n')
        onMain { activity.first.setText("") }
        SystemClock.sleep(700)
        marker("ready").writeText("ready")
        await("Host paused idle candidate worker", 20_000) { marker("paused").exists() }
        try { block() } finally { releaseWorker() }
    }
    private fun releaseWorker() {
        marker("resume").writeText("resume")
        await("Host resumed candidate worker", 20_000) { marker("resumed").exists() }
    }
    private fun assertStillWaiting(raw: String) {
        SystemClock.sleep(1_500) // crosses the production one-second notice deadline
        assertEquals("Space may not silently submit raw spelling", raw, text())
        assertTrue("Raw must remain composing, not become an implicit commit", composing())
        val state = shell("dumpsys input_method")
        assertTrue("Recovery notice must be active", state.contains("pending=true attention=true"))
        File(artifacts, "${name.methodName}-waiting.txt").writeText(state)
        device.takeScreenshot(File(artifacts, "${name.methodName}-waiting.png"))
        device.dumpWindowHierarchy(File(artifacts, "${name.methodName}-waiting.xml"))
    }

    @Test fun delayedChineseConfirmationPreservesBothWordsAndOrder() = pausedWorker {
        type("woxihuanbeijing", 12)
        key(' ')
        type("nihao", 12)
        key(' ')
        assertStillWaiting("woxihuanbeijing")
        releaseWorker()
        await("Both Chinese words arrive after worker resumes") { text() == "我喜欢北京你好" && !composing() }
        SystemClock.sleep(400)
        assertEquals("我喜欢北京你好", text())
    }

    @Test fun enterExplicitlyKeepsRawDuringSlowWait() = pausedWorker {
        type("woxihuanbeijing", 12)
        key(' ')
        assertStillWaiting("woxihuanbeijing")
        key('\n')
        await("Enter retains visible raw without waiting for worker") { text() == "woxihuanbeijing" && !composing() }
        releaseWorker()
        SystemClock.sleep(500)
        assertEquals("woxihuanbeijing", text())
    }

    @Test fun deleteCanCorrectTheCompositionBeforeWorkerResumes() = pausedWorker {
        type("nihaoa", 12)
        key(' ')
        assertStillWaiting("nihaoa")
        key('\b')
        await("Delete edits pinyin while worker is paused") { text() == "nihao" && composing() }
        key(' ')
        releaseWorker()
        await("Corrected word commits, never the obsolete spelling") { text() == "你好" && !composing() }
    }

    @Test fun editorSwitchInvalidatesDelayedConfirmationAndQueuedInput() = pausedWorker {
        type("woxihuanbeijing", 12)
        key(' ')
        type("nihao", 12)
        assertStillWaiting("woxihuanbeijing")
        focus("Second external editor")
        await("New editor is responsive with decoder paused") { onMain { activity.second.hasFocus() } }
        releaseWorker()
        SystemClock.sleep(700)
        assertEquals("", onMain { activity.second.text.toString() })
    }
}
