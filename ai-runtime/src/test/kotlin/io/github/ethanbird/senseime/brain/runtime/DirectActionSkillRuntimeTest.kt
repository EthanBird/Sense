package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.ActionSkillInvocation
import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import io.github.ethanbird.senseime.brain.api.ActionSkillDescriptor
import io.github.ethanbird.senseime.brain.api.ActionSkillResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectActionSkillRuntimeTest {
    @Test
    fun `XAUUSD action returns a complete quote without touching a model transport`() {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val runtime = DirectActionSkillRuntime.builtIns(
            loader = ActionHttpLoader { url, headers ->
                requests += url to headers
                """{
                    "currency":"USD",
                    "name":"Gold",
                    "price":4343.299805,
                    "symbol":"XAU",
                    "updatedAt":"2026-08-08T18:32:22Z"
                }""".trimIndent()
            },
        )

        val result = runtime.execute(
            ActionSkillInvocation("action-request-1", XauUsdActionSkill.SKILL_ID),
        ).getOrThrow()

        assertEquals(
            listOf(XauUsdActionSkill.ENDPOINT to emptyMap<String, String>()),
            requests,
        )
        assertEquals("\$4,343.30 / oz", result.primaryValue)
        assertTrue(result.insertText.contains("XAUUSD 现价：\$4,343.30/oz"))
        assertEquals("direct_zero_model_token", result.attributes["execution_mode"])
        assertEquals(0, runtime.descriptors().single().credentialHandle?.length ?: 0)
    }

    @Test
    fun `quote connector rejects a response for another instrument`() {
        val runtime = DirectActionSkillRuntime.builtIns(
            loader = ActionHttpLoader { _, _ ->
                """{"currency":"USD","price":1.2,"symbol":"BTC","updatedAt":"2026-08-08T18:32:22Z"}"""
            },
        )

        val failure = runtime.execute(
            ActionSkillInvocation("action-request-2", XauUsdActionSkill.SKILL_ID),
        ).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("symbol"))
    }

    @Test
    fun `authenticated action receives only its opaque credential lease`() {
        val ref = ActionCredentialRef("fixture.token", ActionSkillAuthMode.BEARER)
        val vault = object : ActionCredentialVault {
            override fun store(ref: ActionCredentialRef, secret: CharArray) = Result.success(Unit)
            override fun revoke(handle: String) = Result.success(Unit)
            override fun lease(ref: ActionCredentialRef) = Result.success(
                ActionCredentialMaterial(ref, "abc123".toCharArray()),
            )
        }
        val skill = object : DirectActionSkill {
            override val descriptor = ActionSkillDescriptor(
                id = "fixture.auth",
                displayName = "认证测试",
                description = "测试凭据句柄租约",
                authMode = ActionSkillAuthMode.BEARER,
                credentialHandle = ref.handle,
            )

            override fun execute(
                invocation: ActionSkillInvocation,
                credential: ActionCredentialMaterial?,
            ) = ActionSkillResult(
                requestId = invocation.requestId,
                skillId = descriptor.id,
                title = "认证完成",
                primaryValue = checkNotNull(credential).requestHeaders().getValue("Authorization"),
                secondaryValue = "",
                insertText = "认证完成",
                sourceLabel = "fixture",
                sourceUrl = "https://fixture.invalid",
                observedAtEpochMs = 1L,
            )
        }

        val result = DirectActionSkillRuntime(listOf(skill), vault).execute(
            ActionSkillInvocation("auth-request", skill.descriptor.id),
        ).getOrThrow()

        assertEquals("Bearer abc123", result.primaryValue)
    }
}
