package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun `conversation round trip preserves unicode and separators`() {
        val messages = listOf(
            AgentHubMessage(AgentHubMessageRole.USER, "你好|终端\n运行 pwd", 10),
            AgentHubMessage(AgentHubMessageRole.ASSISTANT, "目录是 workspace ✅", 11),
        )

        assertEquals(
            messages,
            AgentConversationCodec.decode(AgentConversationCodec.encode(messages)),
        )
    }

    @Test
    fun `conversation decoder rejects unknown schema and blank payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationCodec.decode("sense.agent.conversation.v2\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationCodec.decode("sense.agent.conversation.v1\nUSER|1|")
        }
    }
}
