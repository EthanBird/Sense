package io.github.ethanbird.senseime.mic

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseMicDiscoveryEndpointTest {
    @Test
    fun socketBindsWildcardAndInvokesNetworkBindingBeforeLocalBind() {
        var networkBindingSawUnboundSocket = false
        SenseMicDiscoveryEndpoint.openSocket(
            port = 0,
            timeoutMillis = 50,
            bindToNetwork = { socket -> networkBindingSawUnboundSocket = !socket.isBound },
        ).use { socket ->
            assertTrue(networkBindingSawUnboundSocket)
            assertTrue(socket.isBound)
            assertTrue(socket.localAddress.isAnyLocalAddress)
            assertTrue(socket.broadcast)
        }
    }

    @Test
    fun malformedDatagramIsObservedAndNextValidRequestReceivesResponse() {
        val observer = RecordingObserver()
        val running = AtomicBoolean(true)
        val endpointSocket = SenseMicDiscoveryEndpoint.openSocket(port = 0, timeoutMillis = 50)
        val endpoint = SenseMicDiscoveryEndpoint(
            socket = endpointSocket,
            createResponse = { nonce ->
                SenseMicWireCodec.encodeDiscoveryResponse(
                    SenseMicDiscoveryResponse(
                        controlPort = SenseMicProtocol.CONTROL_PORT,
                        deviceId = 42L,
                        requestNonce = nonce,
                        serverNonce = ByteArray(16) { 7 },
                        serverPublicKey = byteArrayOf(1, 2, 3),
                        deviceName = "Sense JVM",
                    ),
                )
            },
            observer = observer,
        )
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit { endpoint.run(running::get) }

        DatagramSocket().use { client ->
            client.soTimeout = 2_000
            val destination = InetSocketAddress(InetAddress.getLoopbackAddress(), endpointSocket.localPort)
            client.send(DatagramPacket(byteArrayOf(1, 2, 3), 3, destination))

            val nonce = ByteArray(16) { it.toByte() }
            val request = SenseMicWireCodec.encodeDiscoveryRequest(nonce)
            client.send(DatagramPacket(request, request.size, destination))

            val responseBytes = ByteArray(512)
            val response = DatagramPacket(responseBytes, responseBytes.size)
            client.receive(response)
            val decoded = requireNotNull(
                SenseMicWireCodec.decodeDiscoveryResponse(response.data, response.length),
            )
            assertArrayEquals(nonce, decoded.requestNonce)
            assertEquals("Sense JVM", decoded.deviceName)
        }

        running.set(false)
        endpoint.close()
        future.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue(endpointSocket.isClosed)
        assertEquals(2, observer.received.size)
        assertEquals(1, observer.rejected.size)
        assertEquals(1, observer.responded.size)
        assertTrue(observer.failures.isEmpty())
    }

    @Test
    fun closingEndpointUnblocksReceiveWithoutReportingFailure() {
        val observer = RecordingObserver()
        val running = AtomicBoolean(true)
        val socket = SenseMicDiscoveryEndpoint.openSocket(port = 0, timeoutMillis = 5_000)
        val endpoint = SenseMicDiscoveryEndpoint(socket, { error("unused") }, observer)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit { endpoint.run(running::get) }

        endpoint.close()
        running.set(false)
        future.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue(socket.isClosed)
        assertFalse(observer.failures.isNotEmpty())
    }

    @Test
    fun deviceNameUsesUtf8ByteBudgetWithoutSplittingUnicode() {
        val fitted = fitSenseMicDiscoveryDeviceName("手机🎙️".repeat(20))

        assertTrue(fitted.encodeToByteArray().size <= 63)
        assertFalse(fitted.contains('\uFFFD'))
        assertTrue(fitted.isNotBlank())
    }

    private class RecordingObserver : SenseMicDiscoveryObserver {
        val received = CopyOnWriteArrayList<InetSocketAddress>()
        val rejected = CopyOnWriteArrayList<InetSocketAddress>()
        val responded = CopyOnWriteArrayList<InetSocketAddress>()
        val failures = CopyOnWriteArrayList<Pair<SenseMicDiscoveryStage, Throwable>>()

        override fun onReceived(remote: InetSocketAddress, byteCount: Int) {
            received += remote
        }

        override fun onRejected(remote: InetSocketAddress, byteCount: Int) {
            rejected += remote
        }

        override fun onResponded(remote: InetSocketAddress, byteCount: Int) {
            responded += remote
        }

        override fun onFailure(
            stage: SenseMicDiscoveryStage,
            remote: InetSocketAddress?,
            error: Throwable,
        ) {
            failures += stage to error
        }
    }
}
