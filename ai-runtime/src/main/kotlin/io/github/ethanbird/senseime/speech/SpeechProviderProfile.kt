package io.github.ethanbird.senseime.speech

import java.net.URI
import java.util.Locale

/**
 * Secret-free speech recognition configuration.
 *
 * Credentials are deliberately stored separately by [SpeechProviderSettingsStore]. A saved cloud
 * profile does not imply that its transport adapter is executable in the installed build; callers
 * must also inspect [SpeechProviderPreset.runtimeCapability].
 */
data class SpeechProviderProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val presetId: String,
    val displayName: String,
    val transport: SpeechProviderTransport,
    val protocol: SpeechProviderProtocol,
    val endpointUrl: String? = null,
    val model: String? = null,
    val languageTag: String = DEFAULT_LANGUAGE_TAG,
    val preferOnDevice: Boolean = true,
    val interimResults: Boolean = true,
    val punctuation: Boolean = true,
) {
    fun validate(): SpeechProviderValidation = SpeechProviderValidator.validate(this)

    fun requireValid(): SpeechProviderProfile {
        val validation = validate()
        if (!validation.isValid) throw InvalidSpeechProviderProfileException(validation.errors)
        return this
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_LANGUAGE_TAG = "zh-CN"
    }
}

enum class SpeechProviderTransport {
    ANDROID_SPEECH_RECOGNIZER,
    HTTP_MULTIPART,
    HTTP_AUDIO_WAV,
    WEBSOCKET_PCM,
}

enum class SpeechProviderProtocol {
    ANDROID_SYSTEM,
    OPENAI_TRANSCRIPTIONS,
    DEEPGRAM_LISTEN,
    DASHSCOPE_REALTIME,
}

/**
 * An explicit execution gate. CONFIGURATION_ONLY providers may be persisted and shown in settings,
 * but the keyboard must not imply that recognition can start through them yet.
 */
enum class SpeechProviderRuntimeCapability {
    AVAILABLE,
    CONFIGURATION_ONLY,
}

enum class SpeechProviderCredentialRequirement {
    NONE,
    API_KEY,
}

data class SpeechProviderPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val transport: SpeechProviderTransport,
    val protocol: SpeechProviderProtocol,
    val runtimeCapability: SpeechProviderRuntimeCapability,
    val credentialRequirement: SpeechProviderCredentialRequirement,
    val defaultEndpointUrl: String? = null,
    val defaultModel: String? = null,
    val preferOnDevice: Boolean = false,
    val capabilityNotice: String? = null,
) {
    val canTranscribe: Boolean
        get() = runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE

    fun defaultProfile(languageTag: String = SpeechProviderProfile.DEFAULT_LANGUAGE_TAG) =
        SpeechProviderProfile(
            presetId = id,
            displayName = displayName,
            transport = transport,
            protocol = protocol,
            endpointUrl = defaultEndpointUrl,
            model = defaultModel,
            languageTag = languageTag,
            preferOnDevice = preferOnDevice,
        )
}

/**
 * Versioned local catalog. Endpoint defaults are ordinary configuration, never capability claims.
 */
object SpeechProviderPresetCatalog {
    const val CATALOG_VERSION = 1
    const val SYSTEM = "android-system"
    const val OPENAI_COMPATIBLE = "openai-compatible"
    const val DEEPGRAM = "deepgram"
    const val ALIBABA_DASHSCOPE = "alibaba-dashscope"

    private const val CONFIGURATION_ONLY_NOTICE =
        "当前版本仅保存配置，云端语音适配器尚未启用"

    val all: List<SpeechProviderPreset> = listOf(
        SpeechProviderPreset(
            id = SYSTEM,
            displayName = "系统语音识别",
            description = "免 API Key；优先使用设备端识别，缺失时安全回退到系统识别服务",
            transport = SpeechProviderTransport.ANDROID_SPEECH_RECOGNIZER,
            protocol = SpeechProviderProtocol.ANDROID_SYSTEM,
            runtimeCapability = SpeechProviderRuntimeCapability.AVAILABLE,
            credentialRequirement = SpeechProviderCredentialRequirement.NONE,
            preferOnDevice = true,
        ),
        SpeechProviderPreset(
            id = OPENAI_COMPATIBLE,
            displayName = "OpenAI-compatible 语音识别",
            description = "录音完成后通过 audio/transcriptions 兼容接口转写",
            transport = SpeechProviderTransport.HTTP_MULTIPART,
            protocol = SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS,
            runtimeCapability = SpeechProviderRuntimeCapability.AVAILABLE,
            credentialRequirement = SpeechProviderCredentialRequirement.API_KEY,
            defaultEndpointUrl = "https://api.openai.com/v1/audio/transcriptions",
            defaultModel = "gpt-4o-mini-transcribe",
        ),
        SpeechProviderPreset(
            id = DEEPGRAM,
            displayName = "Deepgram",
            description = "录音完成后通过预录音 HTTPS 接口转写",
            transport = SpeechProviderTransport.HTTP_AUDIO_WAV,
            protocol = SpeechProviderProtocol.DEEPGRAM_LISTEN,
            runtimeCapability = SpeechProviderRuntimeCapability.AVAILABLE,
            credentialRequirement = SpeechProviderCredentialRequirement.API_KEY,
            defaultEndpointUrl = "https://api.deepgram.com/v1/listen",
            defaultModel = "nova-3",
        ),
        SpeechProviderPreset(
            id = ALIBABA_DASHSCOPE,
            displayName = "阿里云百炼",
            description = "Paraformer/Fun-ASR 实时语音识别",
            transport = SpeechProviderTransport.WEBSOCKET_PCM,
            protocol = SpeechProviderProtocol.DASHSCOPE_REALTIME,
            runtimeCapability = SpeechProviderRuntimeCapability.CONFIGURATION_ONLY,
            credentialRequirement = SpeechProviderCredentialRequirement.API_KEY,
            defaultEndpointUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            defaultModel = "paraformer-realtime-v2",
            capabilityNotice = CONFIGURATION_ONLY_NOTICE,
        ),
    )

    private val byId = all.associateBy(SpeechProviderPreset::id)

    fun find(id: String): SpeechProviderPreset? = byId[id]

    fun require(id: String): SpeechProviderPreset =
        requireNotNull(find(id)) { "Unknown speech provider preset: $id" }
}

/** One-way compatibility for Deepgram profiles saved while it was a WebSocket preview. */
object SpeechProviderProfileMigration {
    fun migrate(profile: SpeechProviderProfile): SpeechProviderProfile =
        if (
            profile.presetId == SpeechProviderPresetCatalog.DEEPGRAM &&
            profile.protocol == SpeechProviderProtocol.DEEPGRAM_LISTEN &&
            profile.transport == SpeechProviderTransport.WEBSOCKET_PCM &&
            profile.endpointUrl == LEGACY_DEEPGRAM_ENDPOINT
        ) {
            profile.copy(
                transport = SpeechProviderTransport.HTTP_AUDIO_WAV,
                endpointUrl = CURRENT_DEEPGRAM_ENDPOINT,
            )
        } else {
            profile
        }

    private const val LEGACY_DEEPGRAM_ENDPOINT = "wss://api.deepgram.com/v1/listen"
    private const val CURRENT_DEEPGRAM_ENDPOINT = "https://api.deepgram.com/v1/listen"
}

enum class SpeechProviderErrorCode {
    UNSUPPORTED_SCHEMA_VERSION,
    UNKNOWN_PRESET,
    PRESET_MISMATCH,
    INVALID_DISPLAY_NAME,
    INVALID_ENDPOINT,
    INSECURE_ENDPOINT,
    INVALID_MODEL,
    INVALID_LANGUAGE_TAG,
}

data class SpeechProviderError(
    val code: SpeechProviderErrorCode,
    val path: String,
    val message: String,
)

data class SpeechProviderValidation(
    val errors: List<SpeechProviderError>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

class InvalidSpeechProviderProfileException(
    val errors: List<SpeechProviderError>,
) : IllegalArgumentException(
    errors.joinToString(prefix = "Invalid speech provider profile: ", separator = "; ") {
        "${it.path}: ${it.message}"
    },
)

object SpeechProviderValidator {
    private const val MAX_LABEL_CHARS = 128
    private const val MAX_MODEL_CHARS = 256
    private const val MAX_LANGUAGE_TAG_CHARS = 64
    private val languageTag = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")

    fun validate(profile: SpeechProviderProfile): SpeechProviderValidation {
        val errors = mutableListOf<SpeechProviderError>()
        if (profile.schemaVersion != SpeechProviderProfile.CURRENT_SCHEMA_VERSION) {
            errors += error(
                SpeechProviderErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "$.schema_version",
                "expected ${SpeechProviderProfile.CURRENT_SCHEMA_VERSION}",
            )
        }

        val preset = SpeechProviderPresetCatalog.find(profile.presetId)
        if (preset == null) {
            errors += error(
                SpeechProviderErrorCode.UNKNOWN_PRESET,
                "$.preset_id",
                "is not present in catalog ${SpeechProviderPresetCatalog.CATALOG_VERSION}",
            )
        } else if (preset.transport != profile.transport || preset.protocol != profile.protocol) {
            errors += error(
                SpeechProviderErrorCode.PRESET_MISMATCH,
                "$.protocol",
                "transport and protocol must match the selected preset",
            )
        }

        if (!isBoundedText(profile.displayName, MAX_LABEL_CHARS)) {
            errors += error(
                SpeechProviderErrorCode.INVALID_DISPLAY_NAME,
                "$.display_name",
                "must be non-blank, bounded, and contain no controls",
            )
        }

        if (
            profile.languageTag.length > MAX_LANGUAGE_TAG_CHARS ||
            !languageTag.matches(profile.languageTag)
        ) {
            errors += error(
                SpeechProviderErrorCode.INVALID_LANGUAGE_TAG,
                "$.language_tag",
                "must be a bounded BCP-47 style language tag",
            )
        }

        if (profile.transport == SpeechProviderTransport.ANDROID_SPEECH_RECOGNIZER) {
            if (!profile.endpointUrl.isNullOrBlank() || !profile.model.isNullOrBlank()) {
                errors += error(
                    SpeechProviderErrorCode.PRESET_MISMATCH,
                    "$.endpoint_url",
                    "system recognition must not declare a cloud endpoint or model",
                )
            }
        } else {
            validateCloudEndpoint(profile, errors)
            if (!isBoundedText(profile.model.orEmpty(), MAX_MODEL_CHARS)) {
                errors += error(
                    SpeechProviderErrorCode.INVALID_MODEL,
                    "$.model",
                    "must be non-blank, bounded, and contain no controls",
                )
            }
        }

        return SpeechProviderValidation(errors)
    }

    private fun validateCloudEndpoint(
        profile: SpeechProviderProfile,
        errors: MutableList<SpeechProviderError>,
    ) {
        val uri = try {
            URI(profile.endpointUrl)
        } catch (_: Exception) {
            null
        }
        if (
            uri == null ||
            !uri.isAbsolute ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            errors += error(
                SpeechProviderErrorCode.INVALID_ENDPOINT,
                "$.endpoint_url",
                "must be an absolute endpoint without credentials, query, or fragment",
            )
            return
        }
        val expectedScheme = when (profile.transport) {
            SpeechProviderTransport.ANDROID_SPEECH_RECOGNIZER -> return
            SpeechProviderTransport.HTTP_MULTIPART,
            SpeechProviderTransport.HTTP_AUDIO_WAV,
            -> "https"
            SpeechProviderTransport.WEBSOCKET_PCM -> "wss"
        }
        if (uri.scheme.lowercase(Locale.ROOT) != expectedScheme) {
            errors += error(
                SpeechProviderErrorCode.INSECURE_ENDPOINT,
                "$.endpoint_url",
                "$expectedScheme is required for this transport",
            )
        }
    }

    private fun isBoundedText(value: String, limit: Int): Boolean =
        value.isNotBlank() &&
            value.length <= limit &&
            value.none { it.code < 0x20 || it == '\u007f' }

    private fun error(
        code: SpeechProviderErrorCode,
        path: String,
        message: String,
    ) = SpeechProviderError(code, path, message)
}
