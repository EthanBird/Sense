package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.KeyboardHeightPolicy
import io.github.ethanbird.senseime.config.T9SideSymbolPolicy
import io.github.ethanbird.senseime.config.WubiAutoCommitMode

/** The exact live preview state emitted while a user drags either height slider. */
internal data class KeyboardHeightPreviewState(
    val heightDp: Int,
    val landscape: Boolean,
) {
    init {
        KeyboardHeightPolicy.requireValid(heightDp)
    }
}

/** Keeps drag-preview and drag-stop persistence mapped to the same orientation. */
internal object KeyboardHeightDragPolicy {
    fun previewState(
        heightDp: Int,
        landscape: Boolean,
        fromUser: Boolean,
    ): KeyboardHeightPreviewState? =
        if (fromUser) KeyboardHeightPreviewState(heightDp, landscape) else null

    fun persistenceMutation(
        heightDp: Int,
        landscape: Boolean,
    ): KeyboardSettingsMutation =
        if (landscape) {
            KeyboardSettingsMutation.LandscapeHeight(heightDp)
        } else {
            KeyboardSettingsMutation.PortraitHeight(heightDp)
        }
}

/** One atomic field-level keyboard settings write. */
internal sealed interface KeyboardSettingsMutation {
    fun applyTo(latest: ImePreferencesV1): ImePreferencesV1

    data class InputScheme(
        val value: ChineseInputScheme,
    ) : KeyboardSettingsMutation {
        override fun applyTo(latest: ImePreferencesV1): ImePreferencesV1 =
            latest.copy(chineseInputScheme = value)
    }

    data class WubiAutoCommit(
        val value: WubiAutoCommitMode,
    ) : KeyboardSettingsMutation {
        override fun applyTo(latest: ImePreferencesV1): ImePreferencesV1 =
            latest.copy(wubiAutoCommitMode = value)
    }

    data class PortraitHeight(
        val valueDp: Int,
    ) : KeyboardSettingsMutation {
        init {
            KeyboardHeightPolicy.requireValid(valueDp, "portraitKeyboardHeightDp")
        }

        override fun applyTo(latest: ImePreferencesV1): ImePreferencesV1 =
            latest.copy(portraitKeyboardHeightDp = valueDp)
    }

    data class LandscapeHeight(
        val valueDp: Int,
    ) : KeyboardSettingsMutation {
        init {
            KeyboardHeightPolicy.requireValid(valueDp, "landscapeKeyboardHeightDp")
        }

        override fun applyTo(latest: ImePreferencesV1): ImePreferencesV1 =
            latest.copy(landscapeKeyboardHeightDp = valueDp)
    }

    data class T9SideSymbols(
        val values: List<String>,
    ) : KeyboardSettingsMutation {
        init {
            T9SideSymbolPolicy.requireValid(values)
        }

        override fun applyTo(latest: ImePreferencesV1): ImePreferencesV1 =
            latest.copy(t9SideSymbols = values.toList())
    }
}
