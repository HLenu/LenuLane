package net.steampp.wattkit.proxy

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import net.steampp.wattkit.vpn.DnsPacketCodec
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Dual-protocol (SOCKS5 + HTTP CONNECT) local proxy listener on 127.0.0.1:28282.
 * Useful for browser/terminal/ADB proxy chaining alongside or independently of TUN mode,
 * sharing ProxyConnectionDispatcher with TunPacketEngine.
 */
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

    @Volatile
    private var isRunning = false
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(32)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port, 256, InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                }
                Log.i(tag, "Local SOCKS5/HTTP proxy listening on 127.0.0.1:$port")

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
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 15000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val firstByte = input.read()
            if (firstByte < 0) {
                client.close()
                return@withContext
            }

            if (firstByte == 5) {
                handleSocks5(client, input, output)
            } else {
                handleHttpConnect(client, firstByte, input, output)
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private suspend fun handleSocks5(client: Socket, input: InputStream, output: OutputStream) {
        val nMethods = input.read()
        if (nMethods <= 0) {
            client.close()
            return
        }
        val methods = ByteArray(nMethods)
        ProxyConnectionDispatcher.readFully(input, methods)

        // Reply: Ver 5, No Auth (0)
        output.write(byteArrayOf(5, 0))
        output.flush()

        val reqVer = input.read()
        if (reqVer != 5) {
            replySocks5Error(output, 1)
            client.close()
            return
        }
        val cmd = input.read()
        input.read() // RSV
        val atyp = input.read()

        val targetHost = when (atyp) {
            1 -> { // IPv4
                val ipBytes = ByteArray(4)
                ProxyConnectionDispatcher.readFully(input, ipBytes)
                InetAddress.getByAddress(ipBytes).hostAddress ?: ""
            }
            3 -> { // Domain name
                val len = input.read()
                if (len <= 0) {
                    replySocks5Error(output, 8)
                    client.close()
                    return
                }
                val domainBytes = ByteArray(len)
                ProxyConnectionDispatcher.readFully(input, domainBytes)
                String(domainBytes, Charsets.UTF_8)
            }
            4 -> { // IPv6
                val ip6Bytes = ByteArray(16)
                ProxyConnectionDispatcher.readFully(input, ip6Bytes)
                InetAddress.getByAddress(ip6Bytes).hostAddress ?: ""
            }
            else -> {
                replySocks5Error(output, 8)
                client.close()
                return
            }
        }

        val portHigh = input.read()
        val portLow = input.read()
        if (portHigh < 0 || portLow < 0) {
            client.close()
            return
        }
        val targetPort = (portHigh shl 8) or portLow

        if (cmd != 1) {
            // UDP ASSOCIATE / BIND not supported on SOCKS5 listener; explicitly reply Command Not Supported (0x07)
            replySocks5Error(output, 7)
            client.close()
            return
        }

        // Reply SOCKS5 success immediately so client streams initial payload
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (targetPort shr 8).toByte(), (targetPort and 0xFF).toByte()))
        output.flush()

        val fallbackIp = if (DnsPacketCodec.ipStringToInt(targetHost) != null) targetHost else null
        ProxyConnectionDispatcher.relayConnection(
            clientIn = input,
            clientOut = output,
            initialHost = targetHost,
            fallbackIp = fallbackIp,
            targetPort = targetPort,
            vpnProtect = { vpnService.protect(it) },
            onDownstreamEof = { runCatching { client.shutdownOutput() } }
        )
        runCatching { client.close() }
    }

    private suspend fun handleHttpConnect(
        client: Socket,
        firstByte: Int,
        input: InputStream,
        output: OutputStream
    ) {
        val headerBuf = ByteArray(4096)
        headerBuf[0] = firstByte.toByte()
        var len = 1
        while (len < headerBuf.size) {
            val b = input.read()
            if (b < 0) break
            headerBuf[len++] = b.toByte()
            if (len >= 4 &&
                headerBuf[len - 4] == '\r'.code.toByte() &&
                headerBuf[len - 3] == '\n'.code.toByte() &&
                headerBuf[len - 2] == '\r'.code.toByte() &&
                headerBuf[len - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }

        val headerText = String(headerBuf, 0, len, Charsets.US_ASCII)
        val firstLine = headerText.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = firstLine.split(' ')
        if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
            output.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            client.close()
            return
        }

        val authority = parts[1].trim()
        val host: String
        val targetPort: Int
        val lastColon = authority.lastIndexOf(':')
        if (lastColon > 0 && authority.substring(lastColon + 1).toIntOrNull() != null) {
            host = authority.substring(0, lastColon).trim('[', ']')
            targetPort = authority.substring(lastColon + 1).toInt()
        } else {
            host = authority.trim('[', ']')
            targetPort = 443
        }

        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()

        val fallbackIp = if (DnsPacketCodec.ipStringToInt(host) != null) host else null
        ProxyConnectionDispatcher.relayConnection(
            clientIn = input,
            clientOut = output,
            initialHost = host,
            fallbackIp = fallbackIp,
            targetPort = targetPort,
            vpnProtect = { vpnService.protect(it) },
            onDownstreamEof = { runCatching { client.shutdownOutput() } }
        )
        runCatching { client.close() }
    }

    private fun replySocks5Error(output: OutputStream, rep: Int) {
        try {
            output.write(byteArrayOf(5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (_: Exception) {}
    }
}
