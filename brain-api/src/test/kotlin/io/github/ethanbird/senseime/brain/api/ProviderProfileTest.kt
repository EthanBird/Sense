package io.github.ethanbird.senseime.brain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileTest {
    @Test
    fun `OpenAI profile defaults to HTTPS Responses endpoint`() {
        val profile = profile()

        assertTrue(profile.validate().isValid)
        assertEquals("https://api.openai.com/v1/responses", profile.endpointUrl())
        assertTrue(profile.streaming)
        assertEquals(ThinkingMode.DISABLED, profile.thinkingMode)
        assertEquals(
            ProviderTimeouts(
                connectTimeoutMs = 15_000,
                firstEventTimeoutMs = 30_000,
                streamIdleTimeoutMs = 30_000,
                totalTimeoutMs = 120_000,
            ),
            profile.timeouts,
        )
    }

    @Test
    fun `compatible profile resolves chat completions without duplicate slash`() {
        val profile = profile(
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            baseUrl = "https://llm.example.test/openai/v1/",
        )

        assertEquals(
            "https://llm.example.test/openai/v1/chat/completions",
            profile.endpointUrl(),
        )
    }

    @Test
    fun `plain HTTP remote host is rejected`() {
        val validation = profile(baseUrl = "http://llm.example.test/v1").validate()

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.code == ProviderProfileErrorCode.INSECURE_BASE_URL })
    }

    @Test
    fun `loopback HTTP needs explicit development opt in`() {
        assertFalse(profile(baseUrl = "http://127.0.0.1:11434/v1").validate().isValid)
        assertTrue(
            profile(
                baseUrl = "http://127.0.0.1:11434/v1",
                allowInsecureLocalhost = true,
            ).validate().isValid,
        )
    }

    @Test
    fun `URL credentials query and fragment are rejected`() {
        listOf(
            "https://user:pass@example.test/v1",
            "https://example.test/v1?key=x",
            "https://example.test/v1#fragment",
        ).forEach { url ->
            assertFalse("$url should be invalid", profile(baseUrl = url).validate().isValid)
        }
    }

    @Test
    fun `unknown profile schema is rejected`() {
        val error = profile(schemaVersion = 2).validate().errors.single {
            it.code == ProviderProfileErrorCode.UNSUPPORTED_SCHEMA_VERSION
        }

        assertEquals("$.schema_version", error.path)
    }

    @Test
    fun `timeout bounds and ordering are validated`() {
        val validation = profile(
            timeouts = ProviderTimeouts(
                connectTimeoutMs = 10,
                firstEventTimeoutMs = 40_000,
                streamIdleTimeoutMs = 1_000,
                totalTimeoutMs = 30_000,
            ),
        ).validate()

        assertTrue(validation.errors.count { it.code == ProviderProfileErrorCode.INVALID_TIMEOUT } >= 2)
    }

    @Test
    fun `requireValid reports invalid model`() {
        val exception = assertThrows(InvalidProviderProfileException::class.java) {
            profile(model = "\n").requireValid()
        }

        assertTrue(exception.errors.any { it.code == ProviderProfileErrorCode.INVALID_MODEL })
    }

    @Test
    fun `credential and wire request string forms redact authorization`() {
        val credential = ProviderCredential.Bearer("top-secret")
        val request = ProviderWireRequest(
            requestId = "request-1",
            attempt = 0,
            url = "https://example.test/v1/responses",
            headers = mapOf("Authorization" to "Bearer top-secret"),
            body = "{}".toByteArray(),
            connectTimeoutMs = 1_000,
            readTimeoutMs = 1_000,
        )

        assertFalse(credential.toString().contains("top-secret"))
        assertFalse(request.toString().contains("top-secret"))
    }

    @Test
    fun `bearer credential rejects values unsafe for an authorization header`() {
        listOf(
            "",
            "   ",
            "line\nbreak",
            "carriage\rreturn",
            "nul\u0000byte",
            "delete\u007fbyte",
            "x".repeat(8_193),
        ).forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderCredential.Bearer(token)
            }
        }
    }

    private fun profile(
        schemaVersion: Int = ProviderProfile.CURRENT_SCHEMA_VERSION,
        apiStyle: ProviderApiStyle = ProviderApiStyle.OPENAI_RESPONSES,
        baseUrl: String = ProviderProfile.DEFAULT_OPENAI_BASE_URL,
        model: String = "gpt-5-mini",
        timeouts: ProviderTimeouts = ProviderTimeouts(),
        allowInsecureLocalhost: Boolean = false,
    ) = ProviderProfile(
        schemaVersion = schemaVersion,
        id = "primary",
        displayName = "Primary",
        apiStyle = apiStyle,
        baseUrl = baseUrl,
        model = model,
        timeouts = timeouts,
        allowInsecureLocalhost = allowInsecureLocalhost,
    )
}
