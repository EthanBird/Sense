package io.github.ethanbird.senseime.brain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCompatibilityTest {
    @Test
    fun `official DeepSeek rejects Responses and strict JSON Schema`() {
        val issues = ProviderCompatibility.issues(
            profile(
                apiStyle = ProviderApiStyle.OPENAI_RESPONSES,
                structuredOutput = StructuredOutputMode.JSON_SCHEMA,
            ),
        )

        assertEquals(
            listOf(
                ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_CHAT_COMPLETIONS,
                ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_JSON_OBJECT,
            ),
            issues,
        )
    }

    @Test
    fun `official DeepSeek accepts Chat Completions with JSON Object`() {
        val issues = ProviderCompatibility.issues(
            profile(
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
        )

        assertTrue(issues.isEmpty())
        assertTrue(ProviderCompatibility.isOfficialDeepSeek("https://API.DeepSeek.com/v1/"))
    }

    @Test
    fun `compatible gateways are not guessed to be official DeepSeek`() {
        val profile = profile(
            apiStyle = ProviderApiStyle.OPENAI_RESPONSES,
            structuredOutput = StructuredOutputMode.JSON_SCHEMA,
        ).copy(baseUrl = "https://gateway.example/v1")

        assertTrue(ProviderCompatibility.issues(profile).isEmpty())
        assertFalse(ProviderCompatibility.isOfficialDeepSeek(profile.baseUrl))
    }

    private fun profile(
        apiStyle: ProviderApiStyle,
        structuredOutput: StructuredOutputMode,
    ) = ProviderProfile(
        id = "test",
        displayName = "DeepSeek",
        apiStyle = apiStyle,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-v4-pro",
        structuredOutput = structuredOutput,
    )
}
