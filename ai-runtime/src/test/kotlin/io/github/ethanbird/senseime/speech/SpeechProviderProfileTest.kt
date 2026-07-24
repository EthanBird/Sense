package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechProviderProfileTest {
    @Test
    fun `catalog exposes executable system OpenAI and Deepgram providers`() {
        val system = SpeechProviderPresetCatalog.require(SpeechProviderPresetCatalog.SYSTEM)
        assertTrue(system.canTranscribe)
        assertEquals(
            SpeechProviderCredentialRequirement.NONE,
            system.credentialRequirement,
        )

        listOf(
            SpeechProviderPresetCatalog.OPENAI_COMPATIBLE,
            SpeechProviderPresetCatalog.DEEPGRAM,
        ).forEach { id ->
            val preset = SpeechProviderPresetCatalog.require(id)
            assertTrue(preset.canTranscribe)
            assertEquals(
                SpeechProviderRuntimeCapability.AVAILABLE,
                preset.runtimeCapability,
            )
            assertEquals(
                SpeechProviderCredentialRequirement.API_KEY,
                preset.credentialRequirement,
            )
            assertTrue(preset.capabilityNotice.isNullOrBlank())
        }

        val dashScope = SpeechProviderPresetCatalog.require(
            SpeechProviderPresetCatalog.ALIBABA_DASHSCOPE,
        )
        assertFalse(dashScope.canTranscribe)
        assertEquals(
            SpeechProviderRuntimeCapability.CONFIGURATION_ONLY,
            dashScope.runtimeCapability,
        )
        assertTrue(dashScope.capabilityNotice.orEmpty().isNotBlank())
    }

    @Test
    fun `every catalog default profile is valid and self-consistent`() {
        SpeechProviderPresetCatalog.all.forEach { preset ->
            val profile = preset.defaultProfile()
            assertTrue(
                "${preset.id}: ${profile.validate().errors}",
                profile.validate().isValid,
            )
            assertEquals(preset.transport, profile.transport)
            assertEquals(preset.protocol, profile.protocol)
        }
    }

    @Test
    fun `system provider rejects cloud fields`() {
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.SYSTEM)
            .defaultProfile()
            .copy(endpointUrl = "https://example.com/listen")

        val validation = profile.validate()
        assertFalse(validation.isValid)
        assertNotNull(
            validation.errors.firstOrNull {
                it.code == SpeechProviderErrorCode.PRESET_MISMATCH
            },
        )
    }

    @Test
    fun `cloud transport enforces encrypted endpoint schemes`() {
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.DEEPGRAM)
            .defaultProfile()
            .copy(endpointUrl = "http://api.deepgram.com/v1/listen")

        val validation = profile.validate()
        assertFalse(validation.isValid)
        assertTrue(
            validation.errors.any {
                it.code == SpeechProviderErrorCode.INSECURE_ENDPOINT
            },
        )
    }

    @Test
    fun `endpoint query cannot persist a credential or hidden request option`() {
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.DEEPGRAM)
            .defaultProfile()
            .copy(endpointUrl = "https://api.deepgram.com/v1/listen?token=secret")

        assertTrue(
            profile.validate().errors.any {
                it.code == SpeechProviderErrorCode.INVALID_ENDPOINT
            },
        )
    }

    @Test
    fun `preset identity cannot silently change transport protocol`() {
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.OPENAI_COMPATIBLE)
            .defaultProfile()
            .copy(
                transport = SpeechProviderTransport.WEBSOCKET_PCM,
                protocol = SpeechProviderProtocol.DEEPGRAM_LISTEN,
            )

        assertTrue(
            profile.validate().errors.any {
                it.code == SpeechProviderErrorCode.PRESET_MISMATCH
            },
        )
    }

    @Test
    fun `official Deepgram websocket preview profile migrates to prerecorded HTTPS`() {
        val legacy = SpeechProviderProfile(
            presetId = SpeechProviderPresetCatalog.DEEPGRAM,
            displayName = "Deepgram",
            transport = SpeechProviderTransport.WEBSOCKET_PCM,
            protocol = SpeechProviderProtocol.DEEPGRAM_LISTEN,
            endpointUrl = "wss://api.deepgram.com/v1/listen",
            model = "nova-3",
        )

        val migrated = SpeechProviderProfileMigration.migrate(legacy)

        assertEquals(SpeechProviderTransport.HTTP_AUDIO_WAV, migrated.transport)
        assertEquals("https://api.deepgram.com/v1/listen", migrated.endpointUrl)
        assertTrue(migrated.validate().isValid)
    }
}
