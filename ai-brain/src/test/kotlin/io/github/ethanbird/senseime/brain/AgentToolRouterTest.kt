package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentBrowserAction
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRouterTest {
    @Test
    fun defaultPagingBudgetCanReadOneMaximumSkillCompletely() {
        val pages = (
            AgentSkillPolicy.MAX_CONTENT_CHARS +
                AgentToolArguments.SkillRead.DEFAULT_MAX_CHARS - 1
            ) / AgentToolArguments.SkillRead.DEFAULT_MAX_CHARS

        assertTrue(pages <= AgentToolRouter.MAX_TOOL_TURNS)
    }

    @Test
    fun skillReadRequiresExactRevisionAndUsesBoundedPaging() {
        val call = decode(
            AgentToolId.SKILL_READ,
            """{"skill_id":"rewrite.concise","revision":7,"offset":4096,"max_chars":2048}""",
        )

        assertEquals(
            AgentToolArguments.SkillRead(
                skillId = "rewrite.concise",
                revision = 7,
                offset = 4096,
                maxChars = 2048,
            ),
            call.arguments,
        )
        assertThrows(ProviderPayloadException::class.java) {
            decode(
                AgentToolId.SKILL_READ,
                """{"skill_id":"rewrite.concise"}""",
            )
        }
        assertThrows(ProviderPayloadException::class.java) {
            decode(
                AgentToolId.SKILL_READ,
                """{"skill_id":"rewrite.concise","revision":7,"max_chars":6001}""",
            )
        }
    }

    @Test
    fun createCanRemainUnboundOrAtomicallyBindOneDirection() {
        val unbound = decode(
            AgentToolId.SKILL_MANAGE,
            """
                {
                  "operation":"create",
                  "expected_catalog_generation":7,
                  "skill_id":"brief",
                  "name":"简报",
                  "description":"把输入整理成简洁简报",
                  "content":"# 简报\n保留事实。",
                  "base_intent":"format"
                }
            """.trimIndent(),
        ).arguments as AgentToolArguments.SkillManage.Create
        assertEquals(EditorIntent.FORMAT, unbound.baseIntent)
        assertNull(unbound.binding)

        val bound = decode(
            AgentToolId.SKILL_MANAGE,
            """
                {
                  "operation":"create",
                  "expected_catalog_generation":7,
                  "skill_id":"translate.ja",
                  "name":"日语",
                  "description":"翻译成自然日语",
                  "content":"翻译为自然日语。",
                  "key_code":116,
                  "direction":"up"
                }
            """.trimIndent(),
        ).arguments as AgentToolArguments.SkillManage.Create
        assertEquals(116, bound.binding?.keyCode)
        assertEquals(AgentSkillDirection.UP, bound.binding?.direction)
        assertEquals(7L, bound.expectedCatalogGeneration)
    }

    @Test
    fun updateRequiresAtLeastOneChangedField() {
        assertThrows(ProviderPayloadException::class.java) {
            decode(
                AgentToolId.SKILL_MANAGE,
                """{"operation":"update","expected_catalog_generation":7,"skill_id":"brief"}""",
            )
        }
        val update = decode(
            AgentToolId.SKILL_MANAGE,
            """
                {
                  "operation":"update",
                  "expected_catalog_generation":7,
                  "skill_id":"brief",
                  "description":"新的短描述",
                  "base_intent":"rewrite"
                }
            """.trimIndent(),
        ).arguments as AgentToolArguments.SkillManage.Update
        assertEquals("新的短描述", update.description)
        assertEquals(EditorIntent.REWRITE, update.baseIntent)
    }

    @Test
    fun bindingsRequireCompleteValidSlotAndRunnableIntent() {
        assertThrows(ProviderPayloadException::class.java) {
            decode(
                AgentToolId.SKILL_MANAGE,
                """
                    {
                      "operation":"create",
                      "expected_catalog_generation":7,
                      "skill_id":"brief",
                      "name":"简报",
                      "description":"简报",
                      "content":"生成简报",
                      "key_code":65
                    }
                """.trimIndent(),
            )
        }
        assertThrows(ProviderPayloadException::class.java) {
            decode(
                AgentToolId.SKILL_MANAGE,
                """
                    {
                      "operation":"update",
                      "expected_catalog_generation":7,
                      "skill_id":"brief",
                      "base_intent":"no_change"
                    }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun skillManagementRejectsMissingOrNonPositiveCatalogGeneration() {
        listOf(
            """{"operation":"unbind","key_code":97,"direction":"up"}""",
            """
                {"operation":"unbind","expected_catalog_generation":0,
                "key_code":97,"direction":"up"}
            """.trimIndent(),
        ).forEach { document ->
            assertThrows(ProviderPayloadException::class.java) {
                decode(AgentToolId.SKILL_MANAGE, document)
            }
        }
    }

    @Test
    fun disabledSkillToolCannotBeGuessedByTheModel() {
        assertThrows(ProviderPayloadException::class.java) {
            AgentToolRouter.decode(
                callId = "call-1",
                toolName = AgentToolId.SKILL_READ.wireValue,
                argumentsDocument = """{"skill_id":"rewrite","revision":1}""",
                enabledTools = emptySet(),
            )
        }
    }

    @Test
    fun terminalExecIsBoundedAndRetainsSessionScope() {
        val call = AgentToolRouter.decode(
            callId = "terminal-1",
            toolName = AgentToolId.TERMINAL_EXEC.wireValue,
            argumentsDocument =
                """{"command":"printf 'hello'","cwd":"project","timeout_ms":5000}""",
            enabledTools = setOf(AgentToolId.TERMINAL_EXEC),
            requestId = "request-1",
            runGeneration = 2,
            sessionId = "sense.agent-hub.default",
        )

        assertEquals("sense.agent-hub.default", call.sessionId)
        assertEquals(
            AgentToolArguments.TerminalExec(
                command = "printf 'hello'",
                cwd = "project",
                timeoutMs = 5_000,
            ),
            call.arguments,
        )
        assertThrows(ProviderPayloadException::class.java) {
            decode(AgentToolId.TERMINAL_EXEC, """{"command":"pwd","timeout_ms":999}""")
        }
    }

    @Test
    fun browserUseEnforcesActionSpecificArguments() {
        val type = decode(
            AgentToolId.BROWSER_USE,
            """{"action":"type","ref":4,"text":"Sense","submit":true}""",
        ).arguments as AgentToolArguments.BrowserUse
        assertEquals(AgentBrowserAction.TYPE, type.action)
        assertEquals(4, type.ref)
        assertEquals("Sense", type.text)
        assertTrue(type.submit)

        val navigate = decode(
            AgentToolId.BROWSER_USE,
            """{"action":"navigate","url":"https://example.com/path"}""",
        ).arguments as AgentToolArguments.BrowserUse
        assertEquals("https://example.com/path", navigate.url)

        listOf(
            """{"action":"navigate"}""",
            """{"action":"click"}""",
            """{"action":"snapshot","url":"https://example.com"}""",
            """{"action":"navigate","url":"file:///tmp/page"}""",
        ).forEach { document ->
            assertThrows(ProviderPayloadException::class.java) {
                decode(AgentToolId.BROWSER_USE, document)
            }
        }
    }

    private fun decode(
        tool: AgentToolId,
        document: String,
    ) = AgentToolRouter.decode(
        callId = "call-1",
        toolName = tool.wireValue,
        argumentsDocument = document,
        enabledTools = setOf(tool),
    )
}
