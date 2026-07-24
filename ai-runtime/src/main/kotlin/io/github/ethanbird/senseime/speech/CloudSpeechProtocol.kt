package io.github.ethanbird.senseime.speech

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale

enum class CloudSpeechAuthorizationScheme(
    internal val headerPrefix: String,
) {
    BEARER("Bearer "),
    DEEPGRAM_TOKEN("Token "),
}

enum class CloudSpeechFailureKind {
    CANCELLED,
    PERMISSION_DENIED,
    RECORDER_UNAVAILABLE,
    NO_AUDIO,
    UNSUPPORTED_PROVIDER,
    INVALID_CONFIGURATION,
    AUTHENTICATION,
    QUOTA,
    RATE_LIMIT,
    NETWORK,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    PROTOCOL,
    SERVER,
    INTERNAL,
}

data class CloudSpeechFailure(
    val kind: CloudSpeechFailureKind,
    val httpStatus: Int? = null,
    val message: String,
) {
    override fun toString(): String =
        "CloudSpeechFailure(kind=$kind, httpStatus=$httpStatus, message=$message)"
}

data class CloudSpeechTranscript(
    val text: String,
    val alternatives: List<String> = emptyList(),
)

/**
 * A secret-free HTTP request plan.
 *
 * The API key is deliberately absent and is supplied to the HTTPS transport only for the lifetime
 * of one call. [toString] never renders audio bytes.
 */
class CloudSpeechHttpRequest internal constructor(
    val endpointUrl: String,
    val contentType: String,
    val authorizationScheme: CloudSpeechAuthorizationScheme,
    val protocol: SpeechProviderProtocol,
    internal val body: ByteArray,
) {
    val method: String
        get() = "POST"

    val contentLength: Int
        get() = body.size

    internal fun eraseBody() {
        body.fill(0)
    }

    override fun toString(): String =
        "CloudSpeechHttpRequest(method=$method, endpointUrl=$endpointUrl, " +
            "contentType=$contentType, authorizationScheme=$authorizationScheme, " +
            "protocol=$protocol, body=<redacted:${body.size}>)"
}

fun interface SpeechMultipartBoundaryFactory {
    fun create(): String
}

object SecureSpeechMultipartBoundaryFactory : SpeechMultipartBoundaryFactory {
    private val random = SecureRandom()

    override fun create(): String {
        val entropy = ByteArray(18)
        random.nextBytes(entropy)
        return buildString(5 + entropy.size * 2) {
            append("Sense")
            entropy.forEach { byte ->
                append(HEX[(byte.toInt() ushr 4) and 0x0f])
                append(HEX[byte.toInt() and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}

data class SpeechMultipartBody(
    val contentType: String,
    val bytes: ByteArray,
)

object SpeechMultipartEncoder {
    private const val MAX_BOUNDARY_CHARS = 70
    private const val MAX_FIELD_CHARS = 512
    private const val MAX_FILE_BYTES =
        Pcm16WavEncoder.HEADER_BYTES +
            Pcm16AudioFormat.SAMPLE_RATE_HZ *
            Pcm16AudioFormat.BYTES_PER_SAMPLE *
            Pcm16AudioFormat.ABSOLUTE_MAX_DURATION_MILLIS /
            1_000
    private val validBoundary = Regex("^[A-Za-z0-9'()+_,./:=?-]{16,70}$")
    private val validName = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun encode(
        boundary: String,
        fields: List<Pair<String, String>>,
        fileFieldName: String,
        fileName: String,
        fileContentType: String,
        fileBytes: ByteArray,
    ): SpeechMultipartBody {
        require(
            boundary.length <= MAX_BOUNDARY_CHARS && validBoundary.matches(boundary),
        ) { "invalid multipart boundary" }
        require(validName.matches(fileFieldName)) { "invalid multipart file field name" }
        require(isSafeHeaderValue(fileName, 128)) { "invalid multipart filename" }
        require(isSafeHeaderValue(fileContentType, 128)) { "invalid multipart file type" }
        require(fileBytes.size <= MAX_FILE_BYTES) { "multipart file exceeds size ceiling" }
        require(fields.size <= 16) { "too many multipart fields" }
        fields.forEach { (name, value) ->
            require(validName.matches(name)) { "invalid multipart field name" }
            require(isSafeFieldValue(value)) { "invalid multipart field value" }
        }

        val marker = "--$boundary".toByteArray(StandardCharsets.US_ASCII)
        require(!fileBytes.containsSequence(marker)) {
            "multipart boundary collides with binary payload"
        }

        val estimatedSize = fileBytes.size + fields.sumOf { it.second.length } + 1_024
        val output = ByteArrayOutputStream(estimatedSize)
        fields.forEach { (name, value) ->
            output.writeAscii("--$boundary\r\n")
            output.writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            output.writeUtf8(value)
            output.writeAscii("\r\n")
        }
        output.writeAscii("--$boundary\r\n")
        output.writeAscii(
            "Content-Disposition: form-data; name=\"$fileFieldName\"; " +
                "filename=\"$fileName\"\r\n",
        )
        output.writeAscii("Content-Type: $fileContentType\r\n\r\n")
        output.write(fileBytes)
        output.writeAscii("\r\n--$boundary--\r\n")
        return SpeechMultipartBody(
            contentType = "multipart/form-data; boundary=$boundary",
            bytes = output.toByteArray(),
        )
    }

    private fun isSafeFieldValue(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_FIELD_CHARS &&
            value.none { it == '\r' || it == '\n' || it.code < 0x20 || it == '\u007f' }

    private fun isSafeHeaderValue(value: String, maxChars: Int): Boolean =
        value.isNotBlank() &&
            value.length <= maxChars &&
            value.none {
                it == '\r' ||
                    it == '\n' ||
                    it == '"' ||
                    it == '\\' ||
                    it.code < 0x20 ||
                    it == '\u007f'
            }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        outer@ for (start in 0..size - sequence.size) {
            for (index in sequence.indices) {
                if (this[start + index] != sequence[index]) continue@outer
            }
            return true
        }
        return false
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }
}

object CloudSpeechRequestFactory {
    private const val MAX_BOUNDARY_ATTEMPTS = 4
    private const val MAX_REQUEST_BYTES =
        Pcm16WavEncoder.HEADER_BYTES +
            Pcm16AudioFormat.SAMPLE_RATE_HZ *
            Pcm16AudioFormat.BYTES_PER_SAMPLE *
            Pcm16AudioFormat.ABSOLUTE_MAX_DURATION_MILLIS /
            1_000 +
            4_096

    fun create(
        profile: SpeechProviderProfile,
        wavAudio: Pcm16WavAudio,
        boundaryFactory: SpeechMultipartBoundaryFactory =
            SecureSpeechMultipartBoundaryFactory,
    ): Result<CloudSpeechHttpRequest> = runCatching {
        profile.requireValid()
        val preset = SpeechProviderPresetCatalog.require(profile.presetId)
        require(preset.runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE) {
            "speech provider adapter is not available"
        }
        require(wavAudio.pcmByteCount > 0) { "audio is empty" }
        require(wavAudio.bytes.size <= MAX_REQUEST_BYTES) { "audio exceeds request ceiling" }

        when (profile.protocol) {
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS ->
                createOpenAiCompatible(profile, wavAudio, boundaryFactory)
            SpeechProviderProtocol.DEEPGRAM_LISTEN ->
                createDeepgram(profile, wavAudio)
            SpeechProviderProtocol.ANDROID_SYSTEM ->
                error("system speech recognition does not use the cloud request factory")
            SpeechProviderProtocol.DASHSCOPE_REALTIME ->
                error("DashScope realtime adapter is configuration-only")
        }.also {
            require(it.contentLength <= MAX_REQUEST_BYTES) { "request exceeds size ceiling" }
        }
    }

    private fun createOpenAiCompatible(
        profile: SpeechProviderProfile,
        wavAudio: Pcm16WavAudio,
        boundaryFactory: SpeechMultipartBoundaryFactory,
    ): CloudSpeechHttpRequest {
        var failure: IllegalArgumentException? = null
        repeat(MAX_BOUNDARY_ATTEMPTS) {
            val boundary = boundaryFactory.create()
            val multipart = try {
                SpeechMultipartEncoder.encode(
                    boundary = boundary,
                    fields = listOf(
                        "model" to requireNotNull(profile.model),
                        "language" to profile.languageTag
                            .substringBefore('-')
                            .lowercase(Locale.ROOT),
                    ),
                    fileFieldName = "file",
                    fileName = "speech.wav",
                    fileContentType = "audio/wav",
                    fileBytes = wavAudio.bytes,
                )
            } catch (error: IllegalArgumentException) {
                failure = error
                return@repeat
            }
            return CloudSpeechHttpRequest(
                endpointUrl = requireNotNull(profile.endpointUrl),
                contentType = multipart.contentType,
                authorizationScheme = CloudSpeechAuthorizationScheme.BEARER,
                protocol = profile.protocol,
                body = multipart.bytes,
            )
        }
        throw failure ?: IllegalArgumentException("could not create safe multipart boundary")
    }

    private fun createDeepgram(
        profile: SpeechProviderProfile,
        wavAudio: Pcm16WavAudio,
    ): CloudSpeechHttpRequest {
        val query = buildList {
            add("model" to requireNotNull(profile.model))
            add("language" to profile.languageTag)
            if (profile.punctuation) add("smart_format" to "true")
        }.joinToString("&") { (name, value) ->
            "${encodeQuery(name)}=${encodeQuery(value)}"
        }
        return CloudSpeechHttpRequest(
            endpointUrl = "${requireNotNull(profile.endpointUrl)}?$query",
            contentType = "audio/wav",
            authorizationScheme = CloudSpeechAuthorizationScheme.DEEPGRAM_TOKEN,
            protocol = profile.protocol,
            body = wavAudio.bytes.copyOf(),
        )
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
