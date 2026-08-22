package io.github.ethanbird.senseime.mic

import java.net.Inet4Address
import java.net.InetAddress

/** Android-free snapshot used to select the physical LAN below a possible VPN default route. */
internal data class SenseMicLanNetworkCandidate<T>(
    val value: T,
    val isWifi: Boolean,
    val isEthernet: Boolean,
    val isVpn: Boolean,
    val links: List<SenseMicLanLink>,
)

internal data class SenseMicLanLink(
    val address: InetAddress,
    val prefixLength: Int,
)

internal object SenseMicLanNetworkSelector {
    fun <T> select(
        candidates: List<SenseMicLanNetworkCandidate<T>>,
        remoteAddress: InetAddress? = null,
    ): SenseMicLanNetworkCandidate<T>? {
        val remoteV4 = remoteAddress as? Inet4Address
        return candidates.withIndex()
            .mapNotNull { indexed ->
                val candidate = indexed.value
                val usableLinks = candidate.links.filter(::isUsableIpv4Link)
                if (candidate.isVpn || usableLinks.isEmpty()) return@mapNotNull null
                val remoteMatch = remoteV4 != null && usableLinks.any { link ->
                    sameIpv4Subnet(link.address as Inet4Address, remoteV4, link.prefixLength)
                }
                RankedCandidate(
                    candidate = candidate,
                    score = when {
                        remoteMatch -> 1_000
                        candidate.isWifi -> 200
                        candidate.isEthernet -> 150
                        else -> 100
                    } + if (usableLinks.any { it.address.isSiteLocalAddress }) 10 else 0,
                    order = indexed.index,
                )
            }
            .maxWithOrNull(compareBy<RankedCandidate<T>> { it.score }.thenBy { -it.order })
            ?.candidate
    }

    fun usableIpv4Addresses(candidate: SenseMicLanNetworkCandidate<*>): List<Inet4Address> =
        candidate.links.asSequence()
            .filter(::isUsableIpv4Link)
            .map { it.address as Inet4Address }
            .distinctBy { it.hostAddress }
            .toList()

    private fun isUsableIpv4Link(link: SenseMicLanLink): Boolean =
        link.address is Inet4Address &&
            link.prefixLength in 1..32 &&
            !link.address.isAnyLocalAddress &&
            !link.address.isLoopbackAddress &&
            !link.address.isLinkLocalAddress

    private fun sameIpv4Subnet(first: Inet4Address, second: Inet4Address, prefixLength: Int): Boolean {
        val wholeBytes = prefixLength / Byte.SIZE_BITS
        val remainingBits = prefixLength % Byte.SIZE_BITS
        val firstBytes = first.address
        val secondBytes = second.address
        for (index in 0 until wholeBytes) {
            if (firstBytes[index] != secondBytes[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xff shl (Byte.SIZE_BITS - remainingBits)) and 0xff
        return (firstBytes[wholeBytes].toInt() and mask) ==
            (secondBytes[wholeBytes].toInt() and mask)
    }

    private data class RankedCandidate<T>(
        val candidate: SenseMicLanNetworkCandidate<T>,
        val score: Int,
        val order: Int,
    )
}
