package net.steampp.wattkit.vpn

import net.steampp.wattkit.proxy.DoHResolver
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.proxy.TrafficTelemetry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process DNS engine attached to the virtual TUN gateway (10.0.0.1:53).
 * - Matched acceleration domains: return Fake-IP (198.18.x.y) immediately for A queries,
 *   NODATA for AAAA/HTTPS/SVCB queries, and prefetch DoH records.
 * - Unmatched everyday domains: served from a 512-entry TTL wire cache on repeat hits (0ms),
 *   or relayed over a protected UDP socket with fast failover.
 */
class SmartDnsEngine(
    private val fakeIpPool: FakeIpPool,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val upstreamForwarder: ((ByteArray, Int, List<String>) -> ByteArray?)? = null
) {
    companion object {
        private const val MAX_DNS_CACHE_SIZE = 512
        private const val DEFAULT_CACHE_TTL_SEC = 120
    }

    private data class DnsCacheKey(val qType: Int, val qName: String)
    private data class DnsCacheEntry(val wireResponse: ByteArray, val expiresAtMs: Long)

    private val cacheLock = Any()
    private val wireCache = object : LinkedHashMap<DnsCacheKey, DnsCacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DnsCacheKey, DnsCacheEntry>?): Boolean =
            size > MAX_DNS_CACHE_SIZE
    }

    val cacheHits = AtomicLong(0L)
    val cacheMisses = AtomicLong(0L)

    @Volatile
    private var upstreamServers: List<String> = listOf("223.5.5.5", "119.29.29.29")

    @Volatile
    private var lastGoodUpstream: String? = null

    fun clearCache() {
        synchronized(cacheLock) {
            wireCache.clear()
        }
    }

    fun updateUpstreamServers(physicalDnsAddresses: List<String>) {
        val cleaned = mutableListOf<String>()
        val deferredFakeDns = mutableListOf<String>()
        for (addr in physicalDnsAddresses) {
            val ip = addr.trim()
            val ipInt = DnsPacketCodec.ipStringToInt(ip) ?: continue
            if (ip == "10.0.0.1" || ((ipInt ushr 24) and 0xFF) == 127) continue
            if (FakeIpPool.isFakeIp(ipInt)) {
                deferredFakeDns += ip
            } else if (ip !in cleaned) {
                cleaned += ip
            }
        }
        for (pub in listOf("223.5.5.5", "119.29.29.29")) {
            if (pub !in cleaned) cleaned += pub
        }
        for (f in deferredFakeDns) {
            if (f !in cleaned) cleaned += f
        }
        upstreamServers = cleaned
        if (lastGoodUpstream !in cleaned) {
            lastGoodUpstream = null
        }
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

        // Unmatched everyday domain: check in-memory wire cache first (0ms latency)
        val isCacheable = question.qType == DnsPacketCodec.TYPE_A || question.qType == DnsPacketCodec.TYPE_AAAA
        val cacheKey = if (isCacheable) DnsCacheKey(question.qType, question.qName) else null
        if (cacheKey != null) {
            val now = System.currentTimeMillis()
            val cached = synchronized(cacheLock) {
                wireCache[cacheKey]?.takeIf { now < it.expiresAtMs }
            }
            if (cached != null) {
                cacheHits.incrementAndGet()
                val copy = cached.wireResponse.copyOf()
                copy[0] = queryBytes[0]
                copy[1] = queryBytes[1]
                return copy
            }
            cacheMisses.incrementAndGet()
        }

        val orderedServers = orderedUpstreamList()
        val response = upstreamForwarder?.invoke(queryBytes, length, orderedServers)
            ?: forwardToUpstream(queryBytes, length, orderedServers)
            ?: return null

        val records = DnsPacketCodec.extractARecords(response, response.size)
        for ((ipInt, _) in records) {
            fakeIpPool.recordSnoopedIp(ipInt, question.qName)
        }

        if (cacheKey != null && response.size >= 12) {
            val rcode = response[3].toInt() and 0x0F
            if (rcode == 0) {
                val minTtl = records.minOfOrNull { it.second }?.coerceIn(30, 300) ?: DEFAULT_CACHE_TTL_SEC
                synchronized(cacheLock) {
                    wireCache[cacheKey] = DnsCacheEntry(
                        wireResponse = response.copyOf(),
                        expiresAtMs = System.currentTimeMillis() + minTtl * 1000L
                    )
                }
            }
        }
        return response
    }

    private fun orderedUpstreamList(): List<String> {
        val servers = upstreamServers
        val preferred = lastGoodUpstream
        return if (preferred != null && preferred in servers && servers.firstOrNull() != preferred) {
            listOf(preferred) + servers.filter { it != preferred }
        } else {
            servers
        }
    }

    private fun forwardToUpstream(queryBytes: ByteArray, length: Int, ordered: List<String>): ByteArray? {
        for (dnsIp in ordered) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protectUdp(socket)
                socket.soTimeout = 550
                val target = InetAddress.getByName(dnsIp)
                socket.send(DatagramPacket(queryBytes, length, target, 53))
                val recvBuf = ByteArray(2048)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPkt)
                if (recvPkt.length >= 12) {
                    lastGoodUpstream = dnsIp
                    return recvBuf.copyOf(recvPkt.length)
                }
            } catch (_: Exception) {
                if (lastGoodUpstream == dnsIp) {
                    lastGoodUpstream = null
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }
}
