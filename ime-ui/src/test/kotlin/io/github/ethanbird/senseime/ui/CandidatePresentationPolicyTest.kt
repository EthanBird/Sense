package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePresentationPolicyTest {
    @Test
    fun sameRevisionDecodeCompletionPreservesExpandedPage() {
        assertFalse(
            CandidatePresentationPolicy.shouldResetNavigation(
                previousRevision = 12L,
                previousComposing = "pipei",
                nextRevision = 12L,
                nextComposing = "pipei",
            ),
        )
        assertTrue(
            CandidatePresentationPolicy.shouldResetNavigation(
                previousRevision = 12L,
                previousComposing = "pipei",
                nextRevision = 13L,
                nextComposing = "pipeia",
            ),
        )
    }

    @Test
    fun collapsedPendingAndReadyCompositionUseIdenticalHeaderGeometry() {
        val pending = CandidatePresentationPolicy.headerSpec(
            composing = "pipei",
            hasCandidates = false,
        )
        val ready = CandidatePresentationPolicy.headerSpec(
            composing = "pipei",
            hasCandidates = true,
        )

        assertEquals(pending, ready)
        assertEquals(CandidatePresentationPolicy.HeaderRole.COMPOSING, ready?.role)
    }
}
