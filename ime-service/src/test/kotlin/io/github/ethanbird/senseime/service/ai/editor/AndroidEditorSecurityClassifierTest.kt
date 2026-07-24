package io.github.ethanbird.senseime.service.ai.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEditorSecurityClassifierTest {
    @Test
    fun recognizesAllAndroidPasswordVariationsWithoutAndroidRuntime() {
        val textPassword = AndroidEditorSecurityClassifier.classify(0x00000001 or 0x80, 0)
        val visiblePassword =
            AndroidEditorSecurityClassifier.classify(0x00000001 or 0x90, 0)
        val webPassword = AndroidEditorSecurityClassifier.classify(0x00000001 or 0xe0, 0)
        val numberPassword =
            AndroidEditorSecurityClassifier.classify(0x00000002 or 0x10, 0)

        assertTrue(textPassword.isTextPassword)
        assertTrue(visiblePassword.isVisibleTextPassword)
        assertTrue(webPassword.isWebPassword)
        assertTrue(numberPassword.isNumberPassword)
    }

    @Test
    fun variationBitsAreInterpretedWithinTheirInputClass() {
        val numberWithTextPasswordBits =
            AndroidEditorSecurityClassifier.classify(0x00000002 or 0x80, 0)
        val textWithNumberPasswordBits =
            AndroidEditorSecurityClassifier.classify(0x00000001 or 0x10, 0)

        assertFalse(numberWithTextPasswordBits.isTextPassword)
        assertFalse(textWithNumberPasswordBits.isNumberPassword)
    }

    @Test
    fun noPersonalizedLearningAndOtpSignalsArePreserved() {
        val security = AndroidEditorSecurityClassifier.classify(
            inputType = 0x00000002,
            imeOptions = 0x01000000,
            oneTimeCodeSignal = true,
        )

        assertTrue(security.noPersonalizedLearning)
        assertTrue(security.isOneTimeCode)
        assertEquals(
            EditorCaptureBlockReason.ONE_TIME_CODE,
            EditorSnapshotCapturePolicy.preflight(security),
        )
    }

    @Test
    fun classificationFailureRemainsFailClosed() {
        val security = AndroidEditorSecurityClassifier.classify(
            inputType = 0,
            imeOptions = 0,
            classificationComplete = false,
        )

        assertEquals(
            EditorCaptureBlockReason.SECURITY_CLASSIFICATION_UNAVAILABLE,
            EditorSnapshotCapturePolicy.preflight(security),
        )
    }

    @Test
    fun commonOtpMetadataIsRecognizedWithoutMatchingGenericCodeFields() {
        listOf(
            "Verification code",
            "sms_code",
            "Enter your 2FA token",
            "authentication code",
            "安全校验码",
        ).forEach { metadata ->
            assertTrue(AndroidEditorSecurityClassifier.hasOneTimeCodeSignal(metadata))
        }

        assertFalse(AndroidEditorSecurityClassifier.hasOneTimeCodeSignal("postal code"))
        assertFalse(AndroidEditorSecurityClassifier.hasOneTimeCodeSignal("source code"))
    }
}
