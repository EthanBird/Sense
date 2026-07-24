package io.github.ethanbird.senseime.brain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCompatibilityTest {
    @Test
    fun `official DeepSeek rejects Responses but native tool makes output setting irrelevant`() {
        val issues = ProviderCompatibility.issues(
            profile(
                apiStyle = ProviderApiStyle.OPENAI_RESPONSES,
                structuredOutput = StructuredOutputMode.JSON_SCHEMA,
            ),
        )

        assertEquals(
            listOf(ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_CHAT_COMPLETIONS),
            issues,
        )
    }

    @Test
    fun `official DeepSeek accepts Chat Completions with any legacy output setting`() {
        val issues = StructuredOutputMode.entries.flatMap { outputMode ->
            ProviderCompatibility.issues(
                profile(
                    apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                    structuredOutput = outputMode,
                    thinkingMode = ThinkingMode.DISABLED,
                ),
            )
        }

        assertTrue(issues.isEmpty())
        assertTrue(ProviderCompatibility.isOfficialDeepSeek("https://API.DeepSeek.com/v1/"))
    }

    @Test
    fun `official DeepSeek accepts automatic thinking and compatible effort levels`() {
        listOf(
            ThinkingMode.AUTO to ReasoningEffort.DEFAULT,
            ThinkingMode.AUTO to ReasoningEffort.HIGH,
            ThinkingMode.ENABLED to ReasoningEffort.LOW,
            ThinkingMode.ENABLED to ReasoningEffort.MEDIUM,
        ).forEach { (thinkingMode, reasoningEffort) ->
            val issues = ProviderCompatibility.issues(
                profile(
                    apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                    structuredOutput = StructuredOutputMode.JSON_OBJECT,
                    thinkingMode = thinkingMode,
                    reasoningEffort = reasoningEffort,
                ),
            )

            assertTrue("$thinkingMode/$reasoningEffort should be accepted", issues.isEmpty())
        }
    }

    @Test
    fun `official DeepSeek rejects effort when disabled and unsupported active effort levels`() {
        listOf(
            ThinkingMode.DISABLED to ReasoningEffort.LOW,
            ThinkingMode.ENABLED to ReasoningEffort.NONE,
            ThinkingMode.AUTO to ReasoningEffort.MINIMAL,
        ).forEach { (thinkingMode, reasoningEffort) ->
            val issues = ProviderCompatibility.issues(
                profile(
                    apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                    structuredOutput = StructuredOutputMode.JSON_OBJECT,
                    thinkingMode = thinkingMode,
                    reasoningEffort = reasoningEffort,
                ),
            )

            assertEquals(
                listOf(ProviderCompatibilityIssue.DEEPSEEK_REASONING_CONFIGURATION_UNSUPPORTED),
                issues,
            )
        }
    }

    @Test
    fun `legacy official DeepSeek migrates to disabled while unknown providers remain automatic`() {
        assertEquals(
            ThinkingMode.DISABLED,
            ProviderCompatibility.thinkingModeForLegacyProfile("https://api.deepseek.com/v1"),
        )
        assertEquals(
            ThinkingMode.AUTO,
            ProviderCompatibility.thinkingModeForLegacyProfile("https://gateway.example/v1"),
        )
    }

    @Test
    fun `retired DeepSeek aliases migrate only on the official endpoint`() {
        assertEquals(
            "deepseek-v4-flash",
            ProviderCompatibility.activeModelForSavedProfile(
                "https://api.deepseek.com/v1",
                "deepseek-chat",
            ),
        )
        assertEquals(
            "account-model",
            ProviderCompatibility.activeModelForSavedProfile(
                "https://api.deepseek.com/v1",
                "account-model",
            ),
        )
        assertEquals(
            "deepseek-chat",
            ProviderCompatibility.activeModelForSavedProfile(
                "https://gateway.example/v1",
                "deepseek-chat",
            ),
        )
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
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    ) = ProviderProfile(
        id = "test",
        displayName = "DeepSeek",
        apiStyle = apiStyle,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-v4-pro",
        structuredOutput = structuredOutput,
        thinkingMode = thinkingMode,
        reasoningEffort = reasoningEffort,
    )
}
