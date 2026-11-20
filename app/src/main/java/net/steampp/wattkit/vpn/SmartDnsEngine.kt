package net.steampp.wattkit.vpn

import net.steampp.wattkit.proxy.DoHResolver
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.proxy.TrafficTelemetry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * In-process DNS engine attached to the virtual TUN gateway (10.0.0.1:53).
 * - Matched acceleration domains: return Fake-IP (198.18.x.y) immediately for A queries,
 *   NODATA for AAAA/HTTPS/SVCB queries (avoiding poisoned IPv6/QUIC bypass), and warm DoH cache.
 * - Unmatched everyday domains: relay directly over a protected UDP socket to physical DNS
 *   servers so non-accelerated traffic keeps 100% native routing and zero disruption.
 */
class SmartDnsEngine(
    private val fakeIpPool: FakeIpPool,
    private val protectUdp: (DatagramSocket) -> Boolean
) {
    @Volatile
    private var upstreamServers: List<String> = listOf("223.5.5.5", "119.29.29.29")

    fun updateUpstreamServers(physicalDnsAddresses: List<String>) {
        val cleaned = mutableListOf<String>()
        val deferredFakeDns = mutableListOf<String>()
        for (addr in physicalDnsAddresses) {
            val ip = addr.trim()
            val ipInt = DnsPacketCodec.ipStringToInt(ip) ?: continue
            // Never loop back to our virtual DNS 10.0.0.1 or loopback 127.0.0.0/8
            if (ip == "10.0.0.1" || ((ipInt ushr 24) and 0xFF) == 127) continue
            if (FakeIpPool.isFakeIp(ipInt)) {
                deferredFakeDns += ip
            } else if (ip !in cleaned) {
                cleaned += ip
            }
        }
        // Always include clean public domestic resolvers after LAN/ISP DNS
        for (pub in listOf("223.5.5.5", "119.29.29.29")) {
            if (pub !in cleaned) cleaned += pub
        }
        // Keep upstream fake-ip DNS (if any, e.g. emulator host bridge) strictly as last resort
        for (f in deferredFakeDns) {
            if (f !in cleaned) cleaned += f
        }
        upstreamServers = cleaned
    }

    fun handleDnsQuery(queryBytes: ByteArray, length: Int): ByteArray? {
        val question = DnsPacketCodec.parseQuestion(queryBytes, length) ?: return null
        val matchedRule = RuleManager.findMatchingRule(question.qName)

        if (matchedRule != null) {
            when (question.qType) {
                DnsPacketCodec.TYPE_A -> {
                    TrafficTelemetry.recordDnsHit(matchedRule.id, question.qName)
                    val fakeIp = fakeIpPool.allocate(question.qName)
                    DoHResolver.prefetch(question.qName)
                    return DnsPacketCodec.buildAResponse(
                        query = queryBytes,
                        queryLen = length,
                        question = question,
                        ipInt = fakeIp,
                        ttlSeconds = 60
                    )
                }
                DnsPacketCodec.TYPE_AAAA,
                DnsPacketCodec.TYPE_HTTPS,
                DnsPacketCodec.TYPE_SVCB -> {
                    return DnsPacketCodec.buildEmptyNoErrorResponse(
                        query = queryBytes,
                        queryLen = length,
                        question = question
                    )
                }
            }
        }

        // Unmatched domain or non-A/AAAA/HTTPS query: forward to physical upstream DNS
        val response = forwardToUpstream(queryBytes, length) ?: return null
        val records = DnsPacketCodec.extractARecords(response, response.size)
        for ((ipInt, _) in records) {
            fakeIpPool.recordSnoopedIp(ipInt, question.qName)
        }
        return response
    }

    private fun forwardToUpstream(queryBytes: ByteArray, length: Int): ByteArray? {
        val servers = upstreamServers
        for (dnsIp in servers) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protectUdp(socket)
                socket.soTimeout = 1800
                val target = InetAddress.getByName(dnsIp)
                socket.send(DatagramPacket(queryBytes, length, target, 53))
                val recvBuf = ByteArray(2048)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPkt)
                if (recvPkt.length >= 12) {
                    return recvBuf.copyOf(recvPkt.length)
                }
            } catch (_: Exception) {
                // Try next upstream DNS server
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }
}
