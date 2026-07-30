package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionSearchBudgetTest {
    @Test
    fun compactCanonicalDecodeKeepsASeparateBoundedPolicy() {
        val budget = CorrectionSearchBudget.PRODUCTION

        assertEquals(48, budget.spellingPathLimit(allowComposedCorrections = true, outputLimit = 10))
        assertEquals(24, budget.spellingPathLimit(allowComposedCorrections = false, outputLimit = 10))
        assertEquals(48, budget.spellingPathLimit(allowComposedCorrections = false, outputLimit = 64))
        assertEquals(4, budget.composedPathLimit(allowComposedCorrections = true, outputLimit = 10))
        assertEquals(1, budget.composedPathLimit(allowComposedCorrections = false, outputLimit = 10))
        assertEquals(3, budget.composedPathLimit(allowComposedCorrections = false, outputLimit = 64))
    }
}
