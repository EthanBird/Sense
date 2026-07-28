package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.AiBrainEngine
import io.github.ethanbird.senseime.brain.api.AgentSkillSummary
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCall
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderStreamSink
import io.github.ethanbird.senseime.brain.api.ProviderTransport
import io.github.ethanbird.senseime.brain.api.ProviderWireRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSettingsStoreTest {
    @Test
    fun `defaults allow every built-in tool`() {
        val settings = AgentToolSettings()

        assertTrue(settings.masterEnabled)
        assertEquals(
            setOf(
                AgentToolId.WEB_SEARCH,
                AgentToolId.WEB_FETCH,
                AgentToolId.CALCULATOR,
                AgentToolId.MEMORY_SEARCH,
                AgentToolId.SKILL_READ,
                AgentToolId.SKILL_MANAGE,
            ),
            settings.enabledToolIds(),
        )
    }

    @Test
    fun `master switch suppresses tools without changing individual choices`() {
        val settings = AgentToolSettings(
            masterEnabled = false,
            webSearchEnabled = true,
            webFetchEnabled = false,
            calculatorEnabled = true,
            memorySearchEnabled = false,
            skillReadEnabled = true,
            skillManageEnabled = false,
        )

        assertTrue(settings.webSearchEnabled)
        assertFalse(settings.webFetchEnabled)
        assertTrue(settings.enabledToolIds().isEmpty())
        assertEquals(
            setOf(
                AgentToolId.WEB_SEARCH,
                AgentToolId.CALCULATOR,
                AgentToolId.SKILL_READ,
            ),
            settings.copy(masterEnabled = true).enabledToolIds(),
        )
    }

    @Test
    fun `individual choices produce an exact allow-list`() {
        val settings = AgentToolSettings(
            webSearchEnabled = false,
            webFetchEnabled = true,
            calculatorEnabled = false,
            memorySearchEnabled = true,
            skillReadEnabled = false,
            skillManageEnabled = true,
        )

        assertEquals(
            setOf(
                AgentToolId.WEB_FETCH,
                AgentToolId.MEMORY_SEARCH,
                AgentToolId.SKILL_MANAGE,
            ),
            settings.enabledToolIds(),
        )
    }

    @Test
    fun `codec round trips every switch combination`() {
        for (mask in 0 until 128) {
            val settings = AgentToolSettings(
                masterEnabled = mask and 1 != 0,
                webSearchEnabled = mask and 2 != 0,
                webFetchEnabled = mask and 4 != 0,
                calculatorEnabled = mask and 8 != 0,
                memorySearchEnabled = mask and 16 != 0,
                skillReadEnabled = mask and 32 != 0,
                skillManageEnabled = mask and 64 != 0,
            )

            assertEquals(
                "mask=$mask",
                settings,
                AgentToolSettingsCodec.decode(AgentToolSettingsCodec.encode(settings)),
            )
        }
    }

    @Test
    fun `legacy schema migrates both Skill tools to enabled`() {
        val legacy = """
            schema_version=1
            master_enabled=true
            web_search_enabled=false
            web_fetch_enabled=true
            calculator_enabled=false
            memory_search_enabled=true
        """.trimIndent()

        val decoded = AgentToolSettingsCodec.decode(legacy)

        assertTrue(decoded.skillReadEnabled)
        assertTrue(decoded.skillManageEnabled)
        assertEquals(
            setOf(
                AgentToolId.WEB_FETCH,
                AgentToolId.MEMORY_SEARCH,
                AgentToolId.SKILL_READ,
                AgentToolId.SKILL_MANAGE,
            ),
            decoded.enabledToolIds(),
        )
        assertTrue(AgentToolSettingsCodec.encode(decoded).startsWith("schema_version=2\n"))
        val legacyMasterOff = AgentToolSettingsCodec.decode(
            legacy.replace("master_enabled=true", "master_enabled=false"),
        )
        assertTrue(legacyMasterOff.skillReadEnabled)
        assertTrue(legacyMasterOff.skillManageEnabled)
        assertTrue(legacyMasterOff.enabledToolIds().isEmpty())
    }

    @Test
    fun `Brain admission freezes exact settings without appending Skill tools`() {
        val noSkills = AgentToolSettings(
            webSearchEnabled = true,
            webFetchEnabled = false,
            calculatorEnabled = false,
            memorySearchEnabled = false,
            skillReadEnabled = false,
            skillManageEnabled = false,
        )
        val frozen = AgentToolRunAdmission.freeze(noSkills)

        assertEquals(setOf(AgentToolId.WEB_SEARCH), frozen)
        assertEquals(
            emptySet<AgentToolId>(),
            AgentToolRunAdmission.freeze(noSkills.copy(masterEnabled = false)),
        )
        assertEquals(setOf(AgentToolId.WEB_SEARCH), frozen)
    }

    @Test
    fun `Brain admission controls final Skill schemas while active Skill remains injected`() {
        val profiles = listOf(
            provider("responses", ProviderApiStyle.OPENAI_RESPONSES),
            provider("chat", ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
            ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
            ),
        )
        val masterOff = AgentToolSettings(masterEnabled = false)
        val individualSkillsOff = AgentToolSettings(
            skillReadEnabled = false,
            skillManageEnabled = false,
        )

        profiles.forEach { profile ->
            listOf(masterOff, individualSkillsOff).forEach { settings ->
                val body = firstProviderBody(profile, settings)
                assertFalse("${profile.id} exposed skill_read", body.hasToolSchema("skill_read"))
                assertFalse("${profile.id} exposed skill_manage", body.hasToolSchema("skill_manage"))
                assertTrue(
                    "${profile.id} dropped the directly selected Skill",
                    body.contains(ACTIVE_SKILL_SENTINEL),
                )
            }
        }

        val onlyManage = firstProviderBody(
            profiles.first(),
            AgentToolSettings(skillReadEnabled = false, skillManageEnabled = true),
        )
        assertFalse(onlyManage.hasToolSchema("skill_read"))
        assertTrue(onlyManage.hasToolSchema("skill_manage"))

        val onlyRead = firstProviderBody(
            profiles.first(),
            AgentToolSettings(skillReadEnabled = true, skillManageEnabled = false),
        )
        assertTrue(onlyRead.hasToolSchema("skill_read"))
        assertFalse(onlyRead.hasToolSchema("skill_manage"))
    }

    @Test
    fun `codec rejects incomplete duplicated unknown and malformed settings`() {
        val valid = AgentToolSettingsCodec.encode(AgentToolSettings())

        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSettingsCodec.decode(valid.replace("calculator_enabled=true\n", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSettingsCodec.decode("$valid\nmaster_enabled=false\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSettingsCodec.decode("$valid\nfuture_tool_enabled=true\n")
        }
        assertThrows(IllegalStateException::class.java) {
            AgentToolSettingsCodec.decode(valid.replace("web_fetch_enabled=true", "web_fetch_enabled=yes"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSettingsCodec.decode(valid.replace("schema_version=2", "schema_version=3"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSettingsCodec.decode(
                valid.replace("skill_manage_enabled=true\n", ""),
            )
        }
    }

    private fun firstProviderBody(
        profile: ProviderProfile,
        settings: AgentToolSettings,
    ): String {
        val transport = CapturingTransport()
        val request = harness().also { ProtocolValidator.validate(it).requireValid() }
        val handle = AiBrainEngine(transport).start(
            BrainRunSpec(
                harnessRequest = request,
                provider = profile,
                credential = ProviderCredential.None,
                skillCatalog = listOf(
                    AgentSkillSummary(
                        id = "brief",
                        revision = 3,
                        name = "Brief",
                        description = "Create a concise brief",
                    ),
                ),
                skillCatalogGeneration = SKILL_CATALOG_GENERATION,
                enabledTools = AgentToolRunAdmission.freeze(settings),
            ),
            sink = {},
        )
        val wireRequest = transport.awaitRequest()
        handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
        return wireRequest.body.toString(StandardCharsets.UTF_8)
    }

    private fun String.hasToolSchema(name: String): Boolean =
        contains("\"name\":\"$name\"")

    private fun provider(id: String, apiStyle: ProviderApiStyle) = ProviderProfile(
        id = id,
        displayName = id,
        apiStyle = apiStyle,
        baseUrl = "https://provider.test/v1",
        model = "test-model",
    )

    private fun harness(): HarnessRequestV1 {
        val text = "原始内容"
        return HarnessRequestV1(
            requestId = "tool-settings-request",
            runGeneration = 1,
            skill = EditorIntent.REWRITE,
            skillCatalogGeneration = SKILL_CATALOG_GENERATION,
            activeSkill = ActiveSkillInstructionV1(
                id = "brief",
                revision = 3,
                catalogGeneration = SKILL_CATALOG_GENERATION,
                name = "Brief",
                description = "Create a concise brief",
                content = "# Brief\n$ACTIVE_SKILL_SENTINEL",
            ),
            snapshot = EditorSnapshotV1(
                requestId = "tool-settings-request",
                snapshotId = "tool-settings-snapshot",
                editorGeneration = 1,
                fieldIdentity = "tool-settings-field",
                capability = SnapshotCapability.FULL_DOCUMENT,
                text = text,
                selection = TextSelectionV1(text.length, text.length),
                target = PatchTarget.WHOLE_FIELD,
                baseSha256 = EditorTextDigest.sha256Utf8(text),
                capturedAtMonotonicMs = 1,
                truncated = false,
            ),
        )
    }

    private class CapturingTransport : ProviderTransport {
        private val requestOpened = CountDownLatch(1)
        lateinit var request: ProviderWireRequest

        override fun open(
            request: ProviderWireRequest,
            sink: ProviderStreamSink,
        ): ProviderCall {
            check(!::request.isInitialized)
            this.request = request
            requestOpened.countDown()
            return ProviderCall {}
        }

        fun awaitRequest(): ProviderWireRequest {
            check(requestOpened.await(5, TimeUnit.SECONDS)) {
                "Brain did not open a Provider request"
            }
            return request
        }
    }

    private companion object {
        const val SKILL_CATALOG_GENERATION = 9L
        const val ACTIVE_SKILL_SENTINEL = "ACTIVE_SKILL_DIRECT_INJECTION_4M8Q"
    }
}
