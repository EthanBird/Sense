package io.github.ethanbird.senseime.brain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CredentialEndpointScopeTest {
    @Test
    fun `LLM credential scope preserves case-sensitive tenant paths`() {
        assertNotEquals(
            CredentialEndpointScope.normalize("https://api.example/TenantA"),
            CredentialEndpointScope.normalize("https://api.example/tenanta"),
        )
    }

    @Test
    fun `speech credential scope preserves case-sensitive deployment paths`() {
        val openAi = "openai-compatible:" +
            CredentialEndpointScope.normalize("https://speech.example/DeployA/")
        val other = "openai-compatible:" +
            CredentialEndpointScope.normalize("https://speech.example/deploya")

        assertNotEquals(openAi, other)
    }

    @Test
    fun `scheme host default port and trailing slash are canonicalized`() {
        assertEquals(
            "https://api.example/v1",
            CredentialEndpointScope.normalize(" HTTPS://API.EXAMPLE:443/v1/ "),
        )
    }
}
