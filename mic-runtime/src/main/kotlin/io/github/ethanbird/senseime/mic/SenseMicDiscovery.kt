package io.github.ethanbird.senseime.mic

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/** The observable stages of one discovery request. */
internal enum class SenseMicDiscoveryStage {
    RECEIVE,
    DECODE,
    BUILD_RESPONSE,
    SEND,
}

/** Small callback surface so discovery can be diagnosed without coupling the wire loop to Android. */
internal interface SenseMicDiscoveryObserver {
    fun onReceived(remote: InetSocketAddress, byteCount: Int) = Unit
    fun onRejected(remote: InetSocketAddress, byteCount: Int) = Unit
    fun onResponded(remote: InetSocketAddress, byteCount: Int) = Unit
    fun onFailure(stage: SenseMicDiscoveryStage, remote: InetSocketAddress?, error: Throwable) = Unit
}

/**
 * JVM-testable UDP discovery endpoint.
 *
 * Socket construction and Android [android.net.Network] binding stay outside this class. The loop
 * handles timeouts as cancellation points, rejects malformed datagrams without terminating the
 * service, and treats actual receive/send failures as terminal so callers can surface a clear
 * runtime error instead of spinning on a broken descriptor.
 */
internal class SenseMicDiscoveryEndpoint(
    private val socket: DatagramSocket,
    private val createResponse: (requestNonce: ByteArray) -> ByteArray,
    private val observer: SenseMicDiscoveryObserver,
) : AutoCloseable {
    fun run(shouldContinue: () -> Boolean) {
        val buffer = ByteArray(MAX_DISCOVERY_DATAGRAM_BYTES)
        while (shouldContinue() && !Thread.currentThread().isInterrupted) {
            val request = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(request)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: SocketException) {
                if (socket.isClosed || !shouldContinue()) return
                observer.onFailure(SenseMicDiscoveryStage.RECEIVE, null, error)
                throw error
            } catch (error: Exception) {
                observer.onFailure(SenseMicDiscoveryStage.RECEIVE, null, error)
                throw error
            }

            val remote = InetSocketAddress(request.address, request.port)
            observer.onReceived(remote, request.length)
            val requestNonce = SenseMicWireCodec.decodeDiscoveryRequest(request.data, request.length)
            if (requestNonce == null) {
                observer.onRejected(remote, request.length)
                continue
            }

            val response = try {
                createResponse(requestNonce)
            } catch (error: Exception) {
                observer.onFailure(SenseMicDiscoveryStage.BUILD_RESPONSE, remote, error)
                throw error
            }
            try {
                socket.send(DatagramPacket(response, response.size, remote))
            } catch (error: Exception) {
                if (socket.isClosed || !shouldContinue()) return
                observer.onFailure(SenseMicDiscoveryStage.SEND, remote, error)
                throw error
            }
            observer.onResponded(remote, response.size)
        }
    }

    override fun close() {
        socket.close()
    }

    internal companion object {
        private const val MAX_DISCOVERY_DATAGRAM_BYTES = 512

        fun openSocket(
            port: Int,
            bindAddress: InetAddress = ipv4AnyAddress(),
            timeoutMillis: Int = 1_000,
            bindToNetwork: (DatagramSocket) -> Unit = {},
        ): DatagramSocket = DatagramSocket(null).apply {
            try {
                reuseAddress = true
                broadcast = true
                bindToNetwork(this)
                bind(InetSocketAddress(bindAddress, port))
                soTimeout = timeoutMillis
            } catch (error: Exception) {
                close()
                throw error
            }
        }

        private fun ipv4AnyAddress(): InetAddress =
            InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
    }
}

/** Fits Build.MODEL into the protocol's UTF-8 byte budget without splitting a code point. */
internal fun fitSenseMicDiscoveryDeviceName(
    value: String,
    fallback: String = "Sense Android",
    maxUtf8Bytes: Int = 63,
): String {
    require(maxUtf8Bytes > 0)
    fitUtf8Prefix(value.trim(), maxUtf8Bytes).takeIf(String::isNotBlank)?.let { return it }
    fitUtf8Prefix(fallback.trim(), maxUtf8Bytes).takeIf(String::isNotBlank)?.let { return it }
    return "S".take(maxUtf8Bytes)
}

private fun fitUtf8Prefix(source: String, maxUtf8Bytes: Int): String {
    val output = StringBuilder()
    var outputBytes = 0
    var index = 0
    while (index < source.length) {
        val codePoint = source.codePointAt(index)
        val chars = Character.toChars(codePoint)
        val byteCount = String(chars).encodeToByteArray().size
        if (outputBytes + byteCount > maxUtf8Bytes) break
        output.append(chars)
        outputBytes += byteCount
        index += Character.charCount(codePoint)
    }
    return output.toString()
}
