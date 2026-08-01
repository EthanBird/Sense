package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSettingsControllerTest {
    @Test
    fun heightDragUpdatesPreviewLiveAndPersistsTheSameOrientation() {
        assertEquals(
            KeyboardHeightPreviewState(heightDp = 512, landscape = false),
            KeyboardHeightDragPolicy.previewState(
                heightDp = 512,
                landscape = false,
                fromUser = true,
            ),
        )
        assertNull(
            KeyboardHeightDragPolicy.previewState(
                heightDp = 512,
                landscape = false,
                fromUser = false,
            ),
        )

        val original = ImePreferencesV1(
            portraitKeyboardHeightDp = 358,
            landscapeKeyboardHeightDp = 258,
        )
        assertEquals(
            original.copy(portraitKeyboardHeightDp = 512),
            KeyboardHeightDragPolicy.persistenceMutation(
                heightDp = 512,
                landscape = false,
            ).applyTo(original),
        )
        assertEquals(
            original.copy(landscapeKeyboardHeightDp = 333),
            KeyboardHeightDragPolicy.persistenceMutation(
                heightDp = 333,
                landscape = true,
            ).applyTo(original),
        )
    }

    @Test
    fun controllerDeliversOnlyAfterAtomicFieldUpdateCommits() {
        val repository = RecordingRepository()
        val controller = KeyboardSettingsController(repository, ImmediateSettingsTasks())
        var delivered: Result<ImePreferencesV1>? = null

        assertTrue(
            controller.update(KeyboardSettingsMutation.InputScheme(ChineseInputScheme.PINYIN_T9)) {
                delivered = it
            },
        )

        assertEquals(ChineseInputScheme.PINYIN_T9, repository.value.chineseInputScheme)
        assertEquals(repository.value, delivered?.getOrThrow())
    }

    @Test
    fun everyMutationChangesOnlyItsOwnedField() {
        val original = ImePreferencesV1(
            chineseInputScheme = ChineseInputScheme.PINYIN_T9,
            wubiAutoCommitMode = WubiAutoCommitMode.UNIQUE_AT_4,
            portraitKeyboardHeightDp = 401,
            landscapeKeyboardHeightDp = 277,
            t9SideSymbols = listOf("，", "。", "!"),
        )

        val cases = listOf(
            KeyboardSettingsMutation.InputScheme(ChineseInputScheme.WUBI_86) to
                original.copy(chineseInputScheme = ChineseInputScheme.WUBI_86),
            KeyboardSettingsMutation.WubiAutoCommit(WubiAutoCommitMode.OFF) to
                original.copy(wubiAutoCommitMode = WubiAutoCommitMode.OFF),
            KeyboardSettingsMutation.PortraitHeight(488) to
                original.copy(portraitKeyboardHeightDp = 488),
            KeyboardSettingsMutation.LandscapeHeight(312) to
                original.copy(landscapeKeyboardHeightDp = 312),
            KeyboardSettingsMutation.T9SideSymbols(listOf("？", "！")) to
                original.copy(t9SideSymbols = listOf("？", "！")),
        )

        cases.forEach { (mutation, expected) ->
            assertEquals(expected, mutation.applyTo(original))
        }
    }

    @Test
    fun fieldUpdateKeepsNewerCrossProcessValuesInEveryOtherField() {
        val newer = ImePreferencesV1(
            chineseInputScheme = ChineseInputScheme.WUBI_86,
            wubiAutoCommitMode = WubiAutoCommitMode.OFF,
            portraitKeyboardHeightDp = 430,
            landscapeKeyboardHeightDp = 300,
            t9SideSymbols = listOf("@", "#"),
        )
        val repository = RecordingRepository().apply { value = newer }
        val controller = KeyboardSettingsController(repository, ImmediateSettingsTasks())

        controller.update(KeyboardSettingsMutation.PortraitHeight(500)) {}

        assertEquals(newer.copy(portraitKeyboardHeightDp = 500), repository.value)
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
