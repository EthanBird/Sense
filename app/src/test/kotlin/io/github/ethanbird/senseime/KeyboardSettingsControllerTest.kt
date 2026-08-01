package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSettingsControllerTest {
    @Test
    fun savedSchemeIsDeliveredOnlyAfterRepositoryCommit() {
        val repository = RecordingRepository()
        val tasks = ImmediateSettingsTasks()
        val controller = KeyboardSettingsController(repository, tasks)
        val expected = ImePreferencesV1(chineseInputScheme = ChineseInputScheme.PINYIN_T9)
        var delivered: Result<ImePreferencesV1>? = null

        assertTrue(controller.save(ImePreferencesV1.DEFAULT, expected) { delivered = it })

        assertEquals(expected, repository.value)
        assertEquals(expected, delivered?.getOrThrow())
    }

    @Test
    fun untouchedSchemeKeepsANewerCrossProcessValue() {
        val repository = RecordingRepository().apply {
            value = ImePreferencesV1(chineseInputScheme = ChineseInputScheme.PINYIN_T9)
        }
        val controller = KeyboardSettingsController(repository, ImmediateSettingsTasks())
        val baseline = ImePreferencesV1.DEFAULT
        val selected = baseline.copy(
            wubiAutoCommitMode = WubiAutoCommitMode.OFF,
        )
        var delivered: Result<ImePreferencesV1>? = null

        assertTrue(controller.save(baseline, selected) { delivered = it })

        assertEquals(ChineseInputScheme.PINYIN_T9, repository.value.chineseInputScheme)
        assertEquals(selected.wubiAutoCommitMode, repository.value.wubiAutoCommitMode)
        assertEquals(repository.value, delivered?.getOrThrow())
    }

    private class RecordingRepository : KeyboardSettingsRepository {
        var value = ImePreferencesV1.DEFAULT
        override fun load(): Result<ImePreferencesV1> = Result.success(value)
        override fun update(mutation: KeyboardSettingsMutation): Result<ImePreferencesV1> =
            Result.success(mutation.applyTo(value)).also { result -> value = result.getOrThrow() }
    }

    private class ImmediateSettingsTasks : SettingsTaskRunner {
        override fun <T> refresh(
            channel: String,
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean = true.also { deliver(runCatching(operation)) }

        override fun <T> execute(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean = true.also { deliver(runCatching(operation)) }

        override fun close() = Unit
    }
}
