package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real pre-existing editor text, not context injected directly into a decoder. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorContextTest : ExternalEditorTestFixture() {
    private fun seed(value: String, start: Int = value.length, end: Int = start) {
        onMain { activity.first.setText(value); activity.first.setSelection(start, end) }
        SystemClock.sleep(200) // Allow the host's selection/composing callback to reach the IME.
    }

    private fun confirm(query: String, expected: String) {
        type(query, 12)
        key(' ')
        await("Contextual commit: $expected") { text() == expected && !composing() }
        File(artifacts, "${name.methodName}.txt").appendText("committed=$expected\n")
        instrumentation.waitForIdleSync()
        device.waitForIdle(1_000)
        SystemClock.sleep(100)
    }

    @Test fun precedingEditorTextSelectsTheContextualHomophoneWithoutLearning() {
        seed("我很")
        confirm("lei", "我很累")
    }

    @Test fun aContrastingContextPreservesTheOtherHomophone() {
        seed("属于")
        confirm("lei", "属于类")
    }

    @Test fun insertionUsesTheLeftContextAtTheMovedCursorNotTheTextSuffix() {
        seed("我很属于", 2)
        confirm("lei", "我很累属于")
    }

    @Test fun replacementUsesTheLeftEdgeOfTheSelectedRange() {
        seed("我很属于", 2, 4)
        confirm("lei", "我很累")
    }

    @Test fun punctuationResetsTheLanguageModelBoundary() {
        seed("我很。")
        confirm("lei", "我很。类")
    }

    @Test fun changingEditorsDoesNotReuseTheFirstEditorsContext() {
        seed("我很")
        type("lei", 12)
        onMain { activity.second.setText("属于"); activity.second.setSelection(2) }
        focus("Second external editor")
        await("Second editor focused") { onMain { activity.second.hasFocus() } }
        SystemClock.sleep(200)
        type("lei", 12)
        key(' ')
        await("Second editor has its own homophone") { onMain { activity.second.text.toString() == "属于类" } }
        File(artifacts, "${name.methodName}.txt").appendText("second=属于类\n")
        seed("我很")
        focus("First external editor")
        await("First editor focused") { onMain { activity.first.hasFocus() } }
        onMain { activity.first.setSelection(2) }
        SystemClock.sleep(200)
        confirm("lei", "我很累")
    }

    @Test fun deletingTheWholeCompositionRecapturesChangedEditorText() {
        seed("我很")
        type("lei", 12)
        repeat(3) { key('\b') }
        await("Composition removed") { text() == "我很" && !composing() }
        seed("属于")
        confirm("lei", "属于类")
    }

    @Test fun theNextCompositionSeesTheJustCommittedWord() {
        confirm("wohen", "我很")
        confirm("lei", "我很累")
    }
}
