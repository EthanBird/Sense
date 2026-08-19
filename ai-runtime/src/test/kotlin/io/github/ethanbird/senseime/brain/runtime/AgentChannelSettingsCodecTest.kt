package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChannelSettingsCodecTest {
    @Test
    fun `settings codec round trips unicode bindings without credentials`() {
        val settings = AgentChannelSettings(
            revision = 18L,
            paused = true,
            telegram = TelegramAgentChannelSettings(
                enabled = true,
                pairingCode = "123456",
                pairingGeneration = 7L,
                boundPeerId = "用户-1",
                boundChatId = "chat:-10001",
            ),
            feishu = FeishuAgentChannelSettings(
                enabled = true,
                appId = "cli_abc",
                domain = FeishuDomain.LARK,
                pairingCode = "654321",
                pairingGeneration = 9L,
                boundPeerId = "ou_user",
                boundChatId = "oc_chat",
            ),
        )
        assertEquals(settings, AgentChannelSettingsCodec.decode(AgentChannelSettingsCodec.encode(settings)))
    }

    @Test
    fun `v1 settings migrate pairing generations`() {
        val v1 = """
            sense.agent.channels.v1
            true
            MTIzNDU2
            dXNlcg
            Y2hhdA
            false
            Y2xpX2FiYw
            FEISHU
            NjU0MzIx
            ~
            ~
        """.trimIndent()
        val decoded = AgentChannelSettingsCodec.decode(v1)
        assertEquals(1L, decoded.telegram.pairingGeneration)
        assertEquals(1L, decoded.feishu.pairingGeneration)
    }

    @Test
    fun `compare and bind rejects stale pairing generation`() {
        val current = AgentChannelSettings(
            telegram = TelegramAgentChannelSettings(
                pairingCode = "123456",
                pairingGeneration = 4L,
            ),
        )
        val source = AgentChannelSource(
            channel = AgentChannelType.TELEGRAM,
            chatId = "chat",
            peerId = "peer",
            messageId = "message",
        )
        assertEquals(
            null,
            AgentChannelBindingTransition.compareAndBind(current, source, "123456", 3L),
        )
        val bound = AgentChannelBindingTransition.compareAndBind(current, source, "123456", 4L)
        assertEquals("peer", bound?.telegram?.boundPeerId)
        assertEquals("chat", bound?.telegram?.boundChatId)
    }

    @Test
    fun `pairing reset generation invalidates stale coordinator snapshot`() {
        val cached = AgentChannelSettings(
            telegram = TelegramAgentChannelSettings(
                pairingCode = "123456",
                pairingGeneration = 4L,
                boundPeerId = "old-peer",
                boundChatId = "old-chat",
            ),
        )
        val reset = cached.copy(
            telegram = cached.telegram.copy(
                pairingCode = "654321",
                pairingGeneration = 5L,
                boundPeerId = "",
                boundChatId = "",
            ),
        )
        assertTrue(
            !AgentChannelPairingGeneration.isCurrent(
                cached,
                reset,
                AgentChannelType.TELEGRAM,
            ),
        )
        assertTrue(
            AgentChannelPairingGeneration.isCurrent(
                reset,
                reset,
                AgentChannelType.TELEGRAM,
            ),
        )
    }

    @Test
    fun `paused channel lifecycle and config revision reload are explicit`() {
        val paused = AgentChannelSettings(
            revision = 9L,
            paused = true,
            telegram = TelegramAgentChannelSettings(
                enabled = true,
                pairingCode = "123456",
            ),
        )
        assertTrue(paused.anyEnabled)
        assertTrue(!paused.shouldRun)
        assertTrue(AgentChannelConfigReloadPolicy.shouldReload(8L, paused.revision))
        assertTrue(!AgentChannelConfigReloadPolicy.shouldReload(paused.revision, paused.revision))
        assertTrue(!AgentChannelConfigReloadPolicy.shouldReload(paused.revision, 0L))
    }

    @Test
    fun `runtime config rejects stored malformed telegram credential`() {
        val result = runCatching {
            AgentChannelRuntimeConfig(
                settings = AgentChannelSettings(
                    telegram = TelegramAgentChannelSettings(
                        enabled = true,
                        pairingCode = "123456",
                    ),
                ),
                telegramBotToken = "malformed",
                feishuAppSecret = null,
            ).requireReady()
        }
        assertTrue(result.isFailure)
    }
}
