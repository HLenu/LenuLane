package net.steampp.wattkit.proxy

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import net.steampp.wattkit.vpn.DnsPacketCodec
import net.steampp.wattkit.vpn.FakeIpPool
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Shared TCP relay engine used by both the in-process TUN engine (TunPacketEngine)
 * and the local SOCKS5/HTTP proxy listener (LocalProxyServer).
 */
object ProxyConnectionDispatcher {
    private const val TAG = "ProxyDispatcher"

    suspend fun relayConnection(
        clientIn: InputStream,
        clientOut: OutputStream,
        initialHost: String?,
        fallbackIp: String?,
        targetPort: Int,
        vpnProtect: ((Socket) -> Boolean)?,
        onDownstreamEof: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanInitialHost = initialHost?.takeIf { it.isNotBlank() && DnsPacketCodec.ipStringToInt(it) == null }

        // Only sniff/buffer the first packet on TLS port 443 (or when no hostname is known yet).
        // Non-443 server-speaks-first protocols (e.g. SSH/SMTP/CM) connect immediately without blocking.
        var initialBuffer: ByteArray? = null
        var bytesRead = 0
        var sniffedSni: String? = null
        if (targetPort == 443 || cleanInitialHost == null) {
            val initial = readInitialPacket(clientIn)
            if (initial == null || initial.second <= 0) {
                return@withContext false
            }
            initialBuffer = initial.first
            bytesRead = initial.second
            if (targetPort == 443) {
                sniffedSni = SniSniffer.sniffSni(initialBuffer, bytesRead)
            }
        }

        val isTlsClientHello = sniffedSni != null ||
            (initialBuffer != null && bytesRead >= 6 &&
                (initialBuffer[0].toInt() and 0xFF) == 0x16 &&
                (initialBuffer[5].toInt() and 0xFF) == 1)

        // Priority for logical domain: TLS SNI > Fake-IP/SOCKS/HTTP hostname > fallback IP
        val effectiveDomain = sniffedSni ?: cleanInitialHost
        val matchedRule = effectiveDomain?.let { RuleManager.findMatchingRule(it) }

        val targetSocket = Socket()
        var entryId = -1L
        var success = false
        val rxCounter = AtomicLong(0L)
        val startMs = System.currentTimeMillis()

        try {
            vpnProtect?.invoke(targetSocket)
            targetSocket.tcpNoDelay = true
            targetSocket.soTimeout = 30000

            var resolvedIp = ""
            var useTlsFragment = false

            if (matchedRule != null) {
                val hostDomain = effectiveDomain
                var strategy = matchedRule.strategy
                val upstreamCfg = RuleManager.upstreamProxy.value
                if (strategy == AccelerationStrategy.UPSTREAM_PROXY && !upstreamCfg.enabled) {
                    strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
                }

                when (strategy) {
                    AccelerationStrategy.DOH_TLS_FRAGMENT -> {
                        val dohRes = DoHResolver.resolveWithSource(hostDomain)
                        val destIp = dohRes?.ip
                            ?: usableDirectIp(fallbackIp)
                            ?: throw IOException("DoH resolve failed for $hostDomain")
                        resolvedIp = destIp
                        targetSocket.connect(InetSocketAddress(destIp, targetPort), 8000)
                        useTlsFragment = isTlsClientHello && RuleManager.tlsFragmentEnabled.value
                    }

                    AccelerationStrategy.CLEAN_DNS_DIRECT -> {
                        val dohRes = DoHResolver.resolveWithSource(hostDomain)
                        val destIp = dohRes?.ip
                            ?: usableDirectIp(fallbackIp)
                            ?: throw IOException("DoH resolve failed for $hostDomain")
                        resolvedIp = destIp
                        targetSocket.connect(InetSocketAddress(destIp, targetPort), 8000)
                    }

                    AccelerationStrategy.REVERSE_PROXY_MIRROR -> {
                        val mirrorHost = matchedRule.mirrorHost ?: hostDomain
                        val destPort = matchedRule.targetPort
                        val dohRes = DoHResolver.resolveWithSource(mirrorHost)
                        val destIp = dohRes?.ip ?: mirrorHost
                        resolvedIp = destIp
                        targetSocket.connect(InetSocketAddress(destIp, destPort), 8000)
                    }

                    AccelerationStrategy.UPSTREAM_PROXY -> {
                        resolvedIp = "${upstreamCfg.host}:${upstreamCfg.port}"
                        connectViaUpstreamProxy(
                            socket = targetSocket,
                            proxyHost = upstreamCfg.host,
                            proxyPort = upstreamCfg.port,
                            isSocks5 = upstreamCfg.isSocks5,
                            destHost = hostDomain,
                            destPort = targetPort
                        )
                    }
                }

                val connectLatency = System.currentTimeMillis() - startMs
                entryId = TrafficTelemetry.onConnectionStarted(
                    ruleId = matchedRule.id,
                    ruleName = matchedRule.name,
                    strategyBadge = strategy.badgeText,
                    host = hostDomain,
                    targetIp = resolvedIp,
                    latencyMs = connectLatency
                )
            } else {
                // Direct pass-through (unmatched domain or non-rule traffic in GLOBAL_TUN/SOCKS5)
                val destIp = usableDirectIp(fallbackIp)
                    ?: effectiveDomain?.let { DoHResolver.resolve(it) }
                    ?: throw IOException("No valid destination for ${effectiveDomain ?: fallbackIp}")
                resolvedIp = destIp
                targetSocket.connect(InetSocketAddress(destIp, targetPort), 8000)
                val connectLatency = System.currentTimeMillis() - startMs
                entryId = TrafficTelemetry.onConnectionStarted(
                    ruleId = null,
                    ruleName = "直连透传",
                    strategyBadge = "直连",
                    host = effectiveDomain ?: destIp,
                    targetIp = resolvedIp,
                    latencyMs = connectLatency
                )
            }

            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            // Forward initial payload (fragmented if DOH_TLS_FRAGMENT + ClientHello)
            if (initialBuffer != null && bytesRead > 0) {
                TlsFragmenter.writeFragmented(
                    output = targetOut,
                    data = initialBuffer,
                    length = bytesRead,
                    enableFragment = useTlsFragment
                )
                LocalProxyServer.totalTxBytes.addAndGet(bytesRead.toLong())
                if (entryId > 0L) {
                    TrafficTelemetry.onConnectionBytes(entryId, matchedRule?.id, 0L, bytesRead.toLong())
                }
            }

            coroutineScope {
                val up = launch {
                    pipe(clientIn, targetOut, isRx = false, entryId = entryId, ruleId = matchedRule?.id)
                    try { targetSocket.shutdownOutput() } catch (_: Exception) {}
                }
                val down = launch {
                    pipe(targetIn, clientOut, isRx = true, entryId = entryId, ruleId = matchedRule?.id, rxCounter = rxCounter)
                    try { onDownstreamEof?.invoke() } catch (_: Exception) {}
                }
                down.join()
                withTimeoutOrNull(1500) {
                    up.join()
                }
                if (up.isActive) up.cancel()
            }

            success = true
            return@withContext true
        } catch (e: CancellationException) {
            // Clients (e.g. Chromium keep-alive pool) frequently tear down completed connections via TCP RST.
            // Treat as success if real server payload was already relayed.
            success = rxCounter.get() > 0L
            return@withContext success
        } catch (e: Exception) {
            Log.d(TAG, "Relay error for ${effectiveDomain ?: fallbackIp}:$targetPort -> ${e.message}")
            if (entryId == -1L) {
                val errLatency = System.currentTimeMillis() - startMs
                entryId = TrafficTelemetry.onConnectionStarted(
                    ruleId = matchedRule?.id,
                    ruleName = matchedRule?.name ?: "直连透传",
                    strategyBadge = matchedRule?.strategy?.badgeText ?: "直连",
                    host = effectiveDomain ?: fallbackIp ?: "unknown",
                    targetIp = e.javaClass.simpleName,
                    latencyMs = errLatency
                )
            }
            return@withContext false
        } finally {
            try { targetSocket.close() } catch (_: Exception) {}
            if (entryId > 0L) {
                TrafficTelemetry.onConnectionFinished(
                    entryId = entryId,
                    ruleId = matchedRule?.id,
                    success = success,
                    latencyMs = null
                )
            }
        }
    }

    private fun usableDirectIp(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val ipInt = DnsPacketCodec.ipStringToInt(candidate) ?: return null
        return if (FakeIpPool.isFakeIp(ipInt)) null else candidate
    }

    private fun connectViaUpstreamProxy(
        socket: Socket,
        proxyHost: String,
        proxyPort: Int,
        isSocks5: Boolean,
        destHost: String,
        destPort: Int
    ) {
        socket.connect(InetSocketAddress(proxyHost, proxyPort), 8000)
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        if (isSocks5) {
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val methodReply = ByteArray(2)
            readFully(inp, methodReply)
            if (methodReply[0] != 5.toByte() || methodReply[1] != 0.toByte()) {
                throw IOException("Upstream SOCKS5 auth rejected")
            }

            val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
            if (hostBytes.size > 255) throw IOException("Host too long for SOCKS5")
            val req = ByteArray(4 + 1 + hostBytes.size + 2)
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3 // CONNECT, DOMAIN
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = (destPort shr 8).toByte()
            req[6 + hostBytes.size] = (destPort and 0xFF).toByte()
            out.write(req)
            out.flush()

            val hdr = ByteArray(4)
            readFully(inp, hdr)
            if (hdr[1] != 0.toByte()) {
                throw IOException("Upstream SOCKS5 CONNECT failed: rep=${hdr[1].toInt() and 0xFF}")
            }
            val skipLen = when (hdr[3].toInt() and 0xFF) {
                1 -> 4 + 2
                3 -> {
                    val domainLen = inp.read()
                    if (domainLen < 0) throw EOFException()
                    domainLen + 2
                }
                4 -> 16 + 2
                else -> throw IOException("Invalid SOCKS5 ATYP")
            }
            val discard = ByteArray(skipLen)
            readFully(inp, discard)
        } else {
            val connectLine = "CONNECT $destHost:$destPort HTTP/1.1\r\nHost: $destHost:$destPort\r\nProxy-Connection: keep-alive\r\n\r\n"
            out.write(connectLine.toByteArray(Charsets.US_ASCII))
            out.flush()
            val responseHeader = readHttpHeaders(inp)
            if (!responseHeader.contains(" 200 ")) {
                throw IOException("Upstream HTTP CONNECT rejected: ${responseHeader.lineSequence().firstOrNull()}")
            }
        }
    }

    private fun readHttpHeaders(input: InputStream): String {
        val buf = ByteArray(2048)
        var len = 0
        while (len < buf.size) {
            val b = input.read()
            if (b < 0) break
            buf[len++] = b.toByte()
            if (len >= 4 &&
                buf[len - 4] == '\r'.code.toByte() &&
                buf[len - 3] == '\n'.code.toByte() &&
                buf[len - 2] == '\r'.code.toByte() &&
                buf[len - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return String(buf, 0, len, Charsets.US_ASCII)
    }

    fun readInitialPacket(input: InputStream, cap: Int = 5 + 16384 + 2048): Pair<ByteArray, Int>? {
        var buffer = ByteArray(4096)
        var bytesRead = 0

        while (bytesRead < 5) {
            val n = input.read(buffer, bytesRead, 5 - bytesRead)
            if (n < 0) return if (bytesRead > 0) Pair(buffer, bytesRead) else null
            bytesRead += n
        }

        val isTlsRecord = (buffer[0].toInt() and 0xFF) == 0x16 &&
            (buffer[1].toInt() and 0xFF) == 3
        val target = if (isTlsRecord) {
            val recordLen = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
            (5 + recordLen).coerceAtMost(cap)
        } else {
            5
        }

        if (target > buffer.size) buffer = buffer.copyOf(target)

        while (bytesRead < target) {
            val n = input.read(buffer, bytesRead, target - bytesRead)
            if (n < 0) break
            bytesRead += n
        }

        if (!isTlsRecord) {
            while (input.available() > 0 && bytesRead < buffer.size) {
                val n = input.read(buffer, bytesRead, buffer.size - bytesRead)
                if (n < 0) break
                bytesRead += n
            }
        }

        return Pair(buffer, bytesRead)
    }

    private fun pipe(
        input: InputStream,
        output: OutputStream,
        isRx: Boolean,
        entryId: Long,
        ruleId: String?,
        rxCounter: AtomicLong? = null
    ) {
        val buffer = ByteArray(16384)
        try {
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                if (n == 0) continue
                output.write(buffer, 0, n)
                output.flush()
                if (isRx) {
                    rxCounter?.addAndGet(n.toLong())
                    LocalProxyServer.totalRxBytes.addAndGet(n.toLong())
                    if (entryId > 0L) TrafficTelemetry.onConnectionBytes(entryId, ruleId, n.toLong(), 0L)
                } else {
                    LocalProxyServer.totalTxBytes.addAndGet(n.toLong())
                    if (entryId > 0L) TrafficTelemetry.onConnectionBytes(entryId, ruleId, 0L, n.toLong())
                }
            }
        } catch (_: Exception) {
        }
    }

    fun readFully(input: InputStream, b: ByteArray) {
        var offset = 0
        while (offset < b.size) {
            val count = input.read(b, offset, b.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }
}
