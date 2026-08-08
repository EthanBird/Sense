package io.github.ethanbird.senseime.brain.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import io.github.ethanbird.senseime.brain.api.ActionSkillInvocation
import io.github.ethanbird.senseime.brain.api.ActionSkillResult
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionMemoryInstrumentedTest {
    @Test
    fun directActionRawResultIsSearchableAcrossStoreInstances() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val marker = "xauusd-${UUID.randomUUID()}"
        val requestId = UUID.randomUUID().toString()
        val store = ActionHistoryStore(context)
        store.appendStarted(
            ActionSkillInvocation(requestId, XauUsdActionSkill.SKILL_ID),
            System.currentTimeMillis(),
        ).getOrThrow()
        store.appendSucceeded(
            ActionSkillResult(
                requestId = requestId,
                skillId = XauUsdActionSkill.SKILL_ID,
                title = "XAUUSD · 现货黄金",
                primaryValue = "\$4,343.30 / oz",
                secondaryValue = "USD",
                insertText = "$marker 黄金现价",
                sourceLabel = "fixture",
                sourceUrl = "https://fixture.invalid/XAU",
                observedAtEpochMs = System.currentTimeMillis(),
                rawPayload = "{\"marker\":\"$marker\"}",
            ),
        ).getOrThrow()

        val recalled = ActionHistoryStore(context).search(marker, 4)
        assertTrue(recalled.hits.any { it.requestId == requestId && it.text.contains(marker) })
        assertTrue(recalled.scannedRecords >= 1)
    }

    @Test
    fun credentialVaultLeasesOpaqueSecretAndRevokesHandle() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = AndroidActionCredentialVault(context)
        val ref = ActionCredentialRef(
            handle = "test.${UUID.randomUUID()}",
            authMode = ActionSkillAuthMode.BEARER,
        )
        val original = "secret-${UUID.randomUUID()}".toCharArray()
        val expected = original.concatToString()
        vault.store(ref, original).getOrThrow()
        assertTrue(original.all { it == '\u0000' })

        vault.lease(ref).getOrThrow().use { lease ->
            assertEquals(expected, lease?.copySecret()?.concatToString())
        }
        vault.revoke(ref.handle).getOrThrow()
        assertNull(vault.lease(ref).getOrThrow())
    }
}
