package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolId
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
        )

        assertTrue(settings.webSearchEnabled)
        assertFalse(settings.webFetchEnabled)
        assertTrue(settings.enabledToolIds().isEmpty())
        assertEquals(
            setOf(
                AgentToolId.WEB_SEARCH,
                AgentToolId.CALCULATOR,
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
        )

        assertEquals(
            setOf(
                AgentToolId.WEB_FETCH,
                AgentToolId.MEMORY_SEARCH,
            ),
            settings.enabledToolIds(),
        )
    }

    @Test
    fun `codec round trips every switch combination`() {
        for (mask in 0 until 32) {
            val settings = AgentToolSettings(
                masterEnabled = mask and 1 != 0,
                webSearchEnabled = mask and 2 != 0,
                webFetchEnabled = mask and 4 != 0,
                calculatorEnabled = mask and 8 != 0,
                memorySearchEnabled = mask and 16 != 0,
            )

            assertEquals(
                "mask=$mask",
                settings,
                AgentToolSettingsCodec.decode(AgentToolSettingsCodec.encode(settings)),
            )
        }
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
            AgentToolSettingsCodec.decode(valid.replace("schema_version=1", "schema_version=2"))
        }
    }
}
