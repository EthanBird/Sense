package io.github.ethanbird.senseime.brain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetCatalogTest {
    @Test
    fun `all built-in presets produce valid secret-free profiles`() {
        val builtIns = ProviderPresetCatalog.presets.filterNot(ProviderPreset::isCustom)

        assertEquals(8, builtIns.size)
        builtIns.forEach { preset ->
            assertTrue("${preset.id} must be valid", preset.profile().validate().isValid)
            assertTrue(preset.baseUrl.startsWith("https://"))
            assertTrue(preset.model.isNotBlank())
        }
    }

    @Test
    fun `legacy profiles are detected by endpoint while retaining expert model overrides`() {
        val legacy = ProviderPresetCatalog
            .requirePreset(ProviderPresetId.DEEPSEEK)
            .profile()
            .copy(
                baseUrl = "HTTPS://API.DEEPSEEK.COM/v1/",
                model = "account-specific-model",
            )

        assertEquals(ProviderPresetId.DEEPSEEK, ProviderPresetCatalog.detect(legacy).id)
        assertEquals(
            ProviderPresetId.DEEPSEEK,
            ProviderPresetCatalog.detect(legacy.copy(baseUrl = "https://api.deepseek.com")).id,
        )
        assertEquals(
            ProviderPresetId.CUSTOM,
            ProviderPresetCatalog.detect(
                legacy.copy(baseUrl = "https://provider.example/v1"),
            ).id,
        )
    }

    @Test
    fun `DeepSeek preset does not depend on retired legacy aliases`() {
        assertEquals(
            "deepseek-v4-flash",
            ProviderPresetCatalog.requirePreset(ProviderPresetId.DEEPSEEK).model,
        )
    }

    @Test
    fun `reasoning strengths round trip through existing profile fields`() {
        val base = ProviderPresetCatalog.default.profile()

        ProviderReasoningStrength.entries.forEach { strength ->
            val mapped = strength.applyTo(base)
            assertEquals(strength, ProviderReasoningStrength.from(mapped))
            assertFalse(
                mapped.reasoningEffort == ReasoningEffort.HIGH &&
                    strength != ProviderReasoningStrength.DEEP,
            )
        }

        val quick = ProviderReasoningStrength.QUICK.applyTo(base)
        assertEquals(ThinkingMode.DISABLED, quick.thinkingMode)
        assertEquals(ReasoningEffort.DEFAULT, quick.reasoningEffort)
        val deep = ProviderReasoningStrength.DEEP.applyTo(base)
        assertEquals(ThinkingMode.ENABLED, deep.thinkingMode)
        assertEquals(ReasoningEffort.HIGH, deep.reasoningEffort)
        assertEquals(
            ProviderReasoningStrength.DEEP,
            ProviderReasoningStrength.from(
                base.copy(
                    thinkingMode = ThinkingMode.ENABLED,
                    reasoningEffort = ReasoningEffort.DEFAULT,
                ),
            ),
        )
    }
}
