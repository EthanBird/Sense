package io.github.ethanbird.senseime.inputqualityfixture

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.concurrent.FutureTask
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName

/** Drives real touchscreen events through the system IME into another UID's InputConnection. */
abstract class ExternalEditorTestFixture {
    @get:Rule val name = TestName()
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device = UiDevice.getInstance(instrumentation)
    protected lateinit var activity: ExternalEditorActivity
    protected lateinit var typingBounds: Rect
    protected var previousIme = ""
    protected var previousHardKeyboard = ""
    protected val coldStart = InstrumentationRegistry.getArguments().getString("coldStart") == "true"
    protected val captureLoadingUi = InstrumentationRegistry.getArguments().getString("captureLoadingUi") == "true"
    protected val density get() = instrumentation.targetContext.resources.displayMetrics.density
    protected val evidenceRun = InstrumentationRegistry.getArguments().getString("evidenceRun", "manual").also {
        require(it.matches(Regex("[a-zA-Z0-9_-]+")))
    }
    protected val artifacts get() = File(instrumentation.targetContext.getExternalFilesDir(null), "input-quality/$evidenceRun").apply { mkdirs() }

    @Before fun startExternalEditor() {
        // Each run overwrites its own evidence, so a failed rerun cannot inherit old timings.
        File(artifacts, "${name.methodName}.txt").writeText("state=started\n")
        File(artifacts, "${name.methodName}-cadence.txt").writeText("")
        previousIme = shell("settings get secure default_input_method").trim()
        previousHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        assertTrue("Install the Sense debug APK first", shell("pm path $SENSE").contains("package:"))
        if (coldStart) {
            shell("am force-stop $SENSE")
            // Package-stop broadcasts can reset the selected IME after force-stop returns.
            // Drain them before selecting Sense, without starting or warming its runtime.
            val barrier = shell("am wait-for-broadcast-idle")
            File(artifacts, "${name.methodName}.txt").appendText("cold_stop_barrier=$barrier\n")
            assertTrue("Cold fixture requires a completed broadcast barrier: $barrier",
                barrier.contains("All broadcast queues are idle"))
            assertTrue("Cold fixture must start from a stopped IME process",
                shell("pidof $SENSE:ime || true").trim().isEmpty())
        }
        shell("ime enable $IME")
        shell("ime set $IME")
        assertEquals("Select Sense before launching the editor", IME,
            shell("settings get secure default_input_method").trim())
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ExternalEditorActivity::class.java)
            .putExtra("noLearning", InstrumentationRegistry.getArguments().getString("noLearning") == "true")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ExternalEditorActivity
        InstrumentationRegistry.getArguments().getString("expectedOrientation")?.toInt()?.let { expected ->
            // Apply to the foreground editor, not the launcher's fixed-portrait window.
            if (expected == 2) device.setOrientationLeft() else device.setOrientationNatural()
            // Rotation can finish after startActivitySync and recreate the host.
            // Use its resumed instance, not a destroyed portrait Activity.
            await("External Activity resumed in the requested display configuration") {
                onMain {
                    val current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ExternalEditorActivity>()
                        .firstOrNull { it.resources.configuration.orientation == expected }
                    if (current != null) activity = current
                    current != null && (device.displayWidth > device.displayHeight) == (expected == 2)
                }
            }
            File(artifacts, "${name.methodName}.txt").appendText(
                "requestedOrientation=$expected; actualOrientation=${activity.resources.configuration.orientation}; displayRotation=${device.displayRotation}\n")
        }
        assertNotNull("System did not show Sense keyboard", device.wait(Until.findObject(By.descStartsWith("先思键盘")), 10_000))
        // Current service state, not historical log lines from an older instance.
        if (coldStart) {
            assertRuntimeStillLoading("before_typing")
        } else {
            await("Production runtime ready", 20_000) {
                shell("dumpsys input_method").lineSequence().any {
                    it.contains("Sense candidate runtime: ready=true") && it.contains("characterModel=READY")
                }
            }
            SystemClock.sleep(600)
        }
        typingBounds = keyboardBounds()
    }

    @After fun finish() {
        try {
            device.takeScreenshot(File(artifacts, "${name.methodName}.png"))
            device.dumpWindowHierarchy(File(artifacts, "${name.methodName}.xml"))
        } finally {
            if (::activity.isInitialized) onMain { activity.finish() }
            if (InstrumentationRegistry.getArguments().containsKey("expectedOrientation")) device.unfreezeRotation()
            if (previousIme.isNotBlank() && previousIme != "null" && previousIme != IME) shell("ime set $previousIme")
            if (previousHardKeyboard in setOf("0", "1")) shell("settings put secure show_ime_with_hard_keyboard $previousHardKeyboard")
            else shell("settings delete secure show_ime_with_hard_keyboard")
        }
    }

    protected fun type(value: String, interval: Long) {
        val starts = mutableListOf<Long>()
        value.forEach { starts += SystemClock.uptimeMillis(); key(it); SystemClock.sleep(interval) }
        File(artifacts, "${name.methodName}-cadence.txt").appendText(
            "input=" + value + "\nactual_key_start_intervals_ms=" + starts.zipWithNext { a, b -> b - a }.joinToString(",") + "\n")
    }
    protected fun focus(description: String) {
        val field = onMain {
            val view = when (description) {
                "First external editor" -> activity.first
                "Second external editor" -> activity.second
                "Private external editor" -> activity.password
                else -> error("Unknown fixture editor")
            }
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }
        tap(field.centerX().toFloat(), field.centerY().toFloat())
    }
    protected fun text() = onMain { activity.first.text.toString() }
    protected fun assertRuntimeStillLoading(phase: String) {
        val state = runtimeState()
        File(artifacts, "${name.methodName}.txt").appendText("$phase=$state\n")
        assertTrue("Cold-loading coverage requires an observed unfinished runtime ($phase): $state", state.contains("ready=false"))
    }
    protected fun runtimeState() = shell("dumpsys input_method").lineSequence()
        .firstOrNull { it.contains("Sense candidate runtime:") }.orEmpty()
    protected fun composing() = onMain { BaseInputConnection.getComposingSpanStart(activity.first.text) >= 0 }
    protected fun keyboardBounds(): Rect = requireNotNull(device.findObject(By.descStartsWith("先思键盘"))).visibleBounds
    protected fun dp(value: Float) = value * density

    // Known 26-key layout geometry, measured against the actual IME window (not screen constants).
    // This fixture deliberately avoids adding a privileged test bridge to the shipping IME.
    protected fun key(char: Char, synchronous: Boolean = true, waitForAnimations: Boolean = true) {
        val (x, y) = keyLocation(char)
        tap(x, y, synchronous, waitForAnimations)
    }

    protected fun keyLocation(char: Char): Pair<Float, Float> {
        val box = typingBounds
        val fontScale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val top = dp(45f * fontScale + 7f)
        val bottom = box.height() - dp(52f + 7f)
        val gap = dp(5f)
        val rowHeight = (bottom - top - gap * 3) / 4
        fun rowPoint(row: Int, weights: List<Float>, index: Int): Pair<Float, Float> {
            val unit = (box.width() - dp(12f) - gap * (weights.size - 1)) / weights.sum()
            return (dp(6f) + unit * (weights.take(index).sum() + weights[index] / 2) + gap * index) to
                (top + row * (rowHeight + gap) + rowHeight / 2)
        }
        val point = when {
            char in "qwertyuiop" -> rowPoint(0, List(10) { 1f }, "qwertyuiop".indexOf(char))
            char in "asdfghjkl" -> {
                val index = "asdfghjkl".indexOf(char)
                val width = (box.width() - dp(48f) - gap * 8) / 9
                (dp(24f) + index * (width + gap) + width / 2) to (top + rowHeight * 1.5f + gap)
            }
            char in "zxcvbnm\b" -> rowPoint(2, listOf(1.25f) + List(7) { 1f } + 1.25f, "zxcvbnm\b".indexOf(char) + 1)
            // Full-pinyin composition borrows the physical Shift slot for 分词.
            char == '\'' -> rowPoint(2, listOf(1.25f) + List(7) { 1f } + 1.25f, 0)
            char == ' ' || char == '\n' -> rowPoint(3, listOf(.9f, 1.05f, .8f, 2.7f, .8f, 1f, 1.2f), if (char == ' ') 3 else 6)
            else -> error("Unsupported fixture key $char")
        }
        return (box.left + point.first) to (box.top + point.second)
    }

    // Diagnostic test API only. The public two-argument overload always waits for
    // surface transactions, even with sync=false. Fail explicitly if this platform
    // does not expose the overload; never change device-wide hidden API policies.
    private val noAnimationInjection by lazy {
        instrumentation.uiAutomation.javaClass.getMethod("injectInputEvent",
            InputEvent::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }

    protected fun tap(x: Float, y: Float, synchronous: Boolean = true, waitForAnimations: Boolean = true) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                val injected = if (waitForAnimations) instrumentation.uiAutomation.injectInputEvent(event, synchronous)
                else noAnimationInjection.invoke(instrumentation.uiAutomation, event, synchronous, false) as Boolean
                assertTrue(injected)
            } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(8)
        }
    }
    protected fun shell(command: String) = device.executeShellCommand(command)
    protected fun await(message: String, timeout: Long = 8_000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        fail("$message; actual=${if (::activity.isInitialized) text() else "no activity"}")
    }
    protected fun <T> onMain(block: () -> T): T = FutureTask(block).also { instrumentation.runOnMainSync(it) }.get()

    companion object {
        const val SENSE = "io.github.ethanbird.senseime.debug"
        const val IME = "$SENSE/io.github.ethanbird.senseime.service.SenseInputMethodService"
    }
}
