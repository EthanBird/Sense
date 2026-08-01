package io.github.ethanbird.senseime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAutoSavePolicyTest {
    @Test
    fun duplicateFocusAndLifecycleFlushIsSuppressedButARevertIsEnqueued() {
        assertFalse(
            SettingsAutoSavePolicy.shouldEnqueue(
                lastRequestedValue = "A",
                requestedValue = "A",
                lastCredentialSignature = null,
                requestedCredentialSignature = null,
            ),
        )
        assertTrue(
            SettingsAutoSavePolicy.shouldEnqueue(
                lastRequestedValue = "B",
                requestedValue = "A",
                lastCredentialSignature = null,
                requestedCredentialSignature = null,
            ),
        )
    }

    @Test
    fun credentialEditAndExplicitTestBarrierAlwaysEnqueue() {
        val first = SettingsAutoSavePolicy.credentialSignature("token-one")
        val second = SettingsAutoSavePolicy.credentialSignature("token-two")
        assertNotEquals(first, second)
        assertTrue(
            SettingsAutoSavePolicy.shouldEnqueue(
                lastRequestedValue = "profile",
                requestedValue = "profile",
                lastCredentialSignature = first,
                requestedCredentialSignature = second,
            ),
        )
        assertTrue(
            SettingsAutoSavePolicy.shouldEnqueue(
                lastRequestedValue = "profile",
                requestedValue = "profile",
                lastCredentialSignature = null,
                requestedCredentialSignature = null,
                forceBarrier = true,
            ),
        )
    }

    @Test
    fun completedCredentialSaveDoesNotClearTextTypedAfterThatRequestStarted() {
        assertFalse(
            SettingsAutoSavePolicy.shouldClearCredentialEditor(
                submittedValue = "token-before-save",
                currentEditorValue = "token-typed-later",
            ),
        )
    }

    @Test
    fun completedCredentialSaveClearsTheEditorStillOwnedByThatRequest() {
        assertTrue(
            SettingsAutoSavePolicy.shouldClearCredentialEditor(
                submittedValue = "same-token",
                currentEditorValue = "same-token",
            ),
        )
        assertFalse(
            SettingsAutoSavePolicy.shouldClearCredentialEditor(
                submittedValue = "",
                currentEditorValue = "",
            ),
        )
    }
}
