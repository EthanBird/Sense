package io.github.ethanbird.senseime

internal sealed interface SettingsEffect {
    data object OpenInputMethodSettings : SettingsEffect
    data object ShowInputMethodPicker : SettingsEffect
    data object RequestRecordAudio : SettingsEffect
    data object RequestSenseMicPermissions : SettingsEffect
    data object OpenApplicationDetails : SettingsEffect
    data class ShowDictionaryNotice(val text: String) : SettingsEffect
}
