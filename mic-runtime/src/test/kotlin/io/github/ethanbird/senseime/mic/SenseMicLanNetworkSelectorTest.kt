package io.github.ethanbird.senseime.mic

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenseMicLanNetworkSelectorTest {
    @Test
    fun wifiIsSelectedUnderVpnInsteadOfFollowingDefaultRoute() {
        val selected = SenseMicLanNetworkSelector.select(
            listOf(
                candidate("vpn", vpn = true, address = "10.8.0.2", prefix = 24),
                candidate("cellular", address = "100.64.10.4", prefix = 24),
                candidate("wifi", wifi = true, address = "192.168.31.58", prefix = 24),
            ),
        )

        assertEquals("wifi", selected?.value)
    }

    @Test
    fun networkContainingRemoteAddressOutranksOtherPhysicalTransports() {
        val selected = SenseMicLanNetworkSelector.select(
            candidates = listOf(
                candidate("wifi-a", wifi = true, address = "192.168.1.20", prefix = 24),
                candidate("ethernet-b", ethernet = true, address = "10.20.30.4", prefix = 24),
            ),
            remoteAddress = address("10.20.30.99"),
        )

        assertEquals("ethernet-b", selected?.value)
    }

    @Test
    fun selectorRejectsVpnLoopbackAndLinkLocalOnlyCandidates() {
        val selected = SenseMicLanNetworkSelector.select(
            listOf(
                candidate("vpn", vpn = true, address = "192.168.1.4", prefix = 24),
                candidate("loopback", address = "127.0.0.1", prefix = 8),
                candidate("link-local", address = "169.254.2.3", prefix = 16),
            ),
        )

        assertNull(selected)
    }

    @Test
    fun subnetMatchingSupportsNonOctetPrefixLengths() {
        val selected = SenseMicLanNetworkSelector.select(
            candidates = listOf(
                candidate("wrong", wifi = true, address = "10.10.32.3", prefix = 20),
                candidate("right", address = "10.10.31.4", prefix = 20),
            ),
            remoteAddress = address("10.10.31.200"),
        )

        assertEquals("right", selected?.value)
    }

    private fun candidate(
        name: String,
        wifi: Boolean = false,
        ethernet: Boolean = false,
        vpn: Boolean = false,
        address: String,
        prefix: Int,
    ): SenseMicLanNetworkCandidate<String> = SenseMicLanNetworkCandidate(
        value = name,
        isWifi = wifi,
        isEthernet = ethernet,
        isVpn = vpn,
        links = listOf(SenseMicLanLink(address(address), prefix)),
    )

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
