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

/** Configuration is set/restored by the dedicated-emulator runner before Activity creation. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorConfigurationTest : ExternalEditorTestFixture() {
    @Test fun composingCandidateAndFollowingInputKeepTheKeyboardGeometry() {
        assertConfiguration()
        type("nihao", 20)
        val first = requireNotNull(device.wait(Until.findObject(By.text("你好").descStartsWith("候选词，")), 5_000))
        assertEquals(typingBounds, keyboardBounds())
        device.takeScreenshot(File(artifacts, "${name.methodName}-composing.png"))
        first.click()
        await("Real candidate commits across display configuration") { text() == "你好" && !composing() }
        type("shijie", 20); key(' ')
        await("Following word remains correct") { text() == "你好世界" && !composing() }
        assertEquals(typingBounds, keyboardBounds())
    }

    @Test fun expandedCandidateScrollSelectsItsActualVisibleWord() {
        assertConfiguration()
        type("shi", 20)
        requireNotNull(device.wait(Until.findObject(By.desc("展开候选")), 5_000)).click()
        assertNotNull(device.wait(Until.findObject(By.desc("收起候选")), 3_000))
        val first = device.findObjects(By.descStartsWith("候选词，")).map { it.contentDescription }
        val box = keyboardBounds()
        val x = box.centerX()
        device.swipe(x, box.bottom - dp(105f).toInt(), x, box.top + dp(60f).toInt(), 25)
        SystemClock.sleep(600)
        val visible = device.findObjects(By.descStartsWith("候选词，"))
        assertTrue("Continuous scrolling should expose a new source index", visible.any { it.contentDescription !in first })
        device.takeScreenshot(File(artifacts, "${name.methodName}-expanded.png"))
        val selected = visible.first { it.visibleBounds.height() >= dp(16f) }
        val word = selected.text
        selected.click()
        await("Scrolled word commits to the external editor exactly once") { text() == word && !composing() }
        assertEquals(typingBounds, keyboardBounds())
    }

    @Test fun associationCloseRestoresToolbarWithoutMovingLetterRows() {
        assertConfiguration()
        type("jintian", 20); key(' ')
        await("Context committed") { text() == "今天" && !composing() }
        assertNotNull(device.wait(Until.findObject(By.text("早上").descStartsWith("联想词，")), 5_000))
        assertEquals(typingBounds, keyboardBounds())
        device.takeScreenshot(File(artifacts, "${name.methodName}-association.png"))
        requireNotNull(device.findObject(By.desc("关闭联想"))).click()
        SystemClock.sleep(600)
        assertNull(device.findObject(By.descStartsWith("联想词，")))
        assertEquals("今天", text())
        assertEquals(typingBounds, keyboardBounds())
    }

    private fun assertConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val config = activity.resources.configuration
        assertEquals(args.getString("expectedFontScale", "1.0").toFloat(), config.fontScale, .01f)
        assertEquals(args.getString("expectedOrientation", "1").toInt(), config.orientation)
        args.getString("expectedDensityDpi")?.toInt()?.let { assertEquals(it, config.densityDpi) }
        assertTrue(typingBounds.height() > 0 && typingBounds.width() > 0)
        assertTrue(typingBounds.top >= 0 && typingBounds.bottom <= device.displayHeight)
        File(artifacts, "${name.methodName}.txt").appendText(
            "fontScale=${config.fontScale}\norientation=${config.orientation}\ndensityDpi=${config.densityDpi}\nsdk=${android.os.Build.VERSION.SDK_INT}\nkeyboard=$typingBounds\ndisplay=${device.displayWidth}x${device.displayHeight}\n")
    }
}
