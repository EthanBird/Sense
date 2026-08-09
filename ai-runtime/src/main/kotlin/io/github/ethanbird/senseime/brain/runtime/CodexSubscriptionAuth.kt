package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.util.Base64
import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class CodexDeviceCode(
    val verificationUrl: String,
    val userCode: String,
    internal val deviceAuthId: String,
    internal val intervalSeconds: Long,
)

data class CodexAccountSummary(
    val accountId: String,
    val email: String?,
)

data class CodexTokenBundle(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
) {
    fun toJson(): String = JSONObject()
        .put("id_token", idToken)
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .toString()

    companion object {
        fun fromJson(value: String) = JSONObject(value).let {
            CodexTokenBundle(
                idToken = it.getString("id_token"),
                accessToken = it.getString("access_token"),
                refreshToken = it.getString("refresh_token"),
            )
        }
    }
}

/** Official Codex device-code endpoints, implemented for an Android browser hand-off. */
class CodexDeviceAuthClient {
    fun requestDeviceCode(): CodexDeviceCode {
        val response = postJson(
            "$AUTH_BASE/api/accounts/deviceauth/usercode",
            JSONObject().put("client_id", CLIENT_ID).toString(),
        )
        return CodexDeviceCode(
            verificationUrl = "$AUTH_BASE/codex/device",
            userCode = response.optString("user_code").ifBlank { response.getString("usercode") },
            deviceAuthId = response.getString("device_auth_id"),
            intervalSeconds = response.optString("interval", "5").toLong().coerceIn(1, 30),
        )
    }

    fun completeDeviceCode(
        code: CodexDeviceCode,
        cancelled: () -> Boolean = { false },
    ): CodexTokenBundle {
        val deadline = System.currentTimeMillis() + DEVICE_CODE_TIMEOUT_MS
        var authorization: JSONObject? = null
        while (authorization == null && System.currentTimeMillis() < deadline) {
            check(!cancelled()) { "Codex login was cancelled" }
            val result = postJsonAllowPending(
                "$AUTH_BASE/api/accounts/deviceauth/token",
                JSONObject()
                    .put("device_auth_id", code.deviceAuthId)
                    .put("user_code", code.userCode)
                    .toString(),
            )
            authorization = result
            if (authorization == null) Thread.sleep(code.intervalSeconds * 1_000)
        }
        val issued = authorization ?: error("Codex device login timed out")
        return exchangeAuthorizationCode(
            code = issued.getString("authorization_code"),
            verifier = issued.getString("code_verifier"),
        )
    }

    internal fun refresh(previous: CodexTokenBundle): CodexTokenBundle {
        val response = postForm(
            "$AUTH_BASE/oauth/token",
            mapOf(
                "client_id" to CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to previous.refreshToken,
            ),
        )
        return CodexTokenBundle(
            idToken = response.optString("id_token").ifBlank { previous.idToken },
            accessToken = response.getString("access_token"),
            refreshToken = response.optString("refresh_token").ifBlank { previous.refreshToken },
        )
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String): CodexTokenBundle {
        val response = postForm(
            "$AUTH_BASE/oauth/token",
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to "$AUTH_BASE/deviceauth/callback",
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
        )
        return CodexTokenBundle(
            idToken = response.getString("id_token"),
            accessToken = response.getString("access_token"),
            refreshToken = response.getString("refresh_token"),
        )
    }

    private fun postJson(url: String, body: String): JSONObject =
        post(url, "application/json", body).second

    private fun postJsonAllowPending(url: String, body: String): JSONObject? {
        val (status, response) = post(url, "application/json", body, setOf(403, 404))
        return if (status in setOf(403, 404)) null else response
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject = post(
        url,
        "application/x-www-form-urlencoded",
        values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" 
        },
    ).second

    private fun post(
        value: String,
        contentType: String,
        body: String,
        allowedErrors: Set<Int> = emptySet(),
    ): Pair<Int, JSONObject> {
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
            connection.setRequestProperty("User-Agent", "Sense-IME/0.4.10 Codex-Auth")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.use(::readBounded).orEmpty()
            require(status in 200..299 || status in allowedErrors) {
                "Codex authentication failed with HTTP $status"
            }
            status to if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
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

    companion object {
        const val VERIFICATION_URL = "https://auth.openai.com/codex/device"
        private const val AUTH_BASE = "https://auth.openai.com"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val DEVICE_CODE_TIMEOUT_MS = 15 * 60 * 1_000L
    }
}

/** Stores refreshable subscription tokens behind the existing non-exportable Keystore key. */
class CodexSubscriptionAuthStore(
    context: Context,
    private val client: CodexDeviceAuthClient = CodexDeviceAuthClient(),
    private val vault: ActionCredentialVault = AndroidActionCredentialVault(context),
) {
    private val ref = ActionCredentialRef(HANDLE, ActionSkillAuthMode.BEARER)

    fun save(bundle: CodexTokenBundle): Result<CodexAccountSummary> = runCatching {
        val account = accountSummary(bundle)
        vault.store(ref, bundle.toJson().toCharArray()).getOrThrow()
        account
    }

    fun credential(): Result<ProviderCredential.ChatGpt?> = runCatching {
        val material = vault.lease(ref).getOrThrow() ?: return@runCatching null
        var bundle = material.use { it.decodeBundle() }
        if (jwtExpiryEpochSeconds(bundle.accessToken) <= System.currentTimeMillis() / 1_000 + 300) {
            bundle = client.refresh(bundle)
            save(bundle).getOrThrow()
        }
        val account = accountSummary(bundle)
        ProviderCredential.ChatGpt(bundle.accessToken, account.accountId)
    }

    fun account(): Result<CodexAccountSummary?> = runCatching {
        val material = vault.lease(ref).getOrThrow() ?: return@runCatching null
        material.use { accountSummary(it.decodeBundle()) }
    }

    fun clear(): Result<Unit> = vault.revoke(HANDLE)

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

    private fun jwtExpiryEpochSeconds(jwt: String): Long = jwtClaims(jwt).optLong("exp", 0L)

    private fun jwtClaims(jwt: String): JSONObject {
        val parts = jwt.split('.')
        require(parts.size >= 2) { "Invalid Codex token" }
        val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
    }
}
