package net.steampp.wattkit.proxy

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class LocalProxyServer(
    private val port: Int = 28282,
    private val vpnService: VpnService
) {
    companion object {
        val totalRxBytes = AtomicLong(0L)
        val totalTxBytes = AtomicLong(0L)
    }

    private val tag = "LocalProxyServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port, 256, InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                }
                Log.i(tag, "Local SOCKS5 Proxy listening on 127.0.0.1:$port")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Proxy server error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. SOCKS5 Method Negotiation
            val ver = input.read()
            if (ver != 5) {
                client.close()
                return@withContext
            }
            val nMethods = input.read()
            if (nMethods <= 0) {
                client.close()
                return@withContext
            }
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            // Reply: Ver 5, No Auth (0)
            output.write(byteArrayOf(5, 0))
            output.flush()

            // 2. Request Details
            val reqVer = input.read()
            if (reqVer != 5) {
                replyError(output)
                client.close()
                return@withContext
            }
            val cmd = input.read()
            input.read() // RSVP
            val atyp = input.read()

            var targetHost = ""

            when (atyp) {
                1 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    input.readFully(ipBytes)
                    val targetIp = InetAddress.getByAddress(ipBytes)
                    targetHost = targetIp.hostAddress ?: ""
                }
                3 -> { // Domain name
                    val len = input.read()
                    val domainBytes = ByteArray(len)
                    input.readFully(domainBytes)
                    targetHost = String(domainBytes, Charsets.UTF_8)
                }
                4 -> { // IPv6
                    val ip6Bytes = ByteArray(16)
                    input.readFully(ip6Bytes)
                    val targetIp = InetAddress.getByAddress(ip6Bytes)
                    targetHost = targetIp.hostAddress ?: ""
                }
                else -> {
                    client.close()
                    return@withContext
                }
            }

            val portHigh = input.read()
            val portLow = input.read()
            val targetPort = (portHigh shl 8) or portLow

            if (cmd == 1) { // CONNECT
                handleConnect(client, input, output, targetHost, targetPort)
            } else {
                // UDP ASSOCIATE is not supported (no UDP relay is running).
                // Replying success with a fake relay port would leave clients
                // hanging, so explicitly reject with "command not supported".
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun replyError(output: OutputStream) {
        try {
            output.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)) // general SOCKS server failure
            output.flush()
        } catch (_: Exception) {}
    }

    private suspend fun handleConnect(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        initialHost: String,
        targetPort: Int
    ) = withContext(Dispatchers.IO) {
        val targetSocket = Socket()
        try {
            // Reply SOCKS5 Success immediately to receive client initial packet (e.g. TLS ClientHello)
            clientOut.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (targetPort shr 8).toByte(), (targetPort and 0xFF).toByte()))
            clientOut.flush()

            // Read the initial payload packet from client
            val initial = readInitialPacket(clientIn)
            if (initial == null || initial.second <= 0) {
                client.close()
                return@withContext
            }
            val initialBuffer = initial.first
            val bytesRead = initial.second

            var destHost = initialHost
            var destPort = targetPort

            // If port 443, sniff SNI from ClientHello
            if (targetPort == 443) {
                val sniffedSni = SniSniffer.sniffSni(initialBuffer, bytesRead)
                if (sniffedSni != null) {
                    val redirect = RuleManager.findMatchingTarget(sniffedSni, targetPort)
                    if (redirect != null) {
                        destHost = redirect.first
                        destPort = redirect.second
                        Log.i(tag, "Accelerating SNI: $sniffedSni -> $destHost:$destPort")
                    } else {
                        destHost = sniffedSni
                    }
                } else {
                    // Check direct rule match with initialHost
                    val redirect = RuleManager.findMatchingTarget(initialHost, targetPort)
                    if (redirect != null) {
                        destHost = redirect.first
                        destPort = redirect.second
                        Log.i(tag, "Accelerating Host: $initialHost -> $destHost:$destPort")
                    }
                }
            } else {
                val redirect = RuleManager.findMatchingTarget(initialHost, targetPort)
                if (redirect != null) {
                    destHost = redirect.first
                    destPort = redirect.second
                }
            }

            // Protect outbound socket from VPN loop
            vpnService.protect(targetSocket)
            targetSocket.tcpNoDelay = true
            targetSocket.connect(InetSocketAddress(destHost, destPort), 10000)

            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            // Forward initial packet
            targetOut.write(initialBuffer, 0, bytesRead)
            targetOut.flush()

            // Bi-directional pipe: job1 forwards client upload (Tx), job2 forwards server download (Rx)
            val job1 = launch { pipe(clientIn, targetOut, isRx = false) }
            val job2 = launch { pipe(targetIn, clientOut, isRx = true) }

            job1.join()
            job2.join()
        } catch (_: Exception) {
        } finally {
            try { targetSocket.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Reads the first payload the client sends after the SOCKS5 handshake.
     *
     * A TLS ClientHello frequently spans multiple TCP segments (or exceeds
     * 4KB with post-quantum key shares), so when the data looks like a TLS
     * handshake record we keep reading until the full record length declared
     * in the 5-byte TLS record header has been received (or a sane cap /
     * timeout is hit). This prevents SNI sniffing from failing on fragmented
     * hellos, which would silently bypass acceleration rules.
     *
     * Returns (buffer, bytesRead) or null on EOF/error.
     */
    private fun readInitialPacket(input: InputStream, cap: Int = 5 + 16384 + 2048): Pair<ByteArray, Int>? {
        var buffer = ByteArray(4096)
        var bytesRead = 0

        // First chunk (blocking, bounded by socket soTimeout)
        while (bytesRead < 5) {
            val n = input.read(buffer, bytesRead, 5 - bytesRead)
            if (n < 0) return if (bytesRead > 0) Pair(buffer, bytesRead) else null
            bytesRead += n
        }

        // Does it look like a TLS handshake record? (contentType 0x16, major version 3)
        val isTlsRecord = (buffer[0].toInt() and 0xFF) == 0x16 &&
                (buffer[1].toInt() and 0xFF) == 3
        var target = if (isTlsRecord) {
            val recordLen = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
            (5 + recordLen).coerceAtMost(cap)
        } else {
            5
        }

        if (target > buffer.size) buffer = buffer.copyOf(target)

        // Read up to the target length; for non-TLS traffic this loop does not
        // run (target == 5), so we simply forward whatever arrived first.
        while (bytesRead < target) {
            val n = input.read(buffer, bytesRead, target - bytesRead)
            if (n < 0) break
            bytesRead += n
        }

        // For non-TLS first packets, opportunistically drain what is already
        // available without blocking, so nothing gets truncated.
        if (!isTlsRecord) {
            while (input.available() > 0 && bytesRead < buffer.size) {
                val n = input.read(buffer, bytesRead, buffer.size - bytesRead)
                if (n < 0) break
                bytesRead += n
            }
        }

        return Pair(buffer, bytesRead)
    }

    private fun pipe(input: InputStream, output: OutputStream, isRx: Boolean) {
        val buffer = ByteArray(16384)
        try {
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                output.write(buffer, 0, n)
                output.flush()
                if (isRx) {
                    totalRxBytes.addAndGet(n.toLong())
                } else {
                    totalTxBytes.addAndGet(n.toLong())
                }
            }
        } catch (_: Exception) {}
    }

    private fun InputStream.readFully(b: ByteArray) {
        var offset = 0
        while (offset < b.size) {
            val count = read(b, offset, b.size - offset)
            if (count < 0) throw java.io.EOFException()
            offset += count
        }
    }
}
