package io.github.ethanbird.senseime

/**
 * Top-level settings destinations. Keeping this state independent from Android views makes
 * hierarchy and back behavior deterministic and unit-testable.
 */
internal enum class SettingsSection {
    HOME,
    KEYBOARD,
    PROVIDER,
    SOUL,
    TOOLS,
    SKILLS,
    VOICE,
    ABOUT,
}

internal enum class SettingsBackResult {
    CONSUMED,
    EXIT_ACTIVITY,
}

internal object SettingsSectionExitPolicy {
    fun shouldCancelProviderTest(
        section: SettingsSection,
        providerTestRunning: Boolean,
    ): Boolean = section == SettingsSection.PROVIDER && providerTestRunning
}

internal class SettingsNavigationState(initial: SettingsSection = SettingsSection.HOME) {
    var current: SettingsSection = initial
        private set

    fun open(section: SettingsSection) {
        current = section
    }

    fun restore(serialized: String?) {
        current = SettingsSection.entries.firstOrNull { it.name == serialized }
            ?: SettingsSection.HOME
    }

    fun back(): SettingsBackResult {
        if (current == SettingsSection.HOME) return SettingsBackResult.EXIT_ACTIVITY
        current = SettingsSection.HOME
        return SettingsBackResult.CONSUMED
    }
}
