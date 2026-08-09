package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import io.github.ethanbird.senseime.brain.api.ActionSkillDescriptor
import io.github.ethanbird.senseime.brain.api.ActionSkillInvocation
import io.github.ethanbird.senseime.brain.api.ActionSkillResult
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.util.Locale

interface DirectActionSkill {
    val descriptor: ActionSkillDescriptor

    fun execute(invocation: ActionSkillInvocation, credential: ActionCredentialMaterial?): ActionSkillResult
}

data class ActionCredentialMaterial(
    val ref: ActionCredentialRef,
    private val secret: CharArray,
) : AutoCloseable {
    fun copySecret(): CharArray = secret.copyOf()

    /** Materializes only the connector-declared authentication header for the current call. */
    fun requestHeaders(): Map<String, String> {
        val copy = copySecret()
        return try {
            val value = copy.concatToString()
            when (ref.authMode) {
                ActionSkillAuthMode.NONE -> error("Credential material has no authentication mode")
                ActionSkillAuthMode.BEARER -> mapOf("Authorization" to "Bearer $value")
                ActionSkillAuthMode.API_KEY_HEADER -> mapOf(checkNotNull(ref.headerName) to value)
            }
        } finally {
            copy.fill('\u0000')
        }
    }

    override fun close() {
        secret.fill('\u0000')
    }
}

interface ActionCredentialVault {
    fun store(ref: ActionCredentialRef, secret: CharArray): Result<Unit>

    fun lease(ref: ActionCredentialRef): Result<ActionCredentialMaterial?>

    fun revoke(handle: String): Result<Unit>

    companion object {
        val EMPTY = object : ActionCredentialVault {
            override fun store(ref: ActionCredentialRef, secret: CharArray): Result<Unit> =
                runCatching { secret.fill('\u0000'); error("Credential vault is not configured") }

            override fun lease(ref: ActionCredentialRef): Result<ActionCredentialMaterial?> =
                Result.success(null)

            override fun revoke(handle: String): Result<Unit> = Result.success(Unit)
        }
    }
}

/** Registry/router for direct Action Skills. No provider profile or model transport is reachable. */
class DirectActionSkillRuntime(
    skills: List<DirectActionSkill>,
    private val credentialVault: ActionCredentialVault = ActionCredentialVault.EMPTY,
) {
    private val skillsById = skills.associateBy { it.descriptor.id }.also { indexed ->
        require(indexed.size == skills.size) { "Duplicate Action Skill id" }
    }

    fun descriptors(): List<ActionSkillDescriptor> = skillsById.values.map(DirectActionSkill::descriptor)

    fun execute(invocation: ActionSkillInvocation): Result<ActionSkillResult> = runCatching {
        val skill = requireNotNull(skillsById[invocation.skillId]) {
            "Unknown Action Skill: ${invocation.skillId}"
        }
        val descriptor = skill.descriptor
        val credential = descriptor.credentialHandle?.let { handle ->
            val ref = ActionCredentialRef(
                handle = handle,
                authMode = descriptor.authMode,
                headerName = descriptor.credentialHeaderName,
            )
            credentialVault.lease(ref).getOrThrow()
                ?: error("Credential is missing: $handle")
        }
        credential.use { skill.execute(invocation, credential) }
    }

    companion object {
        fun builtIns(
            loader: ActionHttpLoader = HttpsActionHttpLoader(),
            credentialVault: ActionCredentialVault = ActionCredentialVault.EMPTY,
        ): DirectActionSkillRuntime = DirectActionSkillRuntime(
            skills = listOf(XauUsdActionSkill(loader)),
            credentialVault = credentialVault,
        )
    }
}

fun interface ActionHttpLoader {
    fun get(url: String, headers: Map<String, String>): String
}

class XauUsdActionSkill(
    private val loader: ActionHttpLoader,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : DirectActionSkill {
    override val descriptor = ActionSkillDescriptor(
        id = SKILL_ID,
        displayName = "黄金现价",
        description = "直接获取 XAUUSD 现货黄金报价，不经过模型",
        authMode = ActionSkillAuthMode.NONE,
    )

    override fun execute(
        invocation: ActionSkillInvocation,
        credential: ActionCredentialMaterial?,
    ): ActionSkillResult {
        require(credential == null)
        val rawDocument = loader.get(ENDPOINT, emptyMap())
        val document = GoldQuoteJsonDecoder.decode(rawDocument)
        require(document.symbol == "XAU") { "Unexpected quote symbol" }
        require(document.currency == "USD") { "Unexpected quote currency" }
        val price = document.price.setScale(2, RoundingMode.HALF_UP)
        require(price > BigDecimal.ZERO) { "Quote price must be positive" }
        val observedAt = document.updatedAt
            .takeIf(String::isNotBlank)
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: nowEpochMs()
        val formatted = checkNotNull(PRICE_FORMAT.get()).format(price)
        val updated = document.updatedAt.ifBlank { Instant.ofEpochMilli(observedAt).toString() }
        return ActionSkillResult(
            requestId = invocation.requestId,
            skillId = descriptor.id,
            title = "XAUUSD · 现货黄金",
            primaryValue = "$$formatted / oz",
            secondaryValue = "USD · $updated",
            insertText = "XAUUSD 现价：$$formatted/oz（$updated，Gold API）",
            sourceLabel = "Gold API",
            sourceUrl = ENDPOINT,
            observedAtEpochMs = observedAt,
            attributes = mapOf(
                "symbol" to "XAU",
                "currency" to "USD",
                "price" to price.toPlainString(),
                "execution_mode" to "direct_zero_model_token",
            ),
            rawPayload = rawDocument,
        )
    }

    companion object {
        const val SKILL_ID = "market.xauusd"
        const val ENDPOINT = "https://api.gold-api.com/price/XAU"
        private val PRICE_FORMAT = ThreadLocal.withInitial {
            DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).apply {
                roundingMode = RoundingMode.HALF_UP
            }
        }
    }
}

internal data class GoldQuoteDocument(
    val symbol: String,
    val currency: String,
    val price: BigDecimal,
    val updatedAt: String,
)

/** Strict bounded decoder for the four fields used by the built-in quote connector. */
internal object GoldQuoteJsonDecoder {
    fun decode(document: String): GoldQuoteDocument {
        require(document.length in 2..MAX_DOCUMENT_CHARS)
        val symbol = stringField(document, "symbol")
        val currency = stringField(document, "currency")
        val updatedAt = stringField(document, "updatedAt")
        val price = numberField(document, "price").toBigDecimal()
        return GoldQuoteDocument(symbol, currency, price, updatedAt)
    }

    private fun stringField(document: String, name: String): String {
        val match = Regex(
            "\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
        ).find(document) ?: error("Missing JSON string field: $name")
        return decodeJsonString(match.groupValues[1])
    }

    private fun numberField(document: String, name: String): String = Regex(
        "\\\"${Regex.escape(name)}\\\"\\s*:\\s*(-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)",
    ).find(document)?.groupValues?.get(1) ?: error("Missing JSON number field: $name")

    private fun decodeJsonString(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character != '\\') {
                append(character)
                continue
            }
            require(index < value.length) { "Incomplete JSON escape" }
            when (val escaped = value[index++]) {
                '"', '\\', '/' -> append(escaped)
                'b' -> append('\b')
                'f' -> append('\u000C')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'u' -> {
                    require(index + 4 <= value.length) { "Incomplete JSON unicode escape" }
                    append(value.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("Unknown JSON escape: $escaped")
            }
        }
    }

    private const val MAX_DOCUMENT_CHARS = 131_072
}

class HttpsActionHttpLoader(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) : ActionHttpLoader {
    override fun get(url: String, headers: Map<String, String>): String {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Sense-IME/0.4.10 Action-Skills")
            headers.forEach(connection::setRequestProperty)
            require(connection.responseCode in 200..299) {
                "Action endpoint returned HTTP ${connection.responseCode}"
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) {
                        "Action response exceeds $MAX_RESPONSE_BYTES bytes"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 128 * 1_024
    }
}
