package net.steampp.wattkit.proxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.steampp.wattkit.vpn.DnsPacketCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class DoHProvider(
    val id: String,
    val displayName: String,
    val endpointUrl: String,
    val bootstrapIp: String,
    val useWireFormat: Boolean = false
) {
    ALIDNS("alidns", "阿里 DoH (223.5.5.5)", "https://223.5.5.5/resolve", "223.5.5.5", false),
    DNSPOD("dnspod", "腾讯 DoH (1.12.12.12)", "https://1.12.12.12/resolve", "119.29.29.29", false),
    QIHOO360("360doh", "360 DoH (101.226.4.6)", "https://101.226.4.6/resolve", "101.226.4.6", false),
    CLOUDFLARE("cloudflare", "Cloudflare DoH (1.1.1.1)", "https://1.1.1.1/dns-query", "1.1.1.1", true);

    companion object {
        fun fromId(id: String?): DoHProvider =
            entries.firstOrNull { it.id == id } ?: DNSPOD
    }
}

data class ResolvedDnsResult(
    val ip: String,
    val source: String,
    val ttlSeconds: Int
)

/**
 * Custom SocketFactory that marks every outbound socket via VpnService.protect()
 * so DoH HTTP calls and DNS probes bypass the TUN interface and never loop back.
 */
class ProtectedSocketFactory(
    private val protectProvider: () -> ((Socket) -> Boolean)?
) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also {
        bindForFd(it)
        protect(it)
    }

    override fun createSocket(host: String, port: Int): Socket =
        Socket().also {
            bindForFd(it)
            protect(it)
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket().also {
            it.bind(InetSocketAddress(localHost, localPort))
            protect(it)
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket().also {
            bindForFd(it)
            protect(it)
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        Socket().also {
            it.bind(InetSocketAddress(localAddress, localPort))
            protect(it)
            it.connect(InetSocketAddress(address, port))
        }

    private fun bindForFd(socket: Socket) {
        try {
            socket.bind(null)
        } catch (_: Exception) {}
    }

    private fun protect(socket: Socket) {
        val cb = protectProvider() ?: return
        if (!cb(socket)) {
            Log.w("ProtectedSocketFactory", "VpnService.protect() returned false")
        }
    }
}

/**
 * Anti-pollution DoH Resolver with TTL cache, bogon/GFW-poison filtering,
 * protected UDP DNS fallback, and curated CDN Anycast edge IPs.
 */
object DoHResolver {
    private const val TAG = "DoHResolver"
    private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

    private data class CachedEntry(
        val ips: List<String>,
        val source: String,
        val ttlSeconds: Int,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val inFlightLocks = ConcurrentHashMap<String, Any>()
    private val ipScores = ConcurrentHashMap<String, Long>()
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var activeProvider: DoHProvider = DoHProvider.DNSPOD

    @Volatile
    private var tcpProtectCallback: ((Socket) -> Boolean)? = null

    @Volatile
    private var udpProtectCallback: ((DatagramSocket) -> Boolean)? = null

    @Volatile
    private var cloudflareFallbackCooldownUntilMs: Long = 0L

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(ProtectedSocketFactory { tcpProtectCallback })
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Verified official CDN Anycast edge IPs (empirically verified against strict TLS 1.3 certs).
     * Used only when upstream DoH/UDP returns poisoned IPs or times out.
     */
    val fallbackIps: Map<String, List<String>> = mapOf(
        // Steam Akamai/Fastly Edge
        "steamcommunity.com" to listOf("104.116.16.214", "184.25.121.15"),
        "www.steamcommunity.com" to listOf("104.116.16.214", "184.25.121.15"),
        "store.steampowered.com" to listOf("23.15.142.182", "23.41.38.92"),
        "api.steampowered.com" to listOf("23.35.148.148", "23.35.148.140"),
        "login.steampowered.com" to listOf("23.35.148.148", "23.15.142.182"),
        "checkout.steampowered.com" to listOf("23.15.142.182", "23.35.148.148"),
        "help.steampowered.com" to listOf("23.15.142.182", "104.116.16.214"),
        "community.steamstatic.com" to listOf("146.75.47.52"),
        "avatars.steamstatic.com" to listOf("146.75.47.52"),
        "shared.cloudflare.steamstatic.com" to listOf("146.75.47.52"),
        "clan.cloudflare.steamstatic.com" to listOf("146.75.47.52"),

        // Pixiv Cloudflare / Fastly Edge
        "i.pximg.net" to listOf("210.140.139.130", "210.140.139.133", "104.18.42.239", "172.64.145.17"),
        "s.pximg.net" to listOf("210.140.139.130", "104.18.42.239"),
        "pixiv.net" to listOf("210.140.139.161", "210.140.139.155", "104.18.42.239"),
        "www.pixiv.net" to listOf("104.18.42.239", "172.64.145.17"),
        "accounts.pixiv.net" to listOf("104.18.42.239", "172.64.145.17"),
        "touch.pixiv.net" to listOf("104.18.42.239"),
        "fanbox.cc" to listOf("104.18.42.239"),
        "www.fanbox.cc" to listOf("104.18.42.239"),

        // GitHub Edge
        "github.com" to listOf("20.205.243.166", "140.82.114.4"),
        "api.github.com" to listOf("20.205.243.168", "140.82.114.6"),
        "raw.githubusercontent.com" to listOf("185.199.108.133", "185.199.109.133"),
        "codeload.github.com" to listOf("20.205.243.165"),

        // Discord Cloudflare Edge
        "discord.com" to listOf("162.159.136.232", "162.159.135.232", "162.159.128.233", "162.159.137.232", "162.159.138.232"),
        "discord.gg" to listOf("162.159.136.232", "162.159.128.233"),
        "gateway.discord.gg" to listOf("162.159.135.234", "162.159.137.232"),
        "cdn.discordapp.com" to listOf("162.159.133.233", "162.159.128.233")
    )

    fun setVpnProtectCallbacks(
        tcpProtect: ((Socket) -> Boolean)?,
        udpProtect: ((DatagramSocket) -> Boolean)?
    ) {
        tcpProtectCallback = tcpProtect
        udpProtectCallback = udpProtect
    }

    fun protectTcpSocket(socket: Socket) {
        tcpProtectCallback?.invoke(socket)
    }

    fun clearCache() {
        cache.clear()
        ipScores.clear()
        cloudflareFallbackCooldownUntilMs = 0L
    }

    /**
     * Benchmarks every configured DoH endpoint concurrently against a reference domain.
     * Returns latency in milliseconds (>= 0) or -1L if the endpoint failed/timed out.
     */
    suspend fun benchmarkAllProviders(probeDomain: String = "github.com"): Map<DoHProvider, Long> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                DoHProvider.entries.map { provider ->
                    async {
                        val t0 = System.currentTimeMillis()
                        val res = queryProvider(provider, probeDomain)
                        val elapsed = if (res != null && res.first.isNotEmpty()) {
                            (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        } else {
                            -1L
                        }
                        provider to elapsed
                    }
                }.awaitAll().toMap()
            }
        }

    fun prefetch(hostname: String) {
        val key = hostname.lowercase().trimEnd('.')
        val now = System.currentTimeMillis()
        val cached = cache[key]
        if (cached != null && now < cached.expiresAtMs) return
        prefetchScope.launch {
            resolveWithSource(key)
        }
    }

    fun resolve(hostname: String): String? = resolveWithSource(hostname)?.ip

    fun resolveWithSource(hostname: String): ResolvedDnsResult? =
        resolveCandidates(hostname).firstOrNull()

    fun resolveCandidates(hostname: String): List<ResolvedDnsResult> {
        val clean = hostname.lowercase().trim().trimEnd('.')
        if (clean.isEmpty()) return emptyList()

        // Literal IPv4 address -> validate and return immediately
        DnsPacketCodec.ipStringToInt(clean)?.let { ipInt ->
            if (!isPoisonedOrBogon(ipInt)) {
                return listOf(ResolvedDnsResult(clean, "IP 直连", 300))
            }
            return emptyList()
        }

        val now = System.currentTimeMillis()
        cache[clean]?.takeIf { now < it.expiresAtMs && it.ips.isNotEmpty() }?.let { cached ->
            return cached.ips.map { ResolvedDnsResult(it, cached.source, cached.ttlSeconds) }
        }

        // In-flight deduplication per domain: burst connections share a single DoH lookup
        val domainLock = inFlightLocks.computeIfAbsent(clean) { Any() }
        synchronized(domainLock) {
            val freshNow = System.currentTimeMillis()
            cache[clean]?.takeIf { freshNow < it.expiresAtMs && it.ips.isNotEmpty() }?.let { cached ->
                return cached.ips.map { ResolvedDnsResult(it, cached.source, cached.ttlSeconds) }
            }

            val edgePool = findFallbackIps(clean).orEmpty()
            var pickedSource = ""
            var pickedTtl = 120
            val discoveredIps = mutableListOf<String>()

            // 1. Primary: Active DoH Provider
            val provider = activeProvider
            queryProvider(provider, clean)?.let { (ips, src, ttl) ->
                discoveredIps.addAll(ips)
                pickedSource = src
                pickedTtl = ttl
            }

            // 2. Cross-provider DoH fallback (skip Cloudflare if recently timed out)
            if (discoveredIps.isEmpty()) {
                val alternates = listOf(DoHProvider.DNSPOD, DoHProvider.ALIDNS, DoHProvider.QIHOO360, DoHProvider.CLOUDFLARE)
                    .filter { it != provider }
                for (alt in alternates) {
                    if (alt == DoHProvider.CLOUDFLARE && System.currentTimeMillis() < cloudflareFallbackCooldownUntilMs) {
                        continue
                    }
                    val altRes = queryProvider(alt, clean)
                    if (altRes != null) {
                        discoveredIps.addAll(altRes.first)
                        pickedSource = altRes.second
                        pickedTtl = altRes.third
                        break
                    }
                    if (alt == DoHProvider.CLOUDFLARE) {
                        cloudflareFallbackCooldownUntilMs = System.currentTimeMillis() + 60_000L
                    }
                }
            }

            // 3. Protected UDP/53 query to clean domestic resolvers
            if (discoveredIps.isEmpty()) {
                for (udpDns in listOf("223.5.5.5", "119.29.29.29")) {
                    val udpRes = queryUdpDns(clean, udpDns)
                    if (udpRes != null) {
                        discoveredIps.addAll(udpRes.first)
                        pickedSource = udpRes.second
                        pickedTtl = udpRes.third
                        break
                    }
                }
            }

            if (discoveredIps.isEmpty()) {
                pickedSource = "CDN Edge 池"
            }
            // Merge verified CDN Edge fallback IPs after live DoH IPs and sort by EWMA health score
            val mergedList = mergeAndRankCandidates(clean, discoveredIps, edgePool)
            if (mergedList.isEmpty()) return emptyList()
            cacheResult(clean, mergedList, pickedSource, pickedTtl)
            return mergedList.map { ResolvedDnsResult(it, pickedSource, pickedTtl) }
        }
    }

    internal fun mergeAndRankCandidates(
        hostname: String,
        discoveredIps: List<String>,
        fallbackEdgePool: List<String>
    ): List<String> {
        val clean = hostname.lowercase().trim().trimEnd('.')
        val combined = (discoveredIps + fallbackEdgePool).distinct()
        if (combined.size <= 1) return combined
        val indexed = combined.withIndex().toList()
        return indexed.sortedWith { a, b ->
            val sa = ipScores[scoreKey(clean, a.value)] ?: 5_000L
            val sb = ipScores[scoreKey(clean, b.value)] ?: 5_000L
            val cmp = sa.compareTo(sb)
            if (cmp != 0) cmp else a.index.compareTo(b.index)
        }.map { it.value }
    }

    /**
     * Records a successful TCP + TLS handshake RTT so the lowest-latency healthy IP rises to #1.
     */
    fun recordIpSuccess(hostname: String, ip: String, rttMs: Long) {
        val clean = hostname.lowercase().trim().trimEnd('.')
        if (clean.isEmpty() || ip.isBlank()) return
        val k = scoreKey(clean, ip)
        val clamped = rttMs.coerceIn(1L, 10_000L)
        val prev = ipScores[k]
        val updated = if (prev == null || prev >= 50_000L) {
            clamped
        } else {
            ((prev * 7L) + (clamped * 3L)) / 10L
        }
        ipScores[k] = updated
        val domainLock = inFlightLocks.computeIfAbsent(clean) { Any() }
        synchronized(domainLock) {
            val cached = cache[clean] ?: return
            if (cached.ips.size <= 1) return
            cache[clean] = cached.copy(ips = mergeAndRankCandidates(clean, cached.ips, emptyList()))
        }
    }

    /**
     * Demotes a candidate IP that failed TCP connect or suffered an immediate TLS handshake reset,
     * promoting the next healthy candidate IP for subsequent connections.
     */
    fun markIpFailed(hostname: String, failedIp: String) {
        val clean = hostname.lowercase().trim().trimEnd('.')
        if (clean.isEmpty() || failedIp.isBlank()) return
        ipScores[scoreKey(clean, failedIp)] = 60_000L
        val domainLock = inFlightLocks.computeIfAbsent(clean) { Any() }
        synchronized(domainLock) {
            val cached = cache[clean] ?: return
            if (cached.ips.size <= 1) return
            val reordered = cached.ips.filter { it != failedIp } + failedIp
            cache[clean] = cached.copy(ips = reordered)
        }
    }

    private fun scoreKey(cleanHost: String, ip: String): String = "$cleanHost|$ip"

    private fun findFallbackIps(host: String): List<String>? {
        fallbackIps[host]?.let { return it }
        // Wildcard check for *.steamstatic.com / *.pximg.net / *.github.com
        if (host.endsWith(".steamstatic.com")) return fallbackIps["community.steamstatic.com"]
        if (host.endsWith(".pximg.net")) return fallbackIps["i.pximg.net"]
        if (host.endsWith(".steampowered.com")) return fallbackIps["store.steampowered.com"]
        if (host.endsWith(".steamcommunity.com")) return fallbackIps["steamcommunity.com"]
        return null
    }

    private fun cacheResult(host: String, ips: List<String>, source: String, ttlSeconds: Int) {
        val ttlMs = ttlSeconds.coerceIn(30, 600) * 1000L
        cache[host] = CachedEntry(
            ips = ips,
            source = source,
            ttlSeconds = ttlSeconds,
            expiresAtMs = System.currentTimeMillis() + ttlMs
        )
    }

    private fun queryProvider(provider: DoHProvider, domain: String): Triple<List<String>, String, Int>? {
        return try {
            if (provider.useWireFormat) {
                val wireQuery = DnsPacketCodec.buildQuery(domain, DnsPacketCodec.TYPE_A, 0x2026)
                val req = Request.Builder()
                    .url(provider.endpointUrl)
                    .post(wireQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                    .header("Accept", "application/dns-message")
                    .header("User-Agent", "LenuLane/1.4.0")
                    .build()
                dohHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val bytes = resp.body?.bytes() ?: return null
                    val records = DnsPacketCodec.extractARecords(bytes, bytes.size)
                    val validIps = mutableListOf<String>()
                    var bestTtl = 60
                    for ((ipInt, ttl) in records) {
                        if (!isPoisonedOrBogon(ipInt)) {
                            validIps += DnsPacketCodec.ipIntToString(ipInt)
                            if (validIps.size == 1) bestTtl = ttl
                        }
                    }
                    if (validIps.isEmpty()) null else Triple(validIps, provider.displayName, bestTtl)
                }
            } else {
                val encoded = URLEncoder.encode(domain, "UTF-8")
                val url = "${provider.endpointUrl}?name=$encoded&type=1"
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "LenuLane/1.4.0")
                    .build()
                dohHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body?.string() ?: return null
                    val json = JSONObject(body)
                    if (json.optInt("Status", -1) != 0) return null
                    val answers = json.optJSONArray("Answer") ?: return null
                    val validIps = mutableListOf<String>()
                    var bestTtl = 60
                    for (i in 0 until answers.length()) {
                        val ans = answers.optJSONObject(i) ?: continue
                        if (ans.optInt("type") != 1) continue
                        val data = ans.optString("data", "").trim()
                        val ttl = ans.optInt("TTL", 60).coerceIn(15, 3600)
                        if (!isPoisonedOrBogon(data)) {
                            validIps += data
                            if (validIps.size == 1) bestTtl = ttl
                        }
                    }
                    if (validIps.isEmpty()) null else Triple(validIps, provider.displayName, bestTtl)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "${provider.displayName} resolve($domain) failed: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "${provider.displayName} parse($domain) error: ${e.message}")
            null
        }
    }

    private fun queryUdpDns(domain: String, dnsServerIp: String): Triple<List<String>, String, Int>? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (udpProtectCallback?.invoke(socket) == false) return null
            socket.soTimeout = 1000
            val wireQuery = DnsPacketCodec.buildQuery(domain, DnsPacketCodec.TYPE_A, (System.nanoTime() and 0xFFFF).toInt())
            val serverAddr = InetAddress.getByName(dnsServerIp)
            socket.connect(serverAddr, 53)
            socket.send(DatagramPacket(wireQuery, wireQuery.size, serverAddr, 53))
            val buf = ByteArray(1024)
            val respPkt = DatagramPacket(buf, buf.size)
            socket.receive(respPkt)
            val question = DnsPacketCodec.parseQuestion(wireQuery) ?: return null
            if (!DnsPacketCodec.matchesResponse(question, buf, respPkt.length)) return null
            val records = DnsPacketCodec.extractARecords(buf, respPkt.length)
            val validIps = mutableListOf<String>()
            var bestTtl = 60
            for ((ipInt, ttl) in records) {
                if (!isPoisonedOrBogon(ipInt)) {
                    validIps += DnsPacketCodec.ipIntToString(ipInt)
                    if (validIps.size == 1) bestTtl = ttl
                }
            }
            if (validIps.isEmpty()) null else Triple(validIps, "UDP DNS ($dnsServerIp)", bestTtl)
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun isPoisonedOrBogon(ipString: String): Boolean {
        val ipInt = DnsPacketCodec.ipStringToInt(ipString) ?: return true
        return isPoisonedOrBogon(ipInt)
    }

    /**
     * Filters unroutable/private/bogon ranges AND well-known GFW DNS-poisoned fake IPs
     * (cross-service Twitter/Facebook/static bogus sinkholes) returned by unencrypted domestic resolvers.
     */
    fun isPoisonedOrBogon(ipInt: Int): Boolean {
        val b0 = (ipInt ushr 24) and 0xFF
        val b1 = (ipInt ushr 16) and 0xFF
        val b2 = (ipInt ushr 8) and 0xFF
        val b3 = ipInt and 0xFF

        // RFC 1122 / 1918 / 3927 / 6598 / 2544 / Multicast / Broadcast
        if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) return true
        if (b0 == 169 && b1 == 254) return true
        if (b0 == 172 && b1 in 16..31) return true
        if (b0 == 192 && b1 == 168) return true
        if (b0 == 100 && b1 in 64..127) return true
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return true

        // Known GFW DNS poison sinkhole ranges & hijacked third-party IPs
        if (b0 == 104 && b1 == 244 && b2 in 40..47) return true // Spoofed Twitter AS13414 block
        if (b0 == 199 && b1 == 16 && b2 in 156..159) return true // Spoofed Twitter block
        if (b0 == 199 && b1 == 59 && b2 in 148..151) return true
        if (b0 == 199 && b1 == 96 && b2 in 56..63) return true // Spoofed Twitter AS35995 block
        if (b0 == 31 && b1 == 13 && b2 in 64..127) return true // Spoofed Meta IP
        if (b0 == 157 && b1 == 240) return true // Spoofed Meta IP
        if (b0 == 69 && b1 == 63 && b2 in 176..191) return true // Spoofed Meta AS32934 IP
        if (b0 == 69 && b1 == 171 && b2 in 224..255) return true
        if (b0 == 66 && b1 == 220 && b2 in 144..159) return true
        if (b0 == 128 && b1 == 121) return true // Spoofed Twitter/GFW block (incl. 128.121.243.x / .146.x)
        if (b0 == 108 && b1 == 160 && b2 in 160..175) return true // Spoofed Dropbox/GFW block (incl. 108.160.169.x / .165.x)
        if (b0 == 103 && b1 == 252 && b2 == 115) return true // Spoofed Meta/Pixiv poison IP
        if (b0 == 103 && b1 == 97 && b2 == 176 && b3 == 73) return true // Hijacked domestic 103.97.176.73 for Pixiv
        if ((b0 == 46 && b1 == 82 && b2 == 174 && b3 == 68) ||
            (b0 == 59 && b1 == 24 && b2 == 3) ||
            (b0 == 93 && b1 == 46 && b2 == 8) ||
            (b0 == 203 && b1 == 98 && b2 == 7 && b3 == 65) ||
            (b0 == 159 && b1 == 106 && b2 == 121 && b3 == 75) ||
            (b0 == 243 && b1 == 185 && b2 == 187 && b3 == 39)
        ) return true

        return false
    }
}
