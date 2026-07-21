package io.github.ethanbird.senseime.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldSettingsStartup() = startup(StartupMode.COLD)

    @Test
    fun warmSettingsStartup() = startup(StartupMode.WARM)

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

    private companion object {
        const val TARGET_PACKAGE = "io.github.ethanbird.senseime"
    }
}
