package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionTestProtocolTest {
    @Test
    fun requestIsValidAndContainsOnlyFixedSyntheticText() {
        val request = ProviderConnectionTestProtocol.buildRequest(
            requestId = "provider-test-request",
            generation = 7L,
            capturedAtMonotonicMs = 123L,
        )

        val validation = ProtocolValidator.validate(request)
        assertTrue(validation.errors.toString(), validation.isValid)
        assertEquals(ProviderConnectionTestProtocol.TEST_TEXT, request.snapshot.text)
        assertEquals(
            EditorTextDigest.sha256Utf8(ProviderConnectionTestProtocol.TEST_TEXT),
            request.snapshot.baseSha256,
        )
        assertEquals(SnapshotCapability.FULL_DOCUMENT, request.snapshot.capability)
        assertEquals(PatchTarget.WHOLE_FIELD, request.snapshot.target)
        assertEquals(request.snapshot.text.length, request.snapshot.selection?.start)
        assertEquals(request.snapshot.text.length, request.snapshot.selection?.end)
        assertFalse(request.snapshot.truncated)
        assertEquals(request.maxOutputChars, request.snapshot.maxOutputChars)
    }

    @Test
    fun providerFailuresMapToSafeStableCategories() {
        assertEquals(
            ProviderConnectionTestFailure.NOT_CONFIGURED,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_NOT_CONFIGURED"),
        )
        assertEquals(
            ProviderConnectionTestFailure.AUTHENTICATION,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_AUTHENTICATION"),
        )
        assertEquals(
            ProviderConnectionTestFailure.QUOTA,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_QUOTA"),
        )
        assertEquals(
            ProviderConnectionTestFailure.CONFIGURATION,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_CONFIGURATION"),
        )
        assertEquals(
            ProviderConnectionTestFailure.RATE_LIMIT,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_RATE_LIMIT"),
        )
        assertEquals(
            ProviderConnectionTestFailure.UNAVAILABLE,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_UNAVAILABLE"),
        )
        assertEquals(
            ProviderConnectionTestFailure.NETWORK,
            ProviderConnectionTestProtocol.mapFailureName("PROVIDER_FAILURE"),
        )
        assertEquals(
            ProviderConnectionTestFailure.TIMEOUT,
            ProviderConnectionTestProtocol.mapFailureName("TOTAL_TIMEOUT"),
        )
        assertEquals(
            ProviderConnectionTestFailure.PROTOCOL,
            ProviderConnectionTestProtocol.mapFailureName("PROTOCOL_INVALID"),
        )
        assertEquals(
            ProviderConnectionTestFailure.INTERNAL,
            ProviderConnectionTestProtocol.mapFailureName("FUTURE_PRIVATE_ERROR"),
        )
    }

    @Test
    fun replacingAndRevokingRunRejectsLateIdentities() {
        val gate = ProviderConnectionTestRunGate()
        val first = ProviderConnectionTestIdentity("first", 1L)
        val second = ProviderConnectionTestIdentity("second", 2L)

        assertNull(gate.replace(first))
        assertTrue(gate.isActive("first", 1L))
        assertEquals(first, gate.replace(second))
        assertFalse(gate.isActive("first", 1L))
        assertTrue(gate.isActive("second", 2L))

        assertNull(gate.takeIfActive("first", 1L))
        assertEquals(second, gate.takeIfActive("second", 2L))
        assertFalse(gate.isActive("second", 2L))

        gate.replace(first)
        assertEquals(first, gate.revoke())
        assertNull(gate.revoke())
    }
}
