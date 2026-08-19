package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.util.AtomicFile
import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

enum class FeishuDomain(val openApiHost: String) {
    FEISHU("https://open.feishu.cn"),
    LARK("https://open.larksuite.com"),
}

data class TelegramAgentChannelSettings(
    val enabled: Boolean = false,
    val pairingCode: String = "",
    val pairingGeneration: Long = 1L,
    val boundPeerId: String = "",
    val boundChatId: String = "",
) {
    init {
        require(pairingCode.isEmpty() || pairingCode.matches(Regex("[0-9]{6}")))
        require(pairingGeneration > 0L)
        require(boundPeerId.length <= 192)
        require(boundChatId.length <= 192)
    }
}

data class FeishuAgentChannelSettings(
    val enabled: Boolean = false,
    val appId: String = "",
    val domain: FeishuDomain = FeishuDomain.FEISHU,
    val pairingCode: String = "",
    val pairingGeneration: Long = 1L,
    val boundPeerId: String = "",
    val boundChatId: String = "",
) {
    init {
        require(appId.length <= 192)
        require(pairingCode.isEmpty() || pairingCode.matches(Regex("[0-9]{6}")))
        require(pairingGeneration > 0L)
        require(boundPeerId.length <= 192)
        require(boundChatId.length <= 192)
    }
}

data class AgentChannelSettings(
    val revision: Long = 1L,
    val paused: Boolean = false,
    val telegram: TelegramAgentChannelSettings = TelegramAgentChannelSettings(),
    val feishu: FeishuAgentChannelSettings = FeishuAgentChannelSettings(),
) {
    init {
        require(revision > 0L)
    }

    val anyEnabled: Boolean
        get() = telegram.enabled || feishu.enabled

    val shouldRun: Boolean
        get() = anyEnabled && !paused
}

data class AgentChannelSettingsSnapshot(
    val settings: AgentChannelSettings,
    val telegramCredentialStored: Boolean,
    val feishuCredentialStored: Boolean,
)

data class AgentChannelRuntimeConfig(
    val settings: AgentChannelSettings,
    val telegramBotToken: String?,
    val feishuAppSecret: String?,
) {
    fun requireReady(): AgentChannelRuntimeConfig {
        if (settings.shouldRun && settings.telegram.enabled) {
            require(!telegramBotToken.isNullOrBlank())
            require(TelegramBotTokenValidator.isValid(checkNotNull(telegramBotToken))) {
                "Telegram bot token format is invalid"
            }
        }
        if (settings.shouldRun && settings.feishu.enabled) {
            require(settings.feishu.appId.isNotBlank())
            require(!feishuAppSecret.isNullOrBlank())
        }
        return this
    }
}

/** Atomic non-secret channel configuration plus Keystore-backed bot credentials. */
class AgentChannelSettingsStore(
    context: Context,
    private val credentialVault: ActionCredentialVault =
        AndroidActionCredentialVault(context.applicationContext),
) {
    private val root = File(context.applicationContext.filesDir, STORE_DIRECTORY)
    private val file = AtomicFile(File(root, STORE_FILE))
    private val lockFile = File(root, LOCK_FILE)
    private val random = SecureRandom()

    fun load(): Result<AgentChannelSettingsSnapshot> = runCatching {
        val settings = loadSettings().getOrThrow()
        AgentChannelSettingsSnapshot(
            settings = settings,
            telegramCredentialStored = hasCredential(TELEGRAM_TOKEN_REF),
            feishuCredentialStored = hasCredential(FEISHU_SECRET_REF),
        )
    }

    /** Credential-free settings read used to linearize access decisions with pairing reset. */
    fun loadSettings(): Result<AgentChannelSettings> = runCatching {
        withStoreLock {
            readOrNull()?.let(AgentChannelSettingsCodec::decode) ?: defaults().also(::write)
        }
    }

    /** Null secret preserves the stored value; an empty secret revokes it. */
    fun save(
        settings: AgentChannelSettings,
        telegramBotToken: CharArray? = null,
        feishuAppSecret: CharArray? = null,
    ): Result<Unit> = runCatching {
        val normalized = normalize(settings)
        telegramBotToken?.takeIf(CharArray::isNotEmpty)?.let { candidate ->
            require(TelegramBotTokenValidator.isValid(candidate.concatToString())) {
                "Telegram bot token format is invalid"
            }
        }
        updateCredential(TELEGRAM_TOKEN_REF, telegramBotToken)
        updateCredential(FEISHU_SECRET_REF, feishuAppSecret)
        val telegramReady = !normalized.telegram.enabled || hasCredential(TELEGRAM_TOKEN_REF)
        val feishuReady = !normalized.feishu.enabled ||
            (normalized.feishu.appId.isNotBlank() && hasCredential(FEISHU_SECRET_REF))
        require(telegramReady) { "Telegram bot token is required" }
        require(feishuReady) { "Feishu app id and app secret are required" }
        if (normalized.telegram.enabled) {
            val storedToken = leaseSecret(TELEGRAM_TOKEN_REF)
            require(storedToken != null && TelegramBotTokenValidator.isValid(storedToken)) {
                "Telegram bot token format is invalid"
            }
        }
        withStoreLock {
            val current = readOrNull()?.let(AgentChannelSettingsCodec::decode) ?: defaults()
            write(normalized.copy(revision = nextRevision(current.revision)))
        }
    }

    /**
     * Compare-and-bind closes the reset/pair race: the code and generation observed when access
     * was granted must still describe the unbound endpoint while the settings file lock is held.
     */
    fun bind(
        source: AgentChannelSource,
        expectedPairingCode: String,
        expectedPairingGeneration: Long,
    ): Result<AgentChannelSettings> = runCatching {
        withStoreLock {
            val current = readOrNull()?.let(AgentChannelSettingsCodec::decode) ?: defaults()
            val next = requireNotNull(
                AgentChannelBindingTransition.compareAndBind(
                    current = current,
                    source = source,
                    expectedPairingCode = expectedPairingCode,
                    expectedPairingGeneration = expectedPairingGeneration,
                ),
            ) { "Pairing code changed before binding" }.copy(
                revision = nextRevision(current.revision),
            )
            write(next)
            next
        }
    }

    fun resetBinding(type: AgentChannelType): Result<AgentChannelSettings> = runCatching {
        withStoreLock {
            val current = readOrNull()?.let(AgentChannelSettingsCodec::decode) ?: defaults()
            val next = when (type) {
                AgentChannelType.TELEGRAM -> current.copy(
                    telegram = current.telegram.copy(
                        pairingCode = pairingCode(),
                        pairingGeneration = nextPairingGeneration(
                            current.telegram.pairingGeneration,
                        ),
                        boundPeerId = "",
                        boundChatId = "",
                    ),
                )
                AgentChannelType.FEISHU -> current.copy(
                    feishu = current.feishu.copy(
                        pairingCode = pairingCode(),
                        pairingGeneration = nextPairingGeneration(
                            current.feishu.pairingGeneration,
                        ),
                        boundPeerId = "",
                        boundChatId = "",
                    ),
                )
            }.copy(revision = nextRevision(current.revision))
            write(next)
            next
        }
    }

    fun setPaused(paused: Boolean): Result<AgentChannelSettings> = runCatching {
        withStoreLock {
            val current = readOrNull()?.let(AgentChannelSettingsCodec::decode) ?: defaults()
            val next = if (current.paused == paused) {
                current
            } else {
                current.copy(
                    revision = nextRevision(current.revision),
                    paused = paused,
                )
            }
            if (next != current) write(next)
            next
        }
    }

    fun loadRuntimeConfig(): Result<AgentChannelRuntimeConfig> = runCatching {
        val settings = load().getOrThrow().settings
        AgentChannelRuntimeConfig(
            settings = settings,
            telegramBotToken = leaseSecret(TELEGRAM_TOKEN_REF),
            feishuAppSecret = leaseSecret(FEISHU_SECRET_REF),
        ).requireReady()
    }

    private fun normalize(settings: AgentChannelSettings): AgentChannelSettings = settings.copy(
        telegram = settings.telegram.copy(
            pairingCode = settings.telegram.pairingCode.takeIf(String::isNotBlank) ?: pairingCode(),
            boundPeerId = settings.telegram.boundPeerId.trim(),
            boundChatId = settings.telegram.boundChatId.trim(),
        ),
        feishu = settings.feishu.copy(
            appId = settings.feishu.appId.trim(),
            pairingCode = settings.feishu.pairingCode.takeIf(String::isNotBlank) ?: pairingCode(),
            boundPeerId = settings.feishu.boundPeerId.trim(),
            boundChatId = settings.feishu.boundChatId.trim(),
        ),
    )

    private fun defaults(): AgentChannelSettings = AgentChannelSettings(
        telegram = TelegramAgentChannelSettings(pairingCode = pairingCode()),
        feishu = FeishuAgentChannelSettings(pairingCode = pairingCode()),
    )

    private fun pairingCode(): String = (random.nextInt(900_000) + 100_000).toString()

    private fun nextPairingGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun nextRevision(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun updateCredential(ref: ActionCredentialRef, secret: CharArray?) {
        when {
            secret == null -> Unit
            secret.isEmpty() -> credentialVault.revoke(ref.handle).getOrThrow()
            else -> credentialVault.store(ref, secret).getOrThrow()
        }
    }

    private fun hasCredential(ref: ActionCredentialRef): Boolean =
        credentialVault.lease(ref).getOrNull()?.use { true } ?: false

    private fun leaseSecret(ref: ActionCredentialRef): String? =
        credentialVault.lease(ref).getOrThrow()?.use { material ->
            val copy = material.copySecret()
            try {
                copy.concatToString()
            } finally {
                copy.fill('\u0000')
            }
        }

    private fun readOrNull(): String? = try {
        file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun write(settings: AgentChannelSettings) {
        val stream = file.startWrite()
        try {
            stream.write(AgentChannelSettingsCodec.encode(settings).toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Agent channel directory could not be created")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    companion object {
        private const val STORE_DIRECTORY = "agent/channels"
        private const val STORE_FILE = "settings.v1"
        private const val LOCK_FILE = "settings.lock"
        private val STORE_MUTEX = Any()
        private val TELEGRAM_TOKEN_REF = ActionCredentialRef(
            handle = "channel.telegram.bot-token",
            authMode = ActionSkillAuthMode.BEARER,
        )
        private val FEISHU_SECRET_REF = ActionCredentialRef(
            handle = "channel.feishu.app-secret",
            authMode = ActionSkillAuthMode.API_KEY_HEADER,
            headerName = "X-App-Secret",
        )
    }
}

internal object AgentChannelSettingsCodec {
    private const val HEADER_V1 = "sense.agent.channels.v1"
    private const val HEADER_V2 = "sense.agent.channels.v2"
    private const val HEADER_V3 = "sense.agent.channels.v3"

    fun encode(settings: AgentChannelSettings): String = buildString {
        appendLine(HEADER_V3)
        appendLine(settings.revision)
        appendLine(settings.paused)
        appendLine(settings.telegram.enabled)
        appendLine(settings.telegram.pairingCode.wire())
        appendLine(settings.telegram.pairingGeneration)
        appendLine(settings.telegram.boundPeerId.wire())
        appendLine(settings.telegram.boundChatId.wire())
        appendLine(settings.feishu.enabled)
        appendLine(settings.feishu.appId.wire())
        appendLine(settings.feishu.domain.name)
        appendLine(settings.feishu.pairingCode.wire())
        appendLine(settings.feishu.pairingGeneration)
        appendLine(settings.feishu.boundPeerId.wire())
        appendLine(settings.feishu.boundChatId.wire())
    }

    fun decode(document: String): AgentChannelSettings {
        val lines = document.lineSequence().toList()
        require(lines.isNotEmpty() && lines[0] in setOf(HEADER_V1, HEADER_V2, HEADER_V3))
        val version2 = lines[0] in setOf(HEADER_V2, HEADER_V3)
        val version3 = lines[0] == HEADER_V3
        require(lines.size >= when {
            version3 -> 15
            version2 -> 13
            else -> 11
        })
        val telegramEnabledIndex = if (version3) 3 else 1
        val telegramCodeIndex = if (version3) 4 else 2
        val telegramGeneration = if (version2) {
            lines[if (version3) 5 else 3].toLong()
        } else {
            1L
        }
        val telegramPeerIndex = if (version3) 6 else if (version2) 4 else 3
        val telegramChatIndex = if (version3) 7 else if (version2) 5 else 4
        val feishuEnabledIndex = if (version3) 8 else if (version2) 6 else 5
        val feishuAppIdIndex = if (version3) 9 else if (version2) 7 else 6
        val feishuDomainIndex = if (version3) 10 else if (version2) 8 else 7
        val feishuCodeIndex = if (version3) 11 else if (version2) 9 else 8
        val feishuGeneration = if (version2) {
            lines[if (version3) 12 else 10].toLong()
        } else {
            1L
        }
        val feishuPeerIndex = if (version3) 13 else if (version2) 11 else 9
        val feishuChatIndex = if (version3) 14 else if (version2) 12 else 10
        return AgentChannelSettings(
            revision = if (version3) lines[1].toLong() else 1L,
            paused = if (version3) lines[2].toBooleanStrict() else false,
            telegram = TelegramAgentChannelSettings(
                enabled = lines[telegramEnabledIndex].toBooleanStrict(),
                pairingCode = lines[telegramCodeIndex].unwire(),
                pairingGeneration = telegramGeneration,
                boundPeerId = lines[telegramPeerIndex].unwire(),
                boundChatId = lines[telegramChatIndex].unwire(),
            ),
            feishu = FeishuAgentChannelSettings(
                enabled = lines[feishuEnabledIndex].toBooleanStrict(),
                appId = lines[feishuAppIdIndex].unwire(),
                domain = FeishuDomain.valueOf(lines[feishuDomainIndex]),
                pairingCode = lines[feishuCodeIndex].unwire(),
                pairingGeneration = feishuGeneration,
                boundPeerId = lines[feishuPeerIndex].unwire(),
                boundChatId = lines[feishuChatIndex].unwire(),
            ),
        )
    }

    private fun String.wire(): String = if (isEmpty()) {
        "~"
    } else {
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(StandardCharsets.UTF_8))
    }

    private fun String.unwire(): String = if (this == "~") {
        ""
    } else {
        Base64.getUrlDecoder().decode(this).toString(StandardCharsets.UTF_8)
    }
}

internal object AgentChannelBindingTransition {
    fun compareAndBind(
        current: AgentChannelSettings,
        source: AgentChannelSource,
        expectedPairingCode: String,
        expectedPairingGeneration: Long,
    ): AgentChannelSettings? = when (source.channel) {
        AgentChannelType.TELEGRAM -> current.telegram.takeIf { endpoint ->
            endpoint.boundPeerId.isBlank() &&
                endpoint.boundChatId.isBlank() &&
                endpoint.pairingCode == expectedPairingCode &&
                endpoint.pairingGeneration == expectedPairingGeneration
        }?.let {
            current.copy(
                telegram = it.copy(
                    boundPeerId = source.peerId,
                    boundChatId = source.chatId,
                ),
            )
        }
        AgentChannelType.FEISHU -> current.feishu.takeIf { endpoint ->
            endpoint.boundPeerId.isBlank() &&
                endpoint.boundChatId.isBlank() &&
                endpoint.pairingCode == expectedPairingCode &&
                endpoint.pairingGeneration == expectedPairingGeneration
        }?.let {
            current.copy(
                feishu = it.copy(
                    boundPeerId = source.peerId,
                    boundChatId = source.chatId,
                ),
            )
        }
    }
}

internal object AgentChannelPairingGeneration {
    fun isCurrent(
        cached: AgentChannelSettings,
        persisted: AgentChannelSettings,
        type: AgentChannelType,
    ): Boolean = generation(cached, type) == generation(persisted, type)

    private fun generation(settings: AgentChannelSettings, type: AgentChannelType): Long =
        when (type) {
            AgentChannelType.TELEGRAM -> settings.telegram.pairingGeneration
            AgentChannelType.FEISHU -> settings.feishu.pairingGeneration
        }
}
