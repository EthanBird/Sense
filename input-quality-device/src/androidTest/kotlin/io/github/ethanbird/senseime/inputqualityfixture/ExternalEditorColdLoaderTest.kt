package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real loading generation held at its lock-free entry by the host JDI debugger. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorColdLoaderTest : ExternalEditorTestFixture() {
    private fun marker(suffix: String) = File(artifacts, "${name.methodName}.$suffix")
    private fun delayedLoader(block: () -> Unit) {
        require(coldStart && InstrumentationRegistry.getArguments().getString("hostLoaderPause") == "true")
        await("Host parked only the loader at its entry", 20_000) { marker("paused").exists() }
        assertRuntimeStillLoading("after_loader_breakpoint")
        try { block() } finally { releaseLoader() }
    }
    private fun releaseLoader() {
        marker("resume").writeText("resume")
        await("Host released loader", 20_000) { marker("resumed").exists() }
    }
    private fun ready() = await("Production runtime published", 20_000) {
        runtimeState().contains("ready=true") && runtimeState().contains("characterModel=READY")
    }
    private fun waiting(raw: String, crossDeadline: Boolean = false) {
        if (crossDeadline) SystemClock.sleep(5_500)
        assertRuntimeStillLoading("confirmation_waiting")
        assertEquals(raw, text())
        assertTrue("Waiting spelling stays composing", composing())
        val dump = shell("dumpsys input_method")
        assertTrue("Chinese confirmation retained", dump.contains("pending=true"))
        if (crossDeadline) assertTrue("Loading notice deadline reached", dump.contains("attention=true"))
        File(artifacts, "${name.methodName}-waiting.txt").writeText(dump)
        device.takeScreenshot(File(artifacts, "${name.methodName}-waiting.png"))
    }

    @Test fun coldConfirmationRetainsQueuedWordsAcrossLoadingNoticeDeadline() = delayedLoader {
        type("woxihuanbeijing", 12)
        assertRuntimeStillLoading("before_space")
        key(' ')
        type("nihao", 12)
        key(' ')
        waiting("woxihuanbeijing", crossDeadline = true)
        releaseLoader()
        ready()
        await("Both queued Chinese confirmations arrive once") { text() == "我喜欢北京你好" && !composing() }
        SystemClock.sleep(400)
        assertEquals("我喜欢北京你好", text())
    }

    @Test fun coldEnterExplicitlyKeepsRawAndPublicationDoesNotOverwriteIt() = delayedLoader {
        type("sensecustomword", 12)
        assertRuntimeStillLoading("before_space")
        key(' ')
        waiting("sensecustomword")
        key('\n')
        await("Enter confirms raw while loader is stopped") { text() == "sensecustomword" && !composing() }
        releaseLoader()
        ready()
        SystemClock.sleep(400)
        assertEquals("sensecustomword", text())
    }

    @Test fun coldDeleteCorrectsPendingSpellingBeforePublication() = delayedLoader {
        type("nihaoa", 12)
        key(' ')
        waiting("nihaoa")
        key('\b')
        await("Delete edits loading composition") { text() == "nihao" && composing() }
        assertRuntimeStillLoading("before_corrected_space")
        key(' ')
        releaseLoader()
        ready()
        await("Corrected Chinese confirmation arrives") { text() == "你好" && !composing() }
    }

    @Test fun coldEditorSwitchInvalidatesOldConfirmationAndKeepsNewInput() = delayedLoader {
        type("woxihuanbeijing", 12)
        key(' ')
        type("nihao", 12)
        waiting("woxihuanbeijing")
        focus("Second external editor")
        await("Second editor responds while loading is stopped") { onMain { activity.second.hasFocus() } }
        SystemClock.sleep(100)
        val firstAfterSwitch = text()
        type("nihao", 12)
        key(' ')
        assertRuntimeStillLoading("second_editor_pending")
        releaseLoader()
        ready()
        await("Only new word reaches new editor") { onMain { activity.second.text.toString() == "你好" } }
        SystemClock.sleep(400)
        assertEquals(firstAfterSwitch, text())
        assertEquals("你好", onMain { activity.second.text.toString() })
    }
}
