package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.runtime.AgentToolSettings
import io.github.ethanbird.senseime.speech.SpeechProviderPresetCatalog
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsControllersTest {
    @Test
    fun toolsControllerRunsRepositoryThroughLaneAndRollsBackFailedSave() {
        val loaded = AgentToolSettings(masterEnabled = true, webSearchEnabled = false)
        val edited = loaded.copy(masterEnabled = false, calculatorEnabled = false)
        val repository = FakeAgentToolsRepository(loaded)
        val tasks = DeferredTaskRunner()
        val states = mutableListOf<AgentToolsUiState>()
        val controller = AgentToolsSettingsController(repository, tasks, states::add)

        controller.load()

        assertEquals(listOf(AgentToolsUiPhase.LOADING), states.map { it.phase })
        assertEquals(0, repository.loadCalls)
        tasks.runNext()
        assertEquals(1, repository.loadCalls)
        assertEquals(AgentToolsUiPhase.READY, states.last().phase)
        assertEquals(loaded, states.last().settings)

        repository.saveFailure = IllegalStateException("disk full")
        controller.save(edited)

        assertEquals(AgentToolsUiPhase.SAVING, states.last().phase)
        assertEquals(0, repository.saveCalls)
        tasks.runNext()
        assertEquals(1, repository.saveCalls)
        assertEquals(AgentToolsUiPhase.SAVE_FAILED, states.last().phase)
        assertEquals(loaded, states.last().settings)
    }

    @Test
    fun providerControllerKeepsLoadSaveAndValidateCallbacksIndependent() {
        val repository = FakeProviderRepository()
        val tasks = DeferredTaskRunner()
        val loads = mutableListOf<Result<ProviderSettingsSnapshot>>()
        val saves = mutableListOf<Result<ProviderSettingsSnapshot>>()
        val validations = mutableListOf<Result<Boolean>>()
        val controller = ProviderSettingsController(repository, tasks)
        val profile = providerProfile("saved")

        assertTrue(controller.load(loads::add))
        assertTrue(controller.save(profile, "token".toCharArray(), saves::add))
        assertTrue(controller.validate(validations::add))
        tasks.runAll()

        assertEquals(1, repository.loadCalls)
        assertEquals(1, repository.saveCalls)
        assertEquals(1, repository.validateCalls)
        assertEquals(1, loads.size)
        assertEquals(listOf(profile), saves.map { it.getOrThrow().profile })
        assertEquals(listOf(true), validations.map { it.getOrThrow() })
    }

    @Test
    fun providerControllerKeepsWritesFifoAndAppliesLatestWinsPerOperation() {
        val repository = FakeProviderRepository()
        val tasks = DeferredTaskRunner()
        val firstSave = mutableListOf<Result<ProviderSettingsSnapshot>>()
        val clears = mutableListOf<Result<ProviderSettingsSnapshot>>()
        val latestSave = mutableListOf<Result<ProviderSettingsSnapshot>>()
        val controller = ProviderSettingsController(repository, tasks)

        controller.save(providerProfile("first"), null, firstSave::add)
        controller.clearCredential(clears::add)
        controller.save(providerProfile("latest"), null, latestSave::add)
        tasks.runAll()

        assertEquals(listOf("save:first", "clear", "save:latest"), repository.writeOperations)
        assertTrue(firstSave.isEmpty())
        assertEquals(1, clears.size)
        assertEquals(listOf("latest"), latestSave.map { it.getOrThrow().profile?.model })
    }

    @Test
    fun speechControllerKeepsLoadSaveAndClearCallbacksIndependent() {
        val repository = FakeSpeechRepository()
        val tasks = DeferredTaskRunner()
        val loads = mutableListOf<Result<SpeechSettingsSnapshot>>()
        val saves = mutableListOf<Result<SpeechSettingsSnapshot>>()
        val clears = mutableListOf<Result<SpeechSettingsSnapshot>>()
        val controller = SpeechSettingsController(repository, tasks)
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.SYSTEM)
            .defaultProfile()

        assertTrue(controller.load(loads::add))
        assertTrue(controller.save(profile, null, saves::add))
        assertTrue(controller.clearCredential(clears::add))
        tasks.runAll()

        assertEquals(1, repository.loadCalls)
        assertEquals(1, repository.saveCalls)
        assertEquals(1, repository.clearCalls)
        assertEquals(1, loads.size)
        assertEquals(listOf(profile), saves.map { it.getOrThrow().profile })
        assertEquals(1, clears.size)
    }

    @Test
    fun speechControllerRevokesAcceptedCallbackAfterClose() {
        val repository = FakeSpeechRepository()
        val tasks = DeferredTaskRunner()
        val delivered = mutableListOf<Result<SpeechSettingsSnapshot>>()
        val controller = SpeechSettingsController(repository, tasks)

        assertTrue(controller.load(delivered::add))
        controller.close()
        tasks.runAll()

        assertEquals(1, repository.loadCalls)
        assertTrue(delivered.isEmpty())
        assertFalse(controller.load(delivered::add))
    }

    private class DeferredTaskRunner : SettingsTaskRunner {
        private val pending = ArrayDeque<() -> Unit>()
        private var closed = false

        override fun <T> refresh(
            channel: String,
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean = enqueue(operation, deliver)

        override fun <T> execute(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean = enqueue(operation, deliver)

        override fun close() {
            closed = true
        }

        fun runNext() {
            pending.removeFirst().invoke()
        }

        fun runAll() {
            while (pending.isNotEmpty()) runNext()
        }

        private fun <T> enqueue(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean {
            if (closed) return false
            pending += { deliver(runCatching(operation)) }
            return true
        }
    }

    private class FakeAgentToolsRepository(
        private val loaded: AgentToolSettings,
    ) : AgentToolSettingsRepository {
        var loadCalls = 0
        var saveCalls = 0
        var saveFailure: Throwable? = null

        override fun load(): Result<AgentToolSettings> {
            loadCalls += 1
            return Result.success(loaded)
        }

        override fun save(settings: AgentToolSettings): Result<Unit> {
            saveCalls += 1
            return saveFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }

    private class FakeProviderRepository : ProviderSettingsRepository {
        var loadCalls = 0
        var saveCalls = 0
        var validateCalls = 0
        val writeOperations = mutableListOf<String>()

        override fun load(): Result<ProviderSettingsSnapshot> {
            loadCalls += 1
            return Result.success(ProviderSettingsSnapshot(null, false))
        }

        override fun save(
            profile: io.github.ethanbird.senseime.brain.api.ProviderProfile,
            credential: CharArray?,
        ): Result<ProviderSettingsSnapshot> {
            saveCalls += 1
            writeOperations += "save:${profile.model}"
            return Result.success(ProviderSettingsSnapshot(profile, credential != null))
        }

        override fun clearCredential(): Result<ProviderSettingsSnapshot> {
            writeOperations += "clear"
            return Result.success(ProviderSettingsSnapshot(null, false))
        }

        override fun hasValidConfiguration(): Result<Boolean> {
            validateCalls += 1
            return Result.success(true)
        }
    }

    private class FakeSpeechRepository : SpeechSettingsRepository {
        var loadCalls = 0
        var saveCalls = 0
        var clearCalls = 0

        override fun load(): Result<SpeechSettingsSnapshot> {
            loadCalls += 1
            return Result.success(SpeechSettingsSnapshot(null, false))
        }

        override fun save(
            profile: io.github.ethanbird.senseime.speech.SpeechProviderProfile,
            credential: CharArray?,
        ): Result<SpeechSettingsSnapshot> {
            saveCalls += 1
            return Result.success(SpeechSettingsSnapshot(profile, credential != null))
        }

        override fun clearCredential(): Result<SpeechSettingsSnapshot> {
            clearCalls += 1
            return Result.success(SpeechSettingsSnapshot(null, false))
        }
    }

    private fun providerProfile(model: String): ProviderProfile =
        ProviderProfile(
            id = "primary",
            displayName = "Primary",
            model = model,
        )
}
