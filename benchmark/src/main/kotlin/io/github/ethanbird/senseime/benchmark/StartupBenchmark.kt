package io.github.ethanbird.senseime.benchmark

import android.content.Context
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext by lazy {
        instrumentation.context.createPackageContext(
            TARGET_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
    }

    @Test
    fun coldSettingsStartup() = startup(StartupMode.COLD)

    @Test
    fun warmSettingsStartup() = startup(StartupMode.WARM)

    @Test
    fun homeToToolsFrameTiming() = destinationCuj(
        Destination(
            titleResource = "settings_tools_title",
            readyResource = "agent_tools_master",
            readyKind = ReadyKind.ENABLED_TEXT,
        ),
    )

    @Test
    fun homeToProviderFrameTiming() = destinationCuj(
        Destination(titleResource = "settings_provider_title"),
    )

    @Test
    fun homeToSkillsFrameTiming() = destinationCuj(
        Destination(
            titleResource = "settings_skills_title",
            readyResource = "skills_content",
            readyKind = ReadyKind.ENABLED_DESCRIPTION,
        ),
    )

    private fun startup(mode: StartupMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = mode,
            iterations = 5,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }

    /**
     * Release-like benchmark-build CUJs intentionally record frame timing
     * distributions without embedding a device-specific millisecond threshold.
     */
    private fun destinationCuj(destination: Destination) {
        val title = targetString(destination.titleResource)
        val homeMarker = targetString(HOME_MARKER_RESOURCE)
        val ready = destination.readyResource?.let(::targetString)

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                ensureSettingsHome(homeMarker)
            },
        ) {
            clickHomeDestination(title)
            waitForDestination(title, ready, destination.readyKind)
        }
    }

    private fun ensureSettingsHome(homeMarker: String) {
        if (!uiDevice.hasObject(By.text(homeMarker))) {
            uiDevice.pressBack()
        }
        check(
            uiDevice.wait(
                Until.hasObject(By.text(homeMarker)),
                UI_TIMEOUT_MILLIS,
            ),
        ) {
            "Settings home marker '$homeMarker' was not visible"
        }
        uiDevice.waitForIdle()
    }

    private fun clickHomeDestination(title: String) {
        UiScrollable(UiSelector().scrollable(true)).apply {
            setAsVerticalList()
            scrollIntoView(UiSelector().descriptionStartsWith(title))
        }
        val category = checkNotNull(
            uiDevice.wait(
                Until.findObject(By.descStartsWith(title)),
                UI_TIMEOUT_MILLIS,
            ),
        ) {
            "Settings category '$title' was not visible"
        }
        category.click()
    }

    private fun waitForDestination(
        title: String,
        ready: String?,
        readyKind: ReadyKind,
    ) {
        check(
            uiDevice.wait(
                Until.hasObject(By.text(title)),
                UI_TIMEOUT_MILLIS,
            ),
        ) {
            "Settings destination '$title' did not render"
        }
        val readySelector = when (readyKind) {
            ReadyKind.TITLE -> null
            ReadyKind.ENABLED_TEXT -> By.text(checkNotNull(ready)).enabled(true)
            ReadyKind.ENABLED_DESCRIPTION -> By.desc(checkNotNull(ready)).enabled(true)
        }
        if (readySelector != null) {
            check(uiDevice.wait(Until.hasObject(readySelector), UI_TIMEOUT_MILLIS)) {
                "Settings destination '$title' did not reach its first ready state"
            }
        }
        uiDevice.waitForIdle()
    }

    private fun targetString(resourceName: String): String {
        val resourceId =
            targetContext.resources.getIdentifier(resourceName, "string", TARGET_PACKAGE)
        check(resourceId != 0) {
            "Target string resource '$resourceName' was not found"
        }
        return targetContext.getString(resourceId)
    }

    private data class Destination(
        val titleResource: String,
        val readyResource: String? = null,
        val readyKind: ReadyKind = ReadyKind.TITLE,
    )

    private enum class ReadyKind {
        TITLE,
        ENABLED_TEXT,
        ENABLED_DESCRIPTION,
    }

    private companion object {
        const val TARGET_PACKAGE = "io.github.ethanbird.senseime"
        const val HOME_MARKER_RESOURCE = "settings_categories_title"
        const val UI_TIMEOUT_MILLIS = 8_000L
    }
}
