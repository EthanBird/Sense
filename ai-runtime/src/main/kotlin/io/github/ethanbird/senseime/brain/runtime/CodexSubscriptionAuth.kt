package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class CodexAccountSummary(
    val accountId: String,
    val email: String?,
)

data class CodexTokenBundle(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long = 0L,
) {
    fun toJson(): String = JSONObject()
        .put("id_token", idToken)
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAtEpochSeconds)
        .toString()

    companion object {
        fun fromJson(value: String) = JSONObject(value).let {
            CodexTokenBundle(
                idToken = it.getString("id_token"),
                accessToken = it.getString("access_token"),
                refreshToken = it.getString("refresh_token"),
                expiresAtEpochSeconds = it.optLong("expires_at", 0L),
            )
        }
    }
}

/**
 * One browser Authorization Code + PKCE attempt.
 *
 * The listener is bound before [authorizationUrl] is exposed, so Chrome can redirect immediately.
 * The verifier, state and callback code stay in memory and are never included in exceptions or
 * persisted outside the encrypted subscription bundle.
 */
class CodexOAuthSession internal constructor(
    val authorizationUrl: String,
    internal val redirectUri: String,
    internal val verifier: String,
    internal val expectedState: String,
    private val listener: ServerSocket,
    private val socketReadTimeoutMs: Int = DEFAULT_SOCKET_READ_TIMEOUT_MS,
) : AutoCloseable {
    private val cancelled = AtomicBoolean(false)

    internal fun complete(
        externalCancellation: () -> Boolean,
        exchange: (code: String, verifier: String, redirectUri: String) -> CodexTokenBundle,
    ): CodexTokenBundle {
        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            ensureActive(externalCancellation)
            val socket = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: SocketException) {
                ensureActive(externalCancellation)
                throw error
            }
            socket.use { connection ->
                val callback = try {
                    readCallback(connection)
                } catch (_: IOException) {
                    null
                } ?: return@use
                when (callback) {
                    is OAuthCallback.Cancelled -> {
                        if (!constantTimeEquals(expectedState, callback.state)) {
                            writePage(connection, 400, STALE_PAGE)
                            return@use
                        }
                        writePage(connection, 400, CANCELLED_PAGE)
                        error("Codex OAuth login cancelled")
                    }
                    OAuthCallback.Invalid -> writePage(connection, 400, INVALID_PAGE)
                    is OAuthCallback.Code -> {
                        if (!constantTimeEquals(expectedState, callback.state)) {
                            writePage(connection, 400, STALE_PAGE)
                            return@use
                        }
                        val bundle = try {
                            exchange(callback.value, verifier, redirectUri).also {
                                ensureActive(externalCancellation)
                            }
                        } catch (error: Throwable) {
                            writePage(connection, 400, FAILED_PAGE)
                            throw error
                        }
                        writePage(connection, 200, SUCCESS_PAGE)
                        return bundle
                    }
                }
            }
        }
        error("Codex OAuth login timed out")
    }

    fun cancel() {
        cancelled.set(true)
        close()
    }

    override fun close() {
        runCatching { listener.close() }
    }

    override fun toString(): String = "CodexOAuthSession(port=${listener.localPort}, credentials=<memory-only>)"

    private fun ensureActive(externalCancellation: () -> Boolean) {
        check(!cancelled.get() && !externalCancellation()) { "Codex OAuth login cancelled" }
    }

    private fun readCallback(socket: Socket): OAuthCallback? {
        socket.soTimeout = socketReadTimeoutMs
        val request = readHttpHeader(socket.getInputStream()) ?: return null
        val requestLine = request.substringBefore("\r\n")
        val parts = requestLine.split(' ')
        if (parts.size < 3 || parts[0] != "GET") {
            writePage(socket, 400, INVALID_PAGE)
            return null
        }
        val uri = runCatching {
            val target = parts[1]
            if (target.startsWith("http://") || target.startsWith("https://")) URI(target)
            else URI("http://localhost$target")
        }.getOrNull() ?: return OAuthCallback.Invalid
        return when (uri.path) {
            CALLBACK_PATH -> CodexOAuthProtocol.parseCallback(uri.rawQuery)
            "/cancel" -> OAuthCallback.Cancelled(CodexOAuthProtocol.callbackState(uri.rawQuery))
            else -> {
                writePage(socket, 404, NOT_FOUND_PAGE)
                null
            }
        }
    }

    private fun readHttpHeader(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        var tail = 0
        while (output.size() < MAX_HTTP_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            output.write(next)
            tail = ((tail shl 8) or next) and 0xffffffff.toInt()
            if (tail == HTTP_HEADER_END) {
                return output.toString(StandardCharsets.US_ASCII.name())
            }
        }
        return null
    }

    private fun writePage(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Bad Request"
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ${bytes.size}\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        runCatching {
            socket.getOutputStream().use { output ->
                output.write(header)
                output.write(bytes)
                output.flush()
            }
        }
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 5 * 60 * 1_000L
        const val DEFAULT_SOCKET_READ_TIMEOUT_MS = 5_000
        const val MAX_HTTP_HEADER_BYTES = 16 * 1_024
        const val HTTP_HEADER_END = 0x0d0a0d0a
        const val CALLBACK_PATH = "/auth/callback"

        val SUCCESS_PAGE = page("登录完成", "ChatGPT 已连接到 Sense，可以关闭此页面并返回设置。", true)
        val FAILED_PAGE = page("登录未完成", "令牌交换失败，请返回 Sense 后重新登录。")
        val STALE_PAGE = page("登录链接已过期", "请使用 Sense 刚刚打开的最新登录页面。")
        val INVALID_PAGE = page("回调信息不完整", "请返回 Sense 后重新登录。")
        val CANCELLED_PAGE = page("登录已取消", "可以关闭此页面并返回 Sense。")
        val NOT_FOUND_PAGE = page("Sense OAuth", "本地回调服务正在等待 ChatGPT 登录。")

        fun page(title: String, message: String, close: Boolean = false): String = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
            <title>$title</title><style>body{margin:0;background:#f5f6f8;color:#15171a;font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}.card{width:min(84vw,420px);padding:32px;border-radius:24px;background:#fff;box-shadow:0 12px 40px #0002}h1{font-size:24px;margin:0 0 12px}p{font-size:16px;line-height:1.65;margin:0;color:#5b616a}.mark{width:48px;height:48px;border-radius:16px;background:#dff7e8;color:#16884a;display:grid;place-items:center;font-size:25px;margin-bottom:22px}</style></head>
            <body><main class="card"><div class="mark">✓</div><h1>$title</h1><p>$message</p></main>${if (close) "<script>setTimeout(()=>window.close(),1200)</script>" else ""}</body></html>
        """.trimIndent()
    }
}

internal sealed interface OAuthCallback {
    data class Code(val value: String, val state: String) : OAuthCallback
    data class Cancelled(val state: String) : OAuthCallback
    data object Invalid : OAuthCallback
}

/** Pure helpers kept separate so PKCE, URL encoding and callback validation stay unit-testable. */
internal object CodexOAuthProtocol {
    const val AUTH_BASE = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val CALLBACK_PATH = "/auth/callback"
    const val PRIMARY_PORT = 1455
    private const val SCOPES =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"

    fun codeChallenge(verifier: String): String {
        require(verifier.length in 43..128)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(redirectUri: String, challenge: String, state: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to "codex_cli_rs",
            "prompt" to "login",
        )
        return "$AUTH_BASE/oauth/authorize?" + params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    fun parseCallback(rawQuery: String?): OAuthCallback {
        val params = rawQuery.orEmpty().split('&')
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null
                else decode(entry.substring(0, separator)) to decode(entry.substring(separator + 1))
            }
            .toMap()
        if (!params["error"].isNullOrBlank()) {
            return OAuthCallback.Cancelled(params["state"].orEmpty())
        }
        val code = params["code"].orEmpty()
        val state = params["state"].orEmpty()
        return if (code.isBlank() || state.isBlank()) OAuthCallback.Invalid
        else OAuthCallback.Code(code, state)
    }

    fun callbackState(rawQuery: String?): String = rawQuery.orEmpty().split('&')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) null
            else decode(entry.substring(0, separator)) to decode(entry.substring(separator + 1))
        }
        .lastOrNull { it.first == "state" }
        ?.second
        .orEmpty()

    fun newSecret(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault("")
}

fun interface CodexTokenRefresher {
    fun refresh(previous: CodexTokenBundle): CodexTokenBundle
}

/** ChatGPT browser OAuth using the same PKCE + localhost callback shape as Codex CLI and jcode. */
class CodexOAuthClient(
    private val secureRandom: SecureRandom = SecureRandom(),
) : CodexTokenRefresher {
    private val activeSession = AtomicReference<CodexOAuthSession?>()
    private val sessionLock = Any()

    fun beginLogin(): CodexOAuthSession = synchronized(sessionLock) {
        activeSession.getAndSet(null)?.cancel()
        val listener = bindLoopback()
        try {
            val verifier = CodexOAuthProtocol.newSecret(secureRandom)
            val state = CodexOAuthProtocol.newSecret(secureRandom)
            val redirectUri =
                "http://localhost:${listener.localPort}${CodexOAuthProtocol.CALLBACK_PATH}"
            val session = CodexOAuthSession(
                authorizationUrl = CodexOAuthProtocol.authorizationUrl(
                    redirectUri = redirectUri,
                    challenge = CodexOAuthProtocol.codeChallenge(verifier),
                    state = state,
                ),
                redirectUri = redirectUri,
                verifier = verifier,
                expectedState = state,
                listener = listener,
            )
            activeSession.set(session)
            session
        } catch (error: Throwable) {
            runCatching { listener.close() }
            throw error
        }
    }

    fun completeLogin(
        session: CodexOAuthSession,
        cancelled: () -> Boolean = { false },
    ): CodexTokenBundle {
        check(activeSession.get() === session) { "Codex OAuth session is no longer active" }
        return try {
            session.complete(cancelled, ::exchangeAuthorizationCode)
        } finally {
            session.close()
            activeSession.compareAndSet(session, null)
        }
    }

    fun cancelLogin() {
        synchronized(sessionLock) {
            activeSession.getAndSet(null)?.cancel()
        }
    }

    fun cancelLogin(session: CodexOAuthSession) {
        activeSession.compareAndSet(session, null)
        session.cancel()
    }

    override fun refresh(previous: CodexTokenBundle): CodexTokenBundle {
        val response = postForm(
            "$AUTH_BASE/oauth/token",
            mapOf(
                "client_id" to CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to previous.refreshToken,
            ),
        )
        return response.toBundle(previous)
    }

    private fun exchangeAuthorizationCode(
        code: String,
        verifier: String,
        redirectUri: String,
    ): CodexTokenBundle {
        val response = postForm(
            "$AUTH_BASE/oauth/token",
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
        )
        return response.toBundle()
    }

    private fun JSONObject.toBundle(previous: CodexTokenBundle? = null): CodexTokenBundle {
        val expiresIn = optLong("expires_in", 0L).coerceAtLeast(0L)
        return CodexTokenBundle(
            idToken = optString("id_token").ifBlank { previous?.idToken.orEmpty() },
            accessToken = getString("access_token"),
            refreshToken = optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
            expiresAtEpochSeconds = if (expiresIn == 0L) 0L
            else System.currentTimeMillis() / 1_000 + expiresIn,
        ).also {
            require(it.idToken.isNotBlank()) { "Codex OAuth response did not include an ID token" }
            require(it.refreshToken.isNotBlank()) { "Codex OAuth response did not include a refresh token" }
        }
    }

    private fun bindLoopback(): ServerSocket {
        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            listener.bind(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), CodexOAuthProtocol.PRIMARY_PORT),
                4,
            )
            listener.soTimeout = 1_000
            return listener
        } catch (error: Throwable) {
            runCatching { listener.close() }
            throw IllegalStateException("Codex OAuth callback port 1455 is busy", error)
        }
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject = post(
        value = url,
        contentType = "application/x-www-form-urlencoded",
        body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        },
    )

    private fun post(value: String, contentType: String, body: String): JSONObject {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("User-Agent", "Sense-IME Codex-OAuth")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.use(::readBounded).orEmpty()
            require(status in 200..299) { "Codex authentication failed with HTTP $status" }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 256 * 1_024)
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val AUTH_BASE = CodexOAuthProtocol.AUTH_BASE
        const val CLIENT_ID = CodexOAuthProtocol.CLIENT_ID
    }
}

/**
 * Stores refreshable subscription tokens behind the existing non-exportable Keystore key.
 *
 * Refresh, browser-login replacement and logout all take the same OS file lock. Every holder
 * re-reads the vault after taking that lock, so separate Sense processes never refresh from a
 * cached rotating token. The vault adds its own file lock plus [android.util.AtomicFile]; the lock
 * order is always this refresh lock followed by the vault lock.
 */
class CodexSubscriptionAuthStore internal constructor(
    private val refreshLockFile: File,
    private val tokenRefresher: CodexTokenRefresher,
    private val vault: ActionCredentialVault,
) {
    constructor(
        context: Context,
        client: CodexTokenRefresher = CodexOAuthClient(),
        vault: ActionCredentialVault = AndroidActionCredentialVault(context),
    ) : this(
        refreshLockFile = File(
            context.applicationContext.filesDir,
            "$REFRESH_LOCK_DIRECTORY/$REFRESH_LOCK_FILE",
        ),
        tokenRefresher = client,
        vault = vault,
    )

    private val ref = ActionCredentialRef(HANDLE, ActionSkillAuthMode.BEARER)

    fun save(bundle: CodexTokenBundle): Result<CodexAccountSummary> = runCatching {
        withRefreshLock { storeBundle(bundle) }
    }

    fun credential(): Result<ProviderCredential.ChatGpt?> = runCatching {
        val observed = readBundle() ?: return@runCatching null
        if (isFresh(observed)) return@runCatching observed.toCredential()
        // Refresh tokens rotate. Re-read only after taking the shared file lock so another Sense
        // process can win the refresh without this process replaying the superseded token.
        withRefreshLock {
            val current = readBundle() ?: return@withRefreshLock null
            val ready = if (isFresh(current)) {
                current
            } else {
                tokenRefresher.refresh(current).also { storeBundle(it) }
            }
            ready.toCredential()
        }
    }

    fun account(): Result<CodexAccountSummary?> = runCatching {
        val material = vault.lease(ref).getOrThrow() ?: return@runCatching null
        material.use { accountSummary(it.decodeBundle()) }
    }

    fun clear(): Result<Unit> = runCatching {
        withRefreshLock { vault.revoke(HANDLE).getOrThrow() }
    }

    private fun readBundle(): CodexTokenBundle? {
        val material = vault.lease(ref).getOrThrow() ?: return null
        return material.use { it.decodeBundle() }
    }

    private fun isFresh(bundle: CodexTokenBundle): Boolean {
        val expiresAt = bundle.expiresAtEpochSeconds.takeIf { it > 0L }
            ?: jwtClaims(bundle.accessToken).optLong("exp", 0L)
        return expiresAt > System.currentTimeMillis() / 1_000 + REFRESH_EARLY_SECONDS
    }

    private fun CodexTokenBundle.toCredential(): ProviderCredential.ChatGpt {
        val account = accountSummary(this)
        return ProviderCredential.ChatGpt(accessToken, account.accountId)
    }

    private fun storeBundle(bundle: CodexTokenBundle): CodexAccountSummary {
        val account = accountSummary(bundle)
        vault.store(ref, bundle.toJson().toCharArray()).getOrThrow()
        return account
    }

    private fun <T> withRefreshLock(block: () -> T): T = synchronized(REFRESH_MUTEX) {
        val parent = checkNotNull(refreshLockFile.parentFile)
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            error("Codex OAuth refresh lock directory could not be created")
        }
        RandomAccessFile(refreshLockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun accountSummary(bundle: CodexTokenBundle): CodexAccountSummary {
        val idClaims = jwtClaims(bundle.idToken)
        val accessClaims = jwtClaims(bundle.accessToken)
        val auth = idClaims.optJSONObject("https://api.openai.com/auth")
            ?: accessClaims.optJSONObject("https://api.openai.com/auth")
        val accountId = auth?.optString("chatgpt_account_id").orEmpty().ifBlank {
            idClaims.optString("chatgpt_account_id").ifBlank {
                accessClaims.optString("chatgpt_account_id")
            }
        }
        require(accountId.isNotBlank()) { "Codex account id is missing from the session" }
        val email = idClaims.optString("email").ifBlank { null }
        return CodexAccountSummary(accountId, email)
    }

    private fun jwtClaims(jwt: String): JSONObject {
        val parts = jwt.split('.')
        require(parts.size >= 2) { "Invalid Codex token" }
        val decoded = Base64.getUrlDecoder().decode(parts[1])
        return JSONObject(decoded.toString(StandardCharsets.UTF_8))
    }

    private fun ActionCredentialMaterial.decodeBundle(): CodexTokenBundle {
        val copy = copySecret()
        return try {
            CodexTokenBundle.fromJson(copy.concatToString())
        } finally {
            copy.fill('\u0000')
        }
    }

    private companion object {
        const val HANDLE = "codex.subscription"
        const val REFRESH_LOCK_DIRECTORY = "agent/action-credentials"
        const val REFRESH_LOCK_FILE = "codex-refresh.lock"
        const val REFRESH_EARLY_SECONDS = 300L
        val REFRESH_MUTEX = Any()
    }
}

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(StandardCharsets.UTF_8),
    right.toByteArray(StandardCharsets.UTF_8),
)
