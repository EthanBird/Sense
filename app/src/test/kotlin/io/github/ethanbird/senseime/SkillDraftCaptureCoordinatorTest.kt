package io.github.ethanbird.senseime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDraftCaptureCoordinatorTest {
    @Test
    fun repeatedLargeDocumentCallbacksCoalesceIntoOneCapture() {
        val coordinator = SkillDraftCaptureCoordinator()

        repeat(64 * 1024) {
            coordinator.markDirty()
        }

        assertTrue(coordinator.claimCapture())
        assertFalse(coordinator.claimCapture())
    }

    @Test
    fun explicitBindingSelectionCapturesEvenWhenDocumentIsClean() {
        val coordinator = SkillDraftCaptureCoordinator()

        assertTrue(coordinator.claimCapture(force = true))
        assertFalse(coordinator.claimCapture())
    }

    @Test
    fun renderResetDropsProgrammaticDirtyMarker() {
        val coordinator = SkillDraftCaptureCoordinator()
        coordinator.markDirty()

        coordinator.reset()

        assertFalse(coordinator.claimCapture())
    }
}
