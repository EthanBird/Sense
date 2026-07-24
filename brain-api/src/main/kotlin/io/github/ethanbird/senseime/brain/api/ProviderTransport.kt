package io.github.ethanbird.senseime.brain.api

/**
 * Ephemeral request credential. Persisting and retrieving it is an Android integration concern.
 */
sealed interface ProviderCredential {
    data class Bearer(val token: String) : ProviderCredential {
        init {
            require(token.isNotBlank()) { "bearer token must not be blank" }
            require(token.length <= MAX_BEARER_TOKEN_CHARS) {
                "bearer token must not exceed $MAX_BEARER_TOKEN_CHARS characters"
            }
            require(token.none { it.code < 0x20 || it == '\u007f' }) {
                "bearer token must not contain control characters"
            }
        }

        override fun toString(): String = "Bearer(**redacted**)"

        private companion object {
            const val MAX_BEARER_TOKEN_CHARS = 8_192
        }
    }

    data object None : ProviderCredential
}

data class ProviderWireRequest(
    val requestId: String,
    val attempt: Int,
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String>,
    val body: ByteArray,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
) {
    init {
        require(requestId.isNotBlank())
        require(attempt in 0..1)
        require(method == "POST")
        require(connectTimeoutMs > 0)
        require(readTimeoutMs > 0)
    }

    override fun toString(): String =
        "ProviderWireRequest(requestId=$requestId, attempt=$attempt, url=$url, method=$method, " +
            "headers=${headers.mapValues { (name, value) ->
                if (name.equals("Authorization", ignoreCase = true)) "**redacted**" else value
            }}, bodyBytes=${body.size}, connectTimeoutMs=$connectTimeoutMs, " +
            "readTimeoutMs=$readTimeoutMs)"
}

data class ProviderResponseMetadata(
    val statusCode: Int,
    val contentType: String?,
    val requestId: String? = null,
)

enum class ProviderFailureKind {
    CANCELLED,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    HTTP_STATUS,
    IO,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    INTERNAL,
}

data class ProviderTransportFailure(
    val kind: ProviderFailureKind,
    val message: String,
    val retryable: Boolean = false,
    val statusCode: Int? = null,
)

/**
 * Provider-neutral asynchronous byte transport.
 *
 * [open] must return promptly without performing a blocking network exchange. Implementations may
 * use HttpURLConnection on a private executor, Cronet, OkHttp, or a Binder proxy. They must deliver
 * callbacks in order for one call, but the brain still rejects callbacks after cancel/repair.
 */
fun interface ProviderTransport {
    fun open(request: ProviderWireRequest, sink: ProviderStreamSink): ProviderCall
}

fun interface ProviderCall {
    /** Idempotently aborts socket reads and prevents unnecessary work. */
    fun cancel()
}

interface ProviderStreamSink {
    fun onOpen(metadata: ProviderResponseMetadata)

    fun onBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    fun onComplete()

    fun onFailure(failure: ProviderTransportFailure)
}

object CompletedProviderCall : ProviderCall {
    override fun cancel() = Unit
}
