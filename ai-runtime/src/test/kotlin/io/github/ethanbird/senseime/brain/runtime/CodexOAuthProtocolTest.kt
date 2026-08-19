package io.github.ethanbird.senseime.brain.runtime

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexOAuthProtocolTest {
    @Test
    fun `pkce challenge matches RFC 7636 vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CodexOAuthProtocol.codeChallenge(verifier),
        )
    }

    @Test
    fun `authorization URL matches Codex and jcode browser flow`() {
        val redirect = "http://localhost:1455/auth/callback"
        val url = URI(CodexOAuthProtocol.authorizationUrl(redirect, "challenge", "state-value"))
        val query = decodeQuery(url.rawQuery)

        assertEquals("https", url.scheme)
        assertEquals("auth.openai.com", url.host)
        assertEquals("/oauth/authorize", url.path)
        assertEquals("code", query["response_type"])
        assertEquals(CodexOAuthProtocol.CLIENT_ID, query["client_id"])
        assertEquals(redirect, query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("challenge", query["code_challenge"])
        assertEquals("state-value", query["state"])
        assertEquals("true", query["id_token_add_organizations"])
        assertEquals("true", query["codex_cli_simplified_flow"])
        assertEquals("codex_cli_rs", query["originator"])
        assertEquals("login", query["prompt"])
        assertTrue(query.getValue("scope").contains("offline_access"))
        assertTrue(query.getValue("scope").contains("api.connectors.invoke"))
        assertFalse(url.toString().contains("code_verifier"))
    }

    @Test
    fun `callback parser decodes code and requires state`() {
        val callback = CodexOAuthProtocol.parseCallback("code=abc%2B123&state=new%20state")

        assertEquals(OAuthCallback.Code("abc+123", "new state"), callback)
        assertTrue(CodexOAuthProtocol.parseCallback("code=abc") is OAuthCallback.Invalid)
        assertEquals(
            OAuthCallback.Cancelled("new state"),
            CodexOAuthProtocol.parseCallback("error=access_denied&state=new%20state"),
        )
        assertEquals("cancel-state", CodexOAuthProtocol.callbackState("state=cancel-state"))
    }

    @Test
    fun `loopback rejects stale state then completes with readable success page`() {
        val listener = loopbackListener()
        val session = CodexOAuthSession(
            authorizationUrl = "https://auth.openai.com/oauth/authorize?redacted=true",
            redirectUri = "http://localhost:${listener.localPort}/auth/callback",
            verifier = "v".repeat(43),
            expectedState = "latest-state",
            listener = listener,
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<CodexTokenBundle> {
                session.complete({ false }) { code, verifier, redirectUri ->
                    assertEquals("fresh-code", code)
                    assertEquals("v".repeat(43), verifier)
                    assertEquals("http://localhost:${listener.localPort}/auth/callback", redirectUri)
                    CodexTokenBundle("id", "access", "refresh")
                }
            }

            val stale = request(listener.localPort, "/auth/callback?code=stale&state=old-state")
            assertTrue(stale.startsWith("HTTP/1.1 400"))
            val missingErrorState = request(listener.localPort, "/auth/callback?error=access_denied")
            assertTrue(missingErrorState.startsWith("HTTP/1.1 400"))
            val staleErrorState = request(
                listener.localPort,
                "/auth/callback?error=access_denied&state=old-state",
            )
            assertTrue(staleErrorState.startsWith("HTTP/1.1 400"))
            val staleCancel = request(listener.localPort, "/cancel?state=old-state")
            assertTrue(staleCancel.startsWith("HTTP/1.1 400"))

            val success = request(
                listener.localPort,
                "/auth/callback?code=fresh-code&state=latest-state",
            )
            assertTrue(success.startsWith("HTTP/1.1 200"))
            assertTrue(success.contains("登录完成"))
            assertEquals("access", result.get(2, TimeUnit.SECONDS).accessToken)
        } finally {
            session.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun `slow malformed socket does not terminate callback session`() {
        val listener = loopbackListener()
        val session = CodexOAuthSession(
            authorizationUrl = "https://auth.openai.com/oauth/authorize?redacted=true",
            redirectUri = "http://localhost:${listener.localPort}/auth/callback",
            verifier = "v".repeat(43),
            expectedState = "latest-state",
            listener = listener,
            socketReadTimeoutMs = 100,
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<CodexTokenBundle> {
                session.complete({ false }) { code, _, _ ->
                    assertEquals("valid-after-slow-client", code)
                    CodexTokenBundle("id", "access", "refresh")
                }
            }

            Socket().use { slowSocket ->
                slowSocket.connect(InetSocketAddress("127.0.0.1", listener.localPort), 1_000)
                slowSocket.getOutputStream().write("GET /auth/call".toByteArray(StandardCharsets.US_ASCII))
                slowSocket.getOutputStream().flush()
                Thread.sleep(180)
            }

            val success = request(
                listener.localPort,
                "/auth/callback?code=valid-after-slow-client&state=latest-state",
            )
            assertTrue(success.startsWith("HTTP/1.1 200"))
            assertEquals("access", result.get(2, TimeUnit.SECONDS).accessToken)
        } finally {
            session.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun `cancel endpoint ends only the session whose state matches`() {
        val listener = loopbackListener()
        val session = CodexOAuthSession(
            authorizationUrl = "https://auth.openai.com/oauth/authorize?redacted=true",
            redirectUri = "http://localhost:${listener.localPort}/auth/callback",
            verifier = "v".repeat(43),
            expectedState = "cancel-state",
            listener = listener,
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<CodexTokenBundle> {
                session.complete({ false }) { _, _, _ -> error("exchange must not run") }
            }

            val response = request(listener.localPort, "/cancel?state=cancel-state")

            assertTrue(response.startsWith("HTTP/1.1 400"))
            val failure = runCatching { result.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(failure is ExecutionException)
            assertTrue(failure?.cause?.message.orEmpty().contains("cancelled"))
        } finally {
            session.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun `closing a session releases its loopback port`() {
        val listener = loopbackListener()
        val port = listener.localPort
        val session = CodexOAuthSession(
            authorizationUrl = "https://auth.openai.com/oauth/authorize?redacted=true",
            redirectUri = "http://localhost:$port/auth/callback",
            verifier = "v".repeat(43),
            expectedState = "state",
            listener = listener,
        )

        session.cancel()

        ServerSocket().use { rebound ->
            rebound.reuseAddress = true
            rebound.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            assertEquals(port, rebound.localPort)
        }
    }

    private fun loopbackListener(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        soTimeout = 1_000
    }

    private fun request(port: Int, target: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        socket.soTimeout = 2_000
        socket.getOutputStream().write(
            "GET $target HTTP/1.1\r\nHost: localhost:$port\r\nConnection: close\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII),
        )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }

    private fun decodeQuery(rawQuery: String): Map<String, String> = rawQuery.split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
