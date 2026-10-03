package io.github.ethanbird.senseime.service

import android.view.accessibility.AccessibilityManager
import org.junit.Assert.*
import org.junit.Test

class AssociationTimeoutPolicyTest {
    @Test fun systemPreferenceExtendsRatherThanTruncatesTheInteractionWindow() {
        assertEquals(10_000L, AssociationTimeoutPolicy.delayMillis { original, flags ->
            assertEquals(4_500, original)
            assertTrue(flags and AccessibilityManager.FLAG_CONTENT_TEXT != 0)
            assertTrue(flags and AccessibilityManager.FLAG_CONTENT_CONTROLS != 0)
            assertTrue(flags and AccessibilityManager.FLAG_CONTENT_ICONS != 0)
            10_000
        })
        assertEquals(Int.MAX_VALUE.toLong(), AssociationTimeoutPolicy.delayMillis { _, _ -> Int.MAX_VALUE })
    }

    @Test fun defaultNegativeAndUnavailableRecommendationsKeepTheOrdinaryInterval() {
        for (value in listOf(-1, 0, 100, 4_500)) assertEquals(4_500L, AssociationTimeoutPolicy.delayMillis { _, _ -> value })
        assertEquals(4_500L, AssociationTimeoutPolicy.delayMillis { _, _ -> error("provider unavailable") })
    }
}
