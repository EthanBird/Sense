package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrivacyPolicyTest {
    @Test
    fun ordinaryEditorAllowsLocalPersistence() {
        assertTrue(
            EditorPrivacyPolicy.allowsPersistence(
                noPersonalizedLearning = false,
                passwordVariation = false,
            ),
        )
    }

    @Test
    fun noPersonalizedLearningDisablesLocalPersistence() {
        assertFalse(
            EditorPrivacyPolicy.allowsPersistence(
                noPersonalizedLearning = true,
                passwordVariation = false,
            ),
        )
    }

    @Test
    fun passwordEditorDisablesLocalPersistence() {
        assertFalse(
            EditorPrivacyPolicy.allowsPersistence(
                noPersonalizedLearning = false,
                passwordVariation = true,
            ),
        )
    }
}
