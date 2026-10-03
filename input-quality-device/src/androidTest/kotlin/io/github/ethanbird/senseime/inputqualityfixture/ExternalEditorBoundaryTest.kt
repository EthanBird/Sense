package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Typed apostrophes, not benchmark-only normalized spelling or inferred display marks. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorBoundaryTest : ExternalEditorTestFixture() {
    @Test fun physicalSeparatorKeepsTheExternalComposingSpanAlive() {
        type("xi'", 12)
        await("Typed boundary must stay in the editor composition") { text() == "xi'" && composing() }
        evidence("typed_boundary_composing=true")
        device.takeScreenshot(File(artifacts, "${name.methodName}-active.png"))
        type("an", 12)
        key(' ')
        await("Explicit xi + an commits 西安") { text() == "西安" && !composing() }
    }

    @Test fun rapidBoundaryAndEnterPreserveTheExactRawInput() {
        type("xi'an", 0)
        key('\n')
        await("Enter keeps the user's literal spelling") { text() == "xi'an" && !composing() }
        SystemClock.sleep(300)
        assertEquals("xi'an", text())
    }

    @Test fun duplicateBoundaryDoesNotEndOrDuplicateTheTransaction() {
        type("xi''an", 12)
        await("Duplicate boundary is a no-op") { text() == "xi'an" && composing() }
        key('\n')
        await("One separator remains after Enter") { text() == "xi'an" && !composing() }
    }

    @Test fun backspaceCanRemoveTheTypedBoundaryBeforeContinuing() {
        type("xi'", 12)
        await("Initial explicit separator") { text() == "xi'" && composing() }
        key('\b')
        await("Delete removes only the separator") { text() == "xi" && composing() }
        type("an", 12)
        key('\n')
        await("Edited raw input remains continuous") { text() == "xian" && !composing() }
    }

    @Test fun selectedPrefixAndUndoRetainTheExactBoundary() {
        type("xi'an", 12)
        select("西")
        await("Prefix consumes xi plus its separator") { text() == "西an" && composing() }
        repeat(3) { key('\b'); SystemClock.sleep(20) }
        await("Undo prefix restores the typed raw span") { text() == "xi'" && composing() }
        type("an", 12)
        key(' ')
        await("Restored boundary is still enforced") { text() == "西安" && !composing() }
    }

    @Test fun changingEditorDiscardsTheOldBoundaryTransaction() {
        type("xi'", 0)
        focus("Second external editor")
        await("Second editor owns focus") { onMain { activity.second.hasFocus() } }
        type("nihao", 12)
        key(' ')
        await("New editor receives only its own candidate") { onMain { activity.second.text.toString() == "你好" } }
        assertFalse(onMain { activity.second.text.contains('\'') })
        evidence("second_editor=你好")
    }

    private fun evidence(value: String) = File(artifacts, "${name.methodName}.txt").appendText("$value\n")

    private fun select(value: String) {
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("候选词，")), 5_000))
        val selector = By.text(value).descStartsWith("候选词，")
        if (device.findObject(selector) == null) device.findObject(By.desc("展开候选"))?.click()
        repeat(30) {
            device.findObject(selector)?.let { node ->
                evidence("selected=${node.contentDescription}")
                node.click()
                // Prefix selection collapses the grid, restoring the letter geometry.
                SystemClock.sleep(200)
                return
            }
            val box = keyboardBounds()
            device.swipe(box.centerX(), box.bottom - dp(75f).toInt(), box.centerX(), box.top + dp(75f).toInt(), 25)
            SystemClock.sleep(150)
        }
        fail("Prefix $value absent from bounded candidate search")
    }
}
