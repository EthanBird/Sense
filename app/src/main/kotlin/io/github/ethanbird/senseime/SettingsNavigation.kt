package io.github.ethanbird.senseime

/**
 * Top-level settings destinations. Keeping this state independent from Android views makes
 * hierarchy and back behavior deterministic and unit-testable.
 */
internal enum class SettingsSection {
    HOME,
    AGENT,
    MORE,
    KEYBOARD,
    PROVIDER,
    PROVIDER_CATALOG,
    SOUL,
    TOOLS,
    CHANNELS,
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
    private val parents = mutableListOf<SettingsSection>()

    val topLevel: SettingsSection
        get() = when (current) {
            SettingsSection.HOME, SettingsSection.KEYBOARD -> SettingsSection.HOME
            SettingsSection.SKILLS, SettingsSection.ACTION_SKILLS -> SettingsSection.SKILLS
            SettingsSection.MORE, SettingsSection.VOICE, SettingsSection.MIC,
            SettingsSection.ABOUT -> SettingsSection.MORE
            else -> SettingsSection.AGENT
        }

    fun open(section: SettingsSection) {
        current = section
        parents.clear()
        if (section != SettingsSection.HOME) parents += SettingsSection.HOME
    }

    fun openChild(section: SettingsSection, parentSection: SettingsSection) {
        if (current != parentSection) open(parentSection)
        if (section == current) return
        // Returning to a page already in the path pops it rather than creating a cycle.
        val existing = parents.indexOf(section)
        if (existing >= 0) {
            while (parents.size > existing) parents.removeAt(parents.lastIndex)
        } else {
            parents += current
        }
        current = section
    }

    fun serialize(): String = (parents + current).joinToString(">") { it.name }

    fun restore(serialized: String?) {
        val path = serialized?.split('>')?.map { name ->
            SettingsSection.entries.firstOrNull { it.name == name }
        }.orEmpty()
        if (path.isEmpty() || path.any { it == null } || path.distinct().size != path.size) {
            open(SettingsSection.HOME)
            return
        }
        open(requireNotNull(path.last()))
        if (path.size > 1) {
            parents.clear()
            parents.addAll(path.dropLast(1).filterNotNull())
        }
    }

    fun back(): SettingsBackResult {
        if (current == SettingsSection.HOME) return SettingsBackResult.EXIT_ACTIVITY
        current = if (parents.isEmpty()) SettingsSection.HOME else parents.removeAt(parents.lastIndex)
        return SettingsBackResult.CONSUMED
    }
}
