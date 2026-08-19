package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSubscriptionAuthStoreTest {
    @Test
    fun `two stores refresh a rotating token exactly once and both observe the replacement`() {
        val root = Files.createTempDirectory("sense-codex-refresh").toFile()
        val lockFile = File(root, "codex-refresh.lock")
        val initial = tokenBundle(
            accessToken = jwt("account-1", expiresAt = 1L),
            refreshToken = "refresh-0",
            expiresAt = 1L,
        )
        val refreshed = tokenBundle(
            accessToken = jwt("account-1", expiresAt = nowSeconds() + 3_600),
            refreshToken = "refresh-1",
            expiresAt = nowSeconds() + 3_600,
        )
        val vault = InMemoryVault(initial.toJson())
        val refreshCount = AtomicInteger()
        fun client() = CodexTokenRefresher { previous ->
            assertEquals("refresh-0", previous.refreshToken)
            refreshCount.incrementAndGet()
            Thread.sleep(120)
            refreshed
        }
        val firstStore = CodexSubscriptionAuthStore(lockFile, client(), vault)
        val secondStore = CodexSubscriptionAuthStore(lockFile, client(), vault)
        val start = CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<ProviderCredential.ChatGpt> {
                start.await(2, TimeUnit.SECONDS)
                checkNotNull(firstStore.credential().getOrThrow())
            }
            val second = workers.submit<ProviderCredential.ChatGpt> {
                start.await(2, TimeUnit.SECONDS)
                checkNotNull(secondStore.credential().getOrThrow())
            }

            val credentials = listOf(
                first.get(3, TimeUnit.SECONDS),
                second.get(3, TimeUnit.SECONDS),
            )

            assertEquals(1, refreshCount.get())
            assertTrue(credentials.all { it.accessToken == refreshed.accessToken })
            assertTrue(credentials.all { it.accountId == "account-1" })
            assertTrue(vault.current().contains("refresh-1"))
        } finally {
            workers.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun `browser login save is serialized after in flight refresh and becomes final credential`() {
        val root = Files.createTempDirectory("sense-codex-login-refresh").toFile()
        val lockFile = File(root, "codex-refresh.lock")
        val initial = tokenBundle(
            accountId = "account-old",
            accessToken = jwt("account-old", expiresAt = 1L),
            refreshToken = "refresh-old",
            expiresAt = 1L,
        )
        val refreshed = tokenBundle(
            accountId = "account-old",
            accessToken = jwt("account-old", expiresAt = nowSeconds() + 3_600),
            refreshToken = "refresh-rotated",
            expiresAt = nowSeconds() + 3_600,
        )
        val browserLogin = tokenBundle(
            accountId = "account-new",
            accessToken = jwt("account-new", expiresAt = nowSeconds() + 7_200),
            refreshToken = "refresh-new-login",
            expiresAt = nowSeconds() + 7_200,
        )
        val vault = InMemoryVault(initial.toJson())
        val refreshEntered = CountDownLatch(1)
        val allowRefresh = CountDownLatch(1)
        val refresher = CodexTokenRefresher { previous ->
            assertEquals("refresh-old", previous.refreshToken)
            refreshEntered.countDown()
            check(allowRefresh.await(2, TimeUnit.SECONDS))
            refreshed
        }
        val refreshingStore = CodexSubscriptionAuthStore(lockFile, refresher, vault)
        val loginStore = CodexSubscriptionAuthStore(lockFile, refresher, vault)
        val workers = Executors.newFixedThreadPool(2)

        try {
            val refresh = workers.submit<ProviderCredential.ChatGpt> {
                checkNotNull(refreshingStore.credential().getOrThrow())
            }
            assertTrue(refreshEntered.await(2, TimeUnit.SECONDS))
            val save = workers.submit<CodexAccountSummary> {
                loginStore.save(browserLogin).getOrThrow()
            }

            // The refresh owns the cross-process mutation lock, so login replacement cannot pass it.
            Thread.sleep(80)
            assertFalse(save.isDone)
            allowRefresh.countDown()

            assertEquals("account-old", refresh.get(2, TimeUnit.SECONDS).accountId)
            assertEquals("account-new", save.get(2, TimeUnit.SECONDS).accountId)
            val finalCredential = checkNotNull(loginStore.credential().getOrThrow())
            assertEquals("account-new", finalCredential.accountId)
            assertEquals(browserLogin.accessToken, finalCredential.accessToken)
            assertTrue(vault.current().contains("refresh-new-login"))
        } finally {
            allowRefresh.countDown()
            workers.shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun tokenBundle(
        accountId: String = "account-1",
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ) = CodexTokenBundle(
        idToken = jwt(accountId, expiresAt),
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = expiresAt,
    )

    private fun jwt(accountId: String, expiresAt: Long): String {
        val payload = """{"email":"sense@example.test","exp":$expiresAt,"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "e30.$encoded.signature"
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000

    private class InMemoryVault(initial: String) : ActionCredentialVault {
        private val value = AtomicReference(initial)

        override fun store(ref: ActionCredentialRef, secret: CharArray): Result<Unit> = runCatching {
            require(ref == CODEX_REF)
            try {
                value.set(secret.concatToString())
            } finally {
                secret.fill('\u0000')
            }
        }

        override fun lease(ref: ActionCredentialRef): Result<ActionCredentialMaterial?> = runCatching {
            require(ref == CODEX_REF)
            ActionCredentialMaterial(ref, value.get().toCharArray())
        }

        override fun revoke(handle: String): Result<Unit> = runCatching {
            require(handle == CODEX_REF.handle)
            value.set("")
        }

        fun current(): String = value.get()
    }

    private companion object {
        val CODEX_REF = ActionCredentialRef("codex.subscription", ActionSkillAuthMode.BEARER)
    }
}
