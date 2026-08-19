package io.github.ethanbird.senseime

/**
 * Top-level settings destinations. Keeping this state independent from Android views makes
 * hierarchy and back behavior deterministic and unit-testable.
 */
internal enum class SettingsSection {
    HOME,
    KEYBOARD,
    PROVIDER,
    PROVIDER_CATALOG,
    SOUL,
    TOOLS,
    ACTION_SKILLS,
    SKILLS,
    VOICE,
    MIC,
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
    private var parent: SettingsSection = SettingsSection.HOME

    fun open(section: SettingsSection) {
        current = section
        parent = SettingsSection.HOME
    }

    fun openChild(section: SettingsSection, parentSection: SettingsSection) {
        current = section
        parent = parentSection
    }

    fun restore(serialized: String?) {
        current = SettingsSection.entries.firstOrNull { it.name == serialized }
            ?: SettingsSection.HOME
        parent = SettingsSection.HOME
    }

    fun back(): SettingsBackResult {
        if (current == SettingsSection.HOME) return SettingsBackResult.EXIT_ACTIVITY
        current = parent
        parent = SettingsSection.HOME
        return SettingsBackResult.CONSUMED
    }
}
