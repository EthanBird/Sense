package io.github.ethanbird.senseime.brain.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentTerminalWorkspacePolicyTest {
    @Test
    fun `session tokens are stable and separated`() {
        assertEquals(
            AgentTerminalWorkspacePolicy.token("session-a"),
            AgentTerminalWorkspacePolicy.token("session-a"),
        )
        assertNotEquals(
            AgentTerminalWorkspacePolicy.token("session-a"),
            AgentTerminalWorkspacePolicy.token("session-b"),
        )
    }

    @Test
    fun `cwd stays below workspace`() {
        val root = Files.createTempDirectory("sense-terminal").toFile()
        assertEquals(
            root.resolve("nested").canonicalFile,
            AgentTerminalWorkspacePolicy.resolve(root, "nested"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentTerminalWorkspacePolicy.resolve(root, "../outside")
        }
    }
}
