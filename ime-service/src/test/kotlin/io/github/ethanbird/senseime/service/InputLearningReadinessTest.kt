package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLearningReadinessTest {
    @Test
    fun wubiLearningDoesNotWaitForTheUnrelatedPinyinAsset() {
        assertTrue(
            isWubiLearningReady(
                localPersistenceAllowed = true,
                adaptiveWubiDecoderReady = true,
            ),
        )
        assertFalse(
            isPinyinLearningReady(
                localPersistenceAllowed = true,
                productionPinyinDecoderReady = false,
            ),
        )
    }

    @Test
    fun editorPrivacyBlocksBothSchemeLocalLearningPipelines() {
        assertFalse(isPinyinLearningReady(false, productionPinyinDecoderReady = true))
        assertFalse(isWubiLearningReady(false, adaptiveWubiDecoderReady = true))
    }
}
