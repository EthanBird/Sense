package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.mic.SenseMicCaptureProfile
import io.github.ethanbird.senseime.mic.SenseMicQuality
import io.github.ethanbird.senseime.mic.SenseMicSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicSettingsControllerTest {
    @Test
    fun loadAndSavePublishDeterministicStatesAndRunActionAfterPersistence() {
        val initial = settings(enabled = false)
        val repository = RecordingRepository(initial)
        val states = mutableListOf<MicSettingsUiState>()
        val actions = mutableListOf<MicSettingsRuntimeAction>()
        val controller = MicSettingsController(
            repository,
            ImmediateTasks(),
            states::add,
            actions::add,
        )

        controller.load()
        val enabled = initial.copy(enabled = true, quality = SenseMicQuality.HIGH)
        controller.save(enabled, MicSettingsRuntimeAction.START)

        assertEquals(
            listOf(
                MicSettingsUiPhase.LOADING,
                MicSettingsUiPhase.READY,
                MicSettingsUiPhase.SAVING,
                MicSettingsUiPhase.READY,
            ),
            states.map(MicSettingsUiState::phase),
        )
        assertEquals(enabled, repository.value)
        assertEquals(listOf(MicSettingsRuntimeAction.START), actions)
    }

    @Test
    fun failedPersistenceLeavesRequestedSettingsVisibleAndSkipsRuntimeAction() {
        val requested = settings(enabled = true)
        val states = mutableListOf<MicSettingsUiState>()
        val actions = mutableListOf<MicSettingsRuntimeAction>()
        val repository = object : MicSettingsRepository {
            override fun load(): Result<SenseMicSettings> = Result.success(settings(false))
            override fun save(settings: SenseMicSettings): Result<SenseMicSettings> =
                Result.failure(IllegalStateException("disk full"))
        }
        val controller = MicSettingsController(
            repository,
            ImmediateTasks(),
            states::add,
            actions::add,
        )

        controller.save(requested, MicSettingsRuntimeAction.START)

        assertEquals(MicSettingsUiPhase.ERROR, states.last().phase)
        assertEquals(requested, states.last().settings)
        assertEquals("disk full", states.last().message)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun lateLoadResultDoesNotOverwriteACompletedSave() {
        val repository = RecordingRepository(settings(false))
        val tasks = DeferredTasks()
        val states = mutableListOf<MicSettingsUiState>()
        val controller = MicSettingsController(repository, tasks, states::add) {}

        controller.load()
        controller.save(settings(true), MicSettingsRuntimeAction.START)
        tasks.completeExecute()
        tasks.completeRefresh()

        assertEquals(MicSettingsUiPhase.READY, states.last().phase)
        assertTrue(states.last().settings.enabled)
    }

    private fun settings(enabled: Boolean) = SenseMicSettings(
        enabled = enabled,
        pairCode = "123456",
        quality = SenseMicQuality.BALANCED,
        captureProfile = SenseMicCaptureProfile.VOICE_COMMUNICATION,
    )

    private class RecordingRepository(initial: SenseMicSettings) : MicSettingsRepository {
        var value = initial
        override fun load(): Result<SenseMicSettings> = Result.success(value)
        override fun save(settings: SenseMicSettings): Result<SenseMicSettings> =
            Result.success(settings).also { value = settings }
    }

    private class ImmediateTasks : SettingsTaskRunner {
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

    private class DeferredTasks : SettingsTaskRunner {
        private var refreshOperation: (() -> Any?)? = null
        private var refreshDelivery: ((Result<Any?>) -> Unit)? = null
        private var executeOperation: (() -> Any?)? = null
        private var executeDelivery: ((Result<Any?>) -> Unit)? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T> refresh(
            channel: String,
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean {
            refreshOperation = operation as () -> Any?
            refreshDelivery = deliver as (Result<Any?>) -> Unit
            return true
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> execute(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean {
            executeOperation = operation as () -> Any?
            executeDelivery = deliver as (Result<Any?>) -> Unit
            return true
        }

        fun completeRefresh() {
            refreshDelivery?.invoke(runCatching { requireNotNull(refreshOperation).invoke() })
        }

        fun completeExecute() {
            executeDelivery?.invoke(runCatching { requireNotNull(executeOperation).invoke() })
        }

        override fun close() = Unit
    }
}
