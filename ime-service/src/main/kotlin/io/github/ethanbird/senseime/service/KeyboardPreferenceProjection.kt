package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.ui.KeyboardSizeProfile

/** Pure boundary from persisted integer dp values to the rendering profile. */
internal fun ImePreferencesV1.toKeyboardSizeProfile(): KeyboardSizeProfile = KeyboardSizeProfile(
    portraitHeightDp = portraitKeyboardHeightDp.toFloat(),
    landscapeHeightDp = landscapeKeyboardHeightDp.toFloat(),
)
