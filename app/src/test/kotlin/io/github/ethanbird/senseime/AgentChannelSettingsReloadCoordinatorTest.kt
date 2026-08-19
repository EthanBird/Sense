package io.github.ethanbird.senseime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChannelSettingsReloadCoordinatorTest {
    @Test
    fun `revision event during initial slow load schedules one fresh read`() {
        val coordinator = AgentChannelSettingsReloadCoordinator()
        assertTrue(coordinator.request())
        assertFalse(coordinator.request())
        assertFalse(coordinator.request())
        assertTrue(coordinator.complete())
        assertTrue(coordinator.request())
        assertFalse(coordinator.complete())
    }
}
