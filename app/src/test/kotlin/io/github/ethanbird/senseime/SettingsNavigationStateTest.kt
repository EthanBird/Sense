package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.config.ImeSettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationStateTest {
    @Test
    fun everyDetailReturnsToHomeBeforeActivityExit() {
        SettingsSection.entries
            .filterNot { it == SettingsSection.HOME }
            .forEach { destination ->
                val state = SettingsNavigationState()
                state.open(destination)

                assertEquals(SettingsBackResult.CONSUMED, state.back())
                assertEquals(SettingsSection.HOME, state.current)
                assertEquals(SettingsBackResult.EXIT_ACTIVITY, state.back())
            }
    }

    @Test
    fun restoreRejectsUnknownOrMissingDestinations() {
        val state = SettingsNavigationState(SettingsSection.SKILLS)

        state.restore("NOT_A_DESTINATION")
        assertEquals(SettingsSection.HOME, state.current)

        state.open(SettingsSection.PROVIDER)
        state.restore(null)
        assertEquals(SettingsSection.HOME, state.current)
    }

    @Test
    fun restoreKeepsKnownDestination() {
        val state = SettingsNavigationState()

        state.restore(SettingsSection.SOUL.name)

        assertEquals(SettingsSection.SOUL, state.current)
    }

    @Test
    fun imeKeyboardRouteOpensKeyboardSettingsDirectly() {
        val state = SettingsNavigationState()

        state.restore(ImeSettingsRoute.KEYBOARD_SECTION)

        assertEquals(SettingsSection.KEYBOARD, state.current)
    }

    @Test
    fun providerTestCancellationPolicyIsSharedBySystemAndTitleBack() {
        assertEquals(
            true,
            SettingsSectionExitPolicy.shouldCancelProviderTest(
                SettingsSection.PROVIDER,
                providerTestRunning = true,
            ),
        )
        assertEquals(
            false,
            SettingsSectionExitPolicy.shouldCancelProviderTest(
                SettingsSection.PROVIDER,
                providerTestRunning = false,
            ),
        )
        SettingsSection.entries
            .filterNot { it == SettingsSection.PROVIDER }
            .forEach { section ->
                assertEquals(
                    false,
                    SettingsSectionExitPolicy.shouldCancelProviderTest(
                        section,
                        providerTestRunning = true,
                    ),
                )
            }
    }

    @Test
    fun nestedProviderPagesReturnToTheirParent() {
        val state = SettingsNavigationState()
        state.open(SettingsSection.PROVIDER)
        state.openChild(SettingsSection.PROVIDER_CATALOG, SettingsSection.PROVIDER)

        assertEquals(SettingsBackResult.CONSUMED, state.back())
        assertEquals(SettingsSection.PROVIDER, state.current)
    }
}
