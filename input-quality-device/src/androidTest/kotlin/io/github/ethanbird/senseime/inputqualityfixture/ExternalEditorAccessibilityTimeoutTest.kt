package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalEditorAccessibilityTimeoutTest : ExternalEditorTestFixture() {
    @Test fun associationHonorsTheUsersSystemInteractionTimeout() {
        val setting = "accessibility_interactive_ui_timeout_ms"
        val original = shell("settings get secure $setting").trim()
        try {
            shell("settings put secure $setting 10000")
            val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
            await("Platform acknowledged the 10 second interaction preference") {
                manager.getRecommendedTimeoutMillis(4_500, AccessibilityManager.FLAG_CONTENT_TEXT or
                    AccessibilityManager.FLAG_CONTENT_CONTROLS) >= 10_000
            }
            type("jintian", 20); key(' ')
            await("Committed a context with real word suggestions") { text() == "今天" && !composing() }
            val suggestion = requireNotNull(device.wait(Until.findObject(By.descStartsWith("联想词，")), 5_000))
            val word = suggestion.text
            SystemClock.sleep(5_500)
            device.takeScreenshot(File(artifacts, "${name.methodName}-after-default-timeout.png"))
            val retained = device.findObject(By.descStartsWith("联想词，").text(word))
            assertNotNull("User requested 10 seconds; suggestions must survive the old 4.5 second timeout", retained)
            requireNotNull(retained).click()
            await("Accessibility click still selects the same complete word") { text() == "今天$word" && !composing() }
        } finally {
            if (original == "null" || original.isEmpty()) shell("settings delete secure $setting")
            else shell("settings put secure $setting $original")
        }
    }
}
