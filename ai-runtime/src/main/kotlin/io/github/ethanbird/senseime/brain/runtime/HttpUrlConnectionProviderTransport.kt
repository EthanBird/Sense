package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.ProviderCall
import io.github.ethanbird.senseime.brain.api.ProviderFailureKind
import io.github.ethanbird.senseime.brain.api.ProviderResponseMetadata
import io.github.ethanbird.senseime.brain.api.ProviderStreamSink
import io.github.ethanbird.senseime.brain.api.ProviderTransport
import io.github.ethanbird.senseime.brain.api.ProviderTransportFailure
import io.github.ethanbird.senseime.brain.api.ProviderWireRequest
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SDK-free HTTP bridge used only inside the private :brain process.
 *
 * There is intentionally no Provider SDK dependency and no logging. Disconnecting the active
 * HttpURLConnection closes a blocking SSE read on Android and complements the harness generation
 * gate; cancellation correctness never depends on the socket closing in time.
 */
class HttpUrlConnectionProviderTransport(
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "sense-ai-http").apply { isDaemon = true }
    },
) : ProviderTransport, AutoCloseable {
    override fun open(
        request: ProviderWireRequest,
        sink: ProviderStreamSink,
    ): ProviderCall {
        val call = ConnectionCall(request, sink)
        executor.execute(call::run)
        return call
    }

    override fun close() {
        executor.shutdownNow()
    }

    private class ConnectionCall(
        private val request: ProviderWireRequest,
        private val sink: ProviderStreamSink,
    ) : ProviderCall {
        private val cancelled = AtomicBoolean(false)
        private val connection = AtomicReference<HttpURLConnection?>()

        fun run() {
            var opened = false
            try {
                if (cancelled.get()) return
                validateEndpoint(request.url)
                val active = (URL(request.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = request.method
                    connectTimeout = request.connectTimeoutMs
                    readTimeout = request.readTimeoutMs
                    doInput = true
                    doOutput = true
                    useCaches = false
                    instanceFollowRedirects = false
                    request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    setFixedLengthStreamingMode(request.body.size)
                }
                connection.set(active)
                if (cancelled.get()) {
                    active.disconnect()
                    return
                }
                active.outputStream.use { stream -> stream.write(request.body) }
                val status = active.responseCode
                opened = true
                if (cancelled.get()) return
                sink.onOpen(
                    ProviderResponseMetadata(
                        statusCode = status,
                        contentType = active.contentType,
                        requestId = active.getHeaderField("x-request-id"),
                    ),
                )
                val body = if (status in 200..299) active.inputStream else active.errorStream
                if (body != null) {
                    body.use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0
                        while (!cancelled.get()) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > MAX_RESPONSE_BYTES) {
                                fail(
                                    ProviderFailureKind.RESPONSE_TOO_LARGE,
                                    "Provider response exceeded the bounded transport limit",
                                )
                                return
                            }
                            sink.onBytes(buffer, 0, read)
                        }
                    }
                }
                if (!cancelled.get()) sink.onComplete()
            } catch (error: SocketTimeoutException) {
                if (!cancelled.get()) {
                    fail(
                        if (opened) ProviderFailureKind.READ_TIMEOUT
                        else ProviderFailureKind.CONNECT_TIMEOUT,
                        "Provider timed out",
                        retryable = true,
                    )
                }
            } catch (_: InterruptedIOException) {
                if (!cancelled.get()) {
                    fail(ProviderFailureKind.IO, "Provider stream was interrupted", retryable = true)
                }
            } catch (error: Throwable) {
                if (!cancelled.get()) {
                    fail(
                        ProviderFailureKind.IO,
                        error.javaClass.simpleName.ifBlank { "Provider I/O failed" },
                        retryable = true,
                    )
                }
            } finally {
                connection.getAndSet(null)?.disconnect()
            }
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                connection.getAndSet(null)?.disconnect()
            }
        }

        private fun fail(
            kind: ProviderFailureKind,
            message: String,
            retryable: Boolean = false,
        ) {
            sink.onFailure(
                ProviderTransportFailure(
                    kind = kind,
                    message = message,
                    retryable = retryable,
                ),
            )
        }

        companion object {
            private fun validateEndpoint(value: String) {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                val secure = scheme == "https"
                val loopback = scheme == "http" &&
                    (host == "localhost" || host == "127.0.0.1" || host == "::1")
                require(secure || loopback) { "Only HTTPS or explicit loopback endpoints are allowed" }
            }
        }
    }

    companion object {
        private const val BUFFER_BYTES = 4 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
