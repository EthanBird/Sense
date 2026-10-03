package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real, naturally produced suggestions; no seeded user history or direct IME calls. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorAssociationTest : ExternalEditorTestFixture() {
    @Test fun associationDoesNotExpireUnderAnActiveTouch() {
        val candidate = showAssociations()
        val word = candidate.text
        val box = candidate.visibleBounds
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                box.exactCenterX(), box.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        try {
            SystemClock.sleep(5_000) // crosses the existing 4.5 s idle timeout while the finger owns a candidate
            device.takeScreenshot(File(artifacts, "${name.methodName}-held.png"))
            assertNotNull("Idle expiry must not remove the word being touched",
                device.findObject(By.text(word).descStartsWith("联想词，")))
        } finally {
            send(MotionEvent.ACTION_UP)
        }
        await("Release commits the same visible association exactly once") { text() == "今天$word" && !composing() }
        assertEquals(typingBounds, keyboardBounds())
    }

    @Test fun typingTheNextWordCancelsThePendingReveal() {
        type("jintian", 20)
        key(' ')
        await("Seed committed") { text() == "今天" && !composing() }
        key('w')
        await("Next composition started") { composing() }
        SystemClock.sleep(800)
        assertAssociationsAbsent()
        assertEquals(typingBounds, keyboardBounds())
        type("omen", 20)
        key(' ')
        await("Continuous typing preserves both words") { text() == "今天我们" && !composing() }
        assertNotNull(device.wait(Until.findObject(By.desc("关闭联想")), 5_000))
    }

    @Test fun dismissStaysClosedUntilANewCommitAndTheNewSuggestionIsSelectable() {
        showAssociations()
        val dismiss = requireNotNull(device.findObject(By.desc("关闭联想")))
        device.takeScreenshot(File(artifacts, "${name.methodName}-visible.png"))
        dismiss.click()
        SystemClock.sleep(800)
        assertAssociationsAbsent()
        assertEquals("今天", text())
        assertEquals(typingBounds, keyboardBounds())
        type("women", 20)
        key(' ')
        await("New committed word") { text() == "今天我们" && !composing() }
        val next = requireNotNull(device.wait(Until.findObject(By.descStartsWith("联想词，")), 5_000))
        val word = next.text
        next.click()
        await("Accessible association action commits to actual external editor") { text() == "今天我们$word" }
    }

    @Test fun idleTimeoutRestoresToolbarWithoutChangingExternalText() {
        showAssociations()
        assertTrue("An untouched association is short-lived",
            device.wait(Until.gone(By.desc("关闭联想")), 6_500))
        SystemClock.sleep(700)
        assertAssociationsAbsent()
        assertEquals("今天", text())
        assertEquals(typingBounds, keyboardBounds())
    }

    @Test fun draggingKeepsTheStripAliveAndReleaseRestartsItsIdleTimeout() {
        showAssociations()
        val box = device.findObjects(By.descStartsWith("联想词，")).last().visibleBounds
        val down = SystemClock.uptimeMillis()
        val x = box.exactCenterX()
        val y = box.exactCenterY()
        val endX = typingBounds.left + dp(12f)
        sendTouch(down, MotionEvent.ACTION_DOWN, x, y)
        try {
            sendTouch(down, MotionEvent.ACTION_MOVE, endX, y)
            SystemClock.sleep(5_000)
            assertNotNull("A horizontal touch gesture keeps the strip alive even after tap cancellation",
                device.findObject(By.desc("关闭联想")))
        } finally {
            sendTouch(down, MotionEvent.ACTION_UP, endX, y)
        }
        assertEquals("A scroll must not choose a word", "今天", text())
        SystemClock.sleep(600)
        assertNotNull("Release gets a fresh idle interval", device.findObject(By.desc("关闭联想")))
        assertTrue(device.wait(Until.gone(By.desc("关闭联想")), 6_500))
        assertEquals("今天", text())
    }

    @Test fun changingEditorWhileHoldingRejectsTheOldCandidateAndExpiry() {
        val box = showAssociations().visibleBounds
        val down = SystemClock.uptimeMillis()
        sendTouch(down, MotionEvent.ACTION_DOWN, box.exactCenterX(), box.exactCenterY())
        try {
            onMain { activity.second.requestFocus() }
            await("Host switched input fields") { onMain { activity.second.hasFocus() } }
            SystemClock.sleep(700)
            assertAssociationsAbsent()
        } finally {
            sendTouch(down, MotionEvent.ACTION_UP, box.exactCenterX(), box.exactCenterY())
        }
        SystemClock.sleep(600)
        assertEquals("今天", text())
        assertEquals("", onMain { activity.second.text.toString() })
        assertAssociationsAbsent()
    }

    @Test fun hidingAndReopeningDoesNotResurrectThePreviousAssociation() {
        showAssociations()
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.descStartsWith("先思键盘")), 5_000))
        focus("First external editor")
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("先思键盘")), 5_000))
        SystemClock.sleep(800)
        assertAssociationsAbsent()
        assertEquals("今天", text())
    }

    @Test fun switchingToPasswordRemovesAssociationsAndKeepsLiteralTyping() {
        showAssociations()
        focus("Private external editor")
        await("Password focused") { onMain { activity.password.hasFocus() } }
        type("nihao", 20)
        SystemClock.sleep(800)
        assertAssociationsAbsent()
        assertEquals("nihao", onMain { activity.password.text.toString() })
        assertEquals("今天", text())
    }

    @Test fun aKnownCompletedGreetingKeepsToolbarRatherThanOfferingNoisyCharacters() {
        type("nihao", 20)
        key(' ')
        await("Greeting committed") { text() == "你好" && !composing() }
        SystemClock.sleep(1_200)
        assertAssociationsAbsent()
        assertEquals(typingBounds, keyboardBounds())
        device.takeScreenshot(File(artifacts, "${name.methodName}-stop.png"))
        type("jintian", 20)
        key(' ')
        await("A new useful context") { text() == "你好今天" && !composing() }
        val suggestion = requireNotNull(device.wait(Until.findObject(By.descStartsWith("联想词，")), 5_000))
        assertEquals("早上", suggestion.text)
        suggestion.click()
        await("A complete multi-character suggestion inserts exactly once") { text() == "你好今天早上" && !composing() }
    }

    private fun showAssociations(): UiObject2 {
        type("jintian", 20)
        key(' ')
        await("Seed committed") { text() == "今天" && !composing() }
        val result = device.wait(Until.findObject(By.descStartsWith("联想词，")), 5_000)
        assertNotNull("Real suggestions should appear after a short idle, without history seeding", result)
        assertEquals(typingBounds, keyboardBounds())
        assertEquals("早上", requireNotNull(result).text)
        return result
    }

    private fun assertAssociationsAbsent() {
        assertNull(device.findObject(By.desc("关闭联想")))
        assertNull(device.findObject(By.descStartsWith("联想词，")))
    }

    private fun sendTouch(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
}
