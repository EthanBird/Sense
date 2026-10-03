package io.github.ethanbird.senseime.inputqualityfixture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalEditorBoundedCompletionTest : ExternalEditorTestFixture() {
    @Test fun missingCanonicalWordCanBeTouchedBeforeItsLastSyllableIsFinished() {
        requireNoLearning()
        type("beiji", 20)
        selectBeijingAfterLiteralNorthPole()
        onMain { activity.first.text.clear() }
        type("beiji", 20)
        assertLiteralFirst()
        key(' ')
        await("No-learning selection must retain the literal default") { text() == "北极" && !composing() }
        evidence("after_no_learning_reuse=北极")
    }

    @Test fun backspacingTheFullSpellingStillExposesTheCanonicalCompletion() {
        requireNoLearning()
        type("beijing", 20)
        assertNotNull(device.wait(Until.findObject(By.text("北京").descStartsWith("候选词，")), 5_000))
        key('\b'); key('\b')
        selectBeijingAfterLiteralNorthPole()
        evidence("path=beijing + backspace + backspace + explicit 北京")
    }

    @Test fun completeLiteralSpellingStillCommitsItsOwnFirstCandidate() {
        requireNoLearning(); type("beiji", 20); key(' ')
        await("Exact 北极 stays the default") { text() == "北极" && !composing() }
        evidence("space_commit=北极")
    }

    @Test fun enterStillCommitsTheActualLatinSpelling() {
        requireNoLearning(); type("beiji", 20); key('\n')
        await("Enter remains literal") { text() == "beiji" && !composing() }
        evidence("enter_commit=beiji")
    }

    private fun requireNoLearning() = assertEquals("true", InstrumentationRegistry.getArguments().getString("noLearning"))
    private fun assertLiteralFirst() {
        val first = device.wait(Until.findObject(By.descStartsWith("候选词，1，北极")), 5_000)
        assertNotNull("Wait for the current beiji candidate batch", first)
        assertEquals("北极", first!!.text)
    }
    private fun selectBeijingAfterLiteralNorthPole() {
        assertLiteralFirst()
        val target = device.wait(Until.findObject(By.text("北京").descStartsWith("候选词，")), 5_000)
        assertNotNull("Dictionary completion 北京 must be visible without expanding the whole candidate page", target)
        val description = target!!.contentDescription
        assertEquals(2, description.split('，')[1].toInt())
        evidence("selected=$description")
        val bounds = target.visibleBounds
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        await("Touch commits the selected canonical word") { text() == "北京" && !composing() }
        evidence("committed=北京")
    }
    private fun evidence(value: String) = File(artifacts, "${name.methodName}.txt").appendText(value + "\n")
}
