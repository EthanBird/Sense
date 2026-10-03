package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.view.inputmethod.BaseInputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real system IME input into another UID, without calling Sense internals. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorInputTest : ExternalEditorTestFixture() {
    @Test fun touchscreenPinyinCandidateCommitsThroughExternalInputConnection() {
        type("nihao", 90)
        SystemClock.sleep(600) // candidate settles; fast confirmation has a separate test
        val bounds = keyboardBounds()
        tap(bounds.left + dp(30f), bounds.top + dp(31f))
        await("candidate commits 你好") { text() == "你好" && !composing() }
        assertEquals("", onMain { activity.second.text.toString() })
    }

    @Test fun candidateWordsAreExposedAsActionsToAccessibilityServices() {
        type("nihao", 20)
        val candidate = device.wait(Until.findObject(By.text("你好").descStartsWith("候选词，")), 5_000)
        assertNotNull("Canvas candidates need individual readable, clickable nodes", candidate)
        assertTrue(candidate.isClickable)
        candidate.click()
        await("Named candidate commits to the external editor") { text() == "你好" && !composing() }
    }

    @Test fun rapidTypingAndImmediateSpaceCommitTheLatestSentenceOnce() {
        type("woxihuanbeijing", 12)
        if (coldStart && captureLoadingUi) device.takeScreenshot(File(artifacts, "${name.methodName}-loading.png"))
        if (coldStart) assertRuntimeStillLoading("before_space")
        val start = SystemClock.uptimeMillis()
        key(' ')
        await("space commits latest sentence") { text() == "我喜欢北京" && !composing() }
        File(artifacts, "${name.methodName}.txt").appendText("space_to_committed_editor_ms=${SystemClock.uptimeMillis() - start}\n" +
            "scope=single emulator sample; includes injection and polling; not decoder p95\n")
        SystemClock.sleep(500)
        assertEquals("我喜欢北京", text())
        assertEquals("Composing should not change key-row geometry", typingBounds, keyboardBounds())
    }

    @Test fun enterCommitsRawEnglishInChineseModeWithoutANewline() {
        type("sensecustomword", 20)
        key('\n')
        await("Enter commits raw spelling") { text() == "sensecustomword" && !composing() }
        if (coldStart) await("Production publication after explicit raw Enter", 20_000) { runtimeState().contains("ready=true") }
        SystemClock.sleep(400)
        assertEquals("sensecustomword", text())
    }

    @Test fun confirmationReplaysFollowingInputInOrder() {
        type("woxihuanbeijing", 12)
        if (coldStart) assertRuntimeStillLoading("before_first_space")
        key(' ')
        type("nihao", 12)
        key(' ')
        await("Queued confirmation preserves both words in order") { text() == "我喜欢北京你好" && !composing() }
        SystemClock.sleep(400)
        assertEquals("我喜欢北京你好", text())
    }

    @Test fun backspaceEditsThePendingSpellingBeforeSpaceConfirms() {
        type("nihaoa", 20)
        key('\b')
        key(' ')
        await("backspace removed final a") { text() == "你好" && !composing() }
    }

    @Test fun switchingEditorsDoesNotCommitAnOldCandidateIntoTheNewField() {
        type("woxihuanbeijing", 12)
        if (coldStart) assertRuntimeStillLoading("before_space_and_editor_switch")
        key(' ')
        focus("Second external editor")
        await("Second editor focused") { onMain { activity.second.hasFocus() } }
        SystemClock.sleep(100)
        val firstAfterSwitch = text()
        type("nihao", 20)
        key(' ')
        await("Only new composition reaches second field") { onMain { activity.second.text.toString() == "你好" } }
        SystemClock.sleep(400)
        assertEquals(firstAfterSwitch, text())
    }

    @Test fun movingCursorAfterCommitInsertsAtTheExternalSelection() {
        type("nihao", 20)
        key(' ')
        await("Initial commit") { text() == "你好" && !composing() }
        onMain { activity.first.setSelection(0) }
        SystemClock.sleep(150)
        type("wo", 30)
        key(' ')
        await("Insert at moved selection") { text() == "我你好" && !composing() }
    }

    @Test fun hidingAndReopeningTheImeKeepsTheExternalCommittedText() {
        type("nihao", 20)
        key(' ')
        await("Initial commit") { text() == "你好" && !composing() }
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.descStartsWith("先思键盘")), 5_000))
        focus("First external editor")
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("先思键盘")), 5_000))
        typingBounds = keyboardBounds()
        type("shijie", 30)
        key(' ')
        await("Committed text survives reopening") { text() == "你好世界" && !composing() }
    }

    @Test fun passwordFieldUsesLiteralInputRatherThanChineseCandidates() {
        focus("Private external editor")
        await("Password focused") { onMain { activity.password.hasFocus() } }
        SystemClock.sleep(150)
        type("senseprivate", 25)
        await("Password keeps literal spelling") { onMain { activity.password.text.toString() == "senseprivate" } }
        assertEquals("", text())
        assertEquals(-1, onMain { BaseInputConnection.getComposingSpanStart(activity.password.text) })
        device.takeScreenshot(File(artifacts, "${name.methodName}-literal.png"))
        key('\b')
        await("Password backspace stays literal") { onMain { activity.password.text.toString() == "senseprivat" } }
        focus("First external editor")
        await("Ordinary editor focused again") { onMain { activity.first.hasFocus() } }
        SystemClock.sleep(100)
        type("nihao", 25)
        key(' ')
        await("Previous Chinese choice is restored") { text() == "你好" && !composing() }
    }

}
