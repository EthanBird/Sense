package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
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

        assertTrue(controller.save(expected) { delivered = it })

        assertEquals(expected, repository.value)
        assertEquals(expected, delivered?.getOrThrow())
    }

    private class RecordingRepository : KeyboardSettingsRepository {
        var value = ImePreferencesV1.DEFAULT
        override fun load(): Result<ImePreferencesV1> = Result.success(value)
        override fun save(value: ImePreferencesV1): Result<Unit> =
            Result.success(Unit).also { this.value = value }
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
