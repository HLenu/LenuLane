package net.steampp.wattkit.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import net.steampp.wattkit.proxy.DoHResolver
import net.steampp.wattkit.proxy.ProxyConnectionDispatcher
import net.steampp.wattkit.proxy.TrafficTelemetry
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin in-process TUN packet engine (IPv4 TCP / UDP / ICMP).
 * Replaces the broken native BadVPN tun2socks subprocess + LocalSocket architecture.
 */
class TunPacketEngine(
    private val vpnInterface: ParcelFileDescriptor,
    private val smartDnsEngine: SmartDnsEngine,
    private val fakeIpPool: FakeIpPool,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean
) {
    companion object {
        private const val TAG = "TunPacketEngine"
        private const val MAX_PACKET_SIZE = 32768
        private const val MAX_TCP_SEGMENT = 1360
        private val SYN_ACK_MSS_OPTION = byteArrayOf(0x02, 0x04, 0x05, 0x50) // MSS = 1360
        private val EOF_SENTINEL = ByteArray(0)
    }

    private data class TcpKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int
    )

    private data class UdpKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int
    )

    private val tunIn = FileInputStream(vpnInterface.fileDescriptor)
    private val tunOut = FileOutputStream(vpnInterface.fileDescriptor)
    private val tunWriteLock = Any()

    @Volatile
    private var running = false

    private val executor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())

    private val tcpSessions = ConcurrentHashMap<TcpKey, TunTcpSession>()
    private val udpSessions = ConcurrentHashMap<UdpKey, UdpNatSession>()
    private var readerThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        // Periodic cleanup for expired UDP NAT sessions
        scope.launch {
            while (running) {
                delay(15_000)
                val cutoff = System.currentTimeMillis() - 45_000L
                val iterator = udpSessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.lastActiveMs < cutoff) {
                        entry.value.close()
                        iterator.remove()
                    }
                }
            }
        }

        readerThread = Thread({
            val buf = ByteArray(MAX_PACKET_SIZE)
            Log.i(TAG, "In-process TUN packet engine started")
            while (running) {
                val len = try {
                    tunIn.read(buf)
                } catch (_: Exception) {
                    if (running) Log.d(TAG, "TUN read stream ended")
                    break
                }
                if (len <= 0) {
                    if (len < 0) break
                    continue
                }
                if (len < 20) continue
                dispatchPacket(buf, len)
            }
            Log.i(TAG, "TUN reader thread stopped")
        }, "WattKit-TunReader").apply { start() }
    }

    fun stop() {
        running = false
        readerThread?.interrupt()
        readerThread = null

        tcpSessions.values.forEach { it.forceClose() }
        tcpSessions.clear()

        udpSessions.values.forEach { it.close() }
        udpSessions.clear()

        scope.cancel()
        executor.shutdownNow()
        try { tunIn.close() } catch (_: Exception) {}
        try { tunOut.close() } catch (_: Exception) {}
    }

    private fun writeTunPacket(packet: ByteArray) {
        if (!running) return
        synchronized(tunWriteLock) {
            if (!running) return
            try {
                tunOut.write(packet, 0, packet.size)
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatchPacket(pkt: ByteArray, readLen: Int) {
        val version = (pkt[0].toInt() ushr 4) and 0xF
        if (version != 4) return

        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || readLen < ihl) return

        val totalLen = PacketChecksum.readUInt16(pkt, 2).coerceAtMost(readLen)
        val protocol = pkt[9].toInt() and 0xFF
        val srcIp = PacketChecksum.readInt32(pkt, 12)
        val dstIp = PacketChecksum.readInt32(pkt, 16)

        when (protocol) {
            PacketChecksum.PROTO_ICMP -> {
                PacketChecksum.buildIcmpEchoReply(pkt, totalLen)?.let { writeTunPacket(it) }
            }

            PacketChecksum.PROTO_UDP -> {
                if (totalLen < ihl + 8) return
                val srcPort = PacketChecksum.readUInt16(pkt, ihl)
                val dstPort = PacketChecksum.readUInt16(pkt, ihl + 2)
                val payloadOffset = ihl + 8
                val payloadLen = totalLen - payloadOffset

                if (dstPort == 53 && payloadLen > 0) {
                    val queryCopy = pkt.copyOfRange(payloadOffset, totalLen)
                    scope.launch {
                        val resp = smartDnsEngine.handleDnsQuery(queryCopy, queryCopy.size)
                        if (resp != null) {
                            val ipPkt = PacketChecksum.buildIpv4UdpPacket(
                                srcIp = dstIp,
                                srcPort = 53,
                                dstIp = srcIp,
                                dstPort = srcPort,
                                payload = resp
                            )
                            writeTunPacket(ipPkt)
                        }
                    }
                    return
                }

                // Force immediate QUIC/HTTP3 fallback to TCP 443 for Fake-IP targets
                if (dstPort == 443 && FakeIpPool.isFakeIp(dstIp)) {
                    PacketChecksum.buildIcmpPortUnreachable(pkt, totalLen)?.let { writeTunPacket(it) }
                    return
                }

                if (payloadLen > 0) {
                    val key = UdpKey(srcIp, srcPort, dstIp, dstPort)
                    val dataCopy = pkt.copyOfRange(payloadOffset, totalLen)
                    val session = udpSessions.computeIfAbsent(key) {
                        UdpNatSession(it).also { created -> created.start() }
                    }
                    session.send(dataCopy)
                }
            }

            PacketChecksum.PROTO_TCP -> {
                if (totalLen < ihl + 20) return
                val srcPort = PacketChecksum.readUInt16(pkt, ihl)
                val dstPort = PacketChecksum.readUInt16(pkt, ihl + 2)
                val seqNum = PacketChecksum.readUInt32(pkt, ihl + 4)
                val ackNum = PacketChecksum.readUInt32(pkt, ihl + 8)
                val dataOffset = ((pkt[ihl + 12].toInt() ushr 4) and 0xF) * 4
                if (dataOffset < 20 || ihl + dataOffset > totalLen) return
                val flags = pkt[ihl + 13].toInt() and 0xFF
                val window = PacketChecksum.readUInt16(pkt, ihl + 14)
                val payloadOffset = ihl + dataOffset
                val payloadLen = totalLen - payloadOffset

                val key = TcpKey(srcIp, srcPort, dstIp, dstPort)
                val existing = tcpSessions[key]
                val isBareSyn = (flags and PacketChecksum.TCP_SYN) != 0 && (flags and PacketChecksum.TCP_ACK) == 0

                if (existing != null) {
                    if (isBareSyn) {
                        existing.handleSynRetransmit(seqNum)
                    } else {
                        existing.handleInbound(pkt, payloadOffset, payloadLen, seqNum, ackNum, flags, window)
                    }
                    return
                }

                if (isBareSyn) {
                    val session = TunTcpSession(key, seqNum, window)
                    val race = tcpSessions.putIfAbsent(key, session)
                    if (race == null) {
                        session.start()
                    } else {
                        race.handleSynRetransmit(seqNum)
                    }
                } else if ((flags and PacketChecksum.TCP_RST) == 0) {
                    // Reject stray ACK/FIN for unknown session cleanly with RST
                    val finBit = if ((flags and PacketChecksum.TCP_FIN) != 0) 1L else 0L
                    val rstAck = (seqNum + payloadLen + finBit) and 0xFFFFFFFFL
                    val rst = PacketChecksum.buildIpv4TcpPacket(
                        srcIp = key.dstIp,
                        srcPort = key.dstPort,
                        dstIp = key.srcIp,
                        dstPort = key.srcPort,
                        seqNum = ackNum,
                        ackNum = rstAck,
                        tcpFlags = PacketChecksum.TCP_RST or PacketChecksum.TCP_ACK
                    )
                    writeTunPacket(rst)
                }
            }
        }
    }

    private inner class UdpNatSession(private val key: UdpKey) {
        @Volatile
        var lastActiveMs: Long = System.currentTimeMillis()
        private var socket: DatagramSocket? = null
        private var receiveJob: Job? = null

        @Volatile
        private var resolvedTarget: InetAddress? = null

        fun start() {
            try {
                val sock = DatagramSocket()
                protectUdp(sock)
                sock.soTimeout = 10_000
                socket = sock

                receiveJob = scope.launch {
                    val buf = ByteArray(4096)
                    val pkt = DatagramPacket(buf, buf.size)
                    while (running && !sock.isClosed) {
                        try {
                            sock.receive(pkt)
                            lastActiveMs = System.currentTimeMillis()
                            val ipPkt = PacketChecksum.buildIpv4UdpPacket(
                                srcIp = key.dstIp,
                                srcPort = key.dstPort,
                                dstIp = key.srcIp,
                                dstPort = key.srcPort,
                                payload = buf,
                                payloadOffset = 0,
                                payloadLen = pkt.length
                            )
                            writeTunPacket(ipPkt)
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                udpSessions.remove(key, this)
            }
        }

        fun send(data: ByteArray) {
            lastActiveMs = System.currentTimeMillis()
            val sock = socket ?: return
            scope.launch {
                try {
                    val addr = resolvedTarget ?: resolveDestination().also { resolvedTarget = it }
                    if (addr != null && !sock.isClosed) {
                        sock.send(DatagramPacket(data, data.size, addr, key.dstPort))
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun resolveDestination(): InetAddress? {
            if (FakeIpPool.isFakeIp(key.dstIp)) {
                val domain = fakeIpPool.lookupDomain(key.dstIp) ?: return null
                val realIp = DoHResolver.resolve(domain) ?: return null
                return InetAddress.getByName(realIp)
            }
            return InetAddress.getByName(DnsPacketCodec.ipIntToString(key.dstIp))
        }

        fun close() {
            receiveJob?.cancel()
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private inner class TunTcpSession(
        private val key: TcpKey,
        private val initialClientSynSeq: Long,
        initialWindow: Int
    ) {
        private val stateLock = Object()
        private var clientNextSeq: Long = (initialClientSynSeq + 1L) and 0xFFFFFFFFL
        private val initialServerSeq: Long =
            ((System.nanoTime() xor (key.srcPort.toLong() shl 16) xor key.dstPort.toLong()) and 0x7FFFFFFFL)
                .coerceAtLeast(1000L)
        private var serverNextSeq: Long = (initialServerSeq + 1L) and 0xFFFFFFFFL
        private var lastAckFromClient: Long = initialServerSeq
        private var clientWindow: Int = initialWindow.coerceAtLeast(8192)
        private var finFromClientReceived = false
        private var serverFinSent = false

        @Volatile
        private var closed = false

        private val clientQueue = LinkedBlockingQueue<ByteArray>()
        private var relayJob: Job? = null

        private val queueInput = object : InputStream() {
            private var curBuf: ByteArray? = null
            private var curPos: Int = 0
            private var eof = false

            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else (one[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                if (eof) return -1

                while (true) {
                    val cur = curBuf
                    if (cur != null && curPos < cur.size) {
                        val avail = cur.size - curPos
                        val copyLen = minOf(len, avail)
                        System.arraycopy(cur, curPos, b, off, copyLen)
                        curPos += copyLen
                        if (curPos >= cur.size) {
                            curBuf = null
                            curPos = 0
                        }
                        return copyLen
                    }
                    if (closed && clientQueue.isEmpty()) {
                        eof = true
                        return -1
                    }
                    val next = try {
                        clientQueue.poll(30_000L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        eof = true
                        return -1
                    } ?: run {
                        if (closed) {
                            eof = true
                            return -1
                        }
                        throw SocketTimeoutException("TUN client stream idle timeout")
                    }

                    if (next === EOF_SENTINEL || next.isEmpty()) {
                        eof = true
                        return -1
                    }
                    curBuf = next
                    curPos = 0
                }
            }

            override fun available(): Int {
                val cur = curBuf
                return if (cur != null) (cur.size - curPos) else (clientQueue.peek()?.size ?: 0)
            }
        }

        private val tunOutput = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0 || closed) return
                var cursor = off
                val end = off + len
                while (cursor < end && !closed) {
                    val segLen = minOf(MAX_TCP_SEGMENT, end - cursor)
                    sendDownstreamSegment(b, cursor, segLen)
                    cursor += segLen
                }
            }

            override fun close() {
                sendFinOnce()
            }
        }

        fun start() {
            // Send SYN-ACK immediately
            val synAck = PacketChecksum.buildIpv4TcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = initialServerSeq,
                ackNum = clientNextSeq,
                tcpFlags = PacketChecksum.TCP_SYN or PacketChecksum.TCP_ACK,
                windowSize = 65535,
                options = SYN_ACK_MSS_OPTION
            )
            writeTunPacket(synAck)

            val initialHost = fakeIpPool.lookupDomain(key.dstIp)
            val fallbackIp = DnsPacketCodec.ipIntToString(key.dstIp)

            relayJob = scope.launch {
                var ok = false
                try {
                    ok = ProxyConnectionDispatcher.relayConnection(
                        clientIn = queueInput,
                        clientOut = tunOutput,
                        initialHost = initialHost,
                        fallbackIp = fallbackIp,
                        targetPort = key.dstPort,
                        vpnProtect = protectTcp,
                        onDownstreamEof = { sendFinOnce() }
                    )
                } finally {
                    if (!ok && !serverFinSent && !closed) {
                        sendRstOnce()
                    } else if (ok) {
                        sendFinOnce()
                    }
                    closed = true
                    clientQueue.offer(EOF_SENTINEL)
                    tcpSessions.remove(key, this@TunTcpSession)
                }
            }
        }

        fun handleSynRetransmit(seqNum: Long) {
            synchronized(stateLock) {
                if (seqNum != initialClientSynSeq || closed) return
                val synAck = PacketChecksum.buildIpv4TcpPacket(
                    srcIp = key.dstIp,
                    srcPort = key.dstPort,
                    dstIp = key.srcIp,
                    dstPort = key.srcPort,
                    seqNum = initialServerSeq,
                    ackNum = clientNextSeq,
                    tcpFlags = PacketChecksum.TCP_SYN or PacketChecksum.TCP_ACK,
                    windowSize = 65535,
                    options = SYN_ACK_MSS_OPTION
                )
                writeTunPacket(synAck)
            }
        }

        fun handleInbound(
            pkt: ByteArray,
            payloadOffset: Int,
            payloadLen: Int,
            seqNum: Long,
            ackNum: Long,
            flags: Int,
            window: Int
        ) {
            if ((flags and PacketChecksum.TCP_RST) != 0) {
                forceClose()
                return
            }

            val ackSeqSnapshot: Long
            val ackClientSeqSnapshot: Long
            var sendAck = false

            synchronized(stateLock) {
                if (closed) return

                clientWindow = window.coerceAtLeast(8192)

                if ((flags and PacketChecksum.TCP_ACK) != 0) {
                    if (seqDiff(ackNum, lastAckFromClient) > 0 && seqDiff(ackNum, serverNextSeq) <= 0) {
                        lastAckFromClient = ackNum
                        stateLock.notifyAll()
                    }
                }

                if (payloadLen > 0) {
                    val diff = seqDiff(seqNum, clientNextSeq)
                    if (diff == 0) {
                        clientQueue.offer(pkt.copyOfRange(payloadOffset, payloadOffset + payloadLen))
                        clientNextSeq = (clientNextSeq + payloadLen) and 0xFFFFFFFFL
                        sendAck = true
                    } else if (diff < 0 && diff + payloadLen > 0) {
                        val skip = -diff
                        val sliceStart = payloadOffset + skip
                        clientQueue.offer(pkt.copyOfRange(sliceStart, payloadOffset + payloadLen))
                        clientNextSeq = (clientNextSeq + (payloadLen - skip)) and 0xFFFFFFFFL
                        sendAck = true
                    } else {
                        // Duplicate / out-of-order segment -> reply current ACK
                        sendAck = true
                    }
                }

                if ((flags and PacketChecksum.TCP_FIN) != 0 && !finFromClientReceived) {
                    val finSeq = (seqNum + payloadLen) and 0xFFFFFFFFL
                    if (seqDiff(finSeq, clientNextSeq) == 0) {
                        clientNextSeq = (clientNextSeq + 1L) and 0xFFFFFFFFL
                        finFromClientReceived = true
                        clientQueue.offer(EOF_SENTINEL)
                        sendAck = true
                    }
                }

                ackSeqSnapshot = serverNextSeq
                ackClientSeqSnapshot = clientNextSeq
            }

            if (sendAck) {
                val ackPkt = PacketChecksum.buildIpv4TcpPacket(
                    srcIp = key.dstIp,
                    srcPort = key.dstPort,
                    dstIp = key.srcIp,
                    dstPort = key.srcPort,
                    seqNum = ackSeqSnapshot,
                    ackNum = ackClientSeqSnapshot,
                    tcpFlags = PacketChecksum.TCP_ACK,
                    windowSize = 65535
                )
                writeTunPacket(ackPkt)
            }
        }

        private fun sendDownstreamSegment(buf: ByteArray, offset: Int, length: Int) {
            val segSeq: Long
            val ackSnap: Long

            synchronized(stateLock) {
                val maxInFlight = minOf(clientWindow, 65535).coerceAtLeast(8192)
                var waitedMs = 0L
                while (!closed && seqDiff(serverNextSeq, lastAckFromClient) >= maxInFlight && waitedMs < 500L) {
                    val step = 50L
                    try {
                        stateLock.wait(step)
                    } catch (_: InterruptedException) {
                        return
                    }
                    waitedMs += step
                }
                if (closed) return
                segSeq = serverNextSeq
                ackSnap = clientNextSeq
                serverNextSeq = (serverNextSeq + length) and 0xFFFFFFFFL
            }

            val tcpPkt = PacketChecksum.buildIpv4TcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = segSeq,
                ackNum = ackSnap,
                tcpFlags = PacketChecksum.TCP_PSH or PacketChecksum.TCP_ACK,
                windowSize = 65535,
                payload = buf,
                payloadOffset = offset,
                payloadLen = length
            )
            writeTunPacket(tcpPkt)
        }

        private fun sendFinOnce() {
            val finSeq: Long
            val ackSnap: Long
            synchronized(stateLock) {
                if (serverFinSent || closed) return
                serverFinSent = true
                finSeq = serverNextSeq
                serverNextSeq = (serverNextSeq + 1L) and 0xFFFFFFFFL
                ackSnap = clientNextSeq
            }
            val finPkt = PacketChecksum.buildIpv4TcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = finSeq,
                ackNum = ackSnap,
                tcpFlags = PacketChecksum.TCP_FIN or PacketChecksum.TCP_ACK,
                windowSize = 65535
            )
            writeTunPacket(finPkt)
        }

        private fun sendRstOnce() {
            val seqSnap: Long
            val ackSnap: Long
            synchronized(stateLock) {
                if (closed) return
                closed = true
                seqSnap = serverNextSeq
                ackSnap = clientNextSeq
                stateLock.notifyAll()
            }
            val rstPkt = PacketChecksum.buildIpv4TcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = seqSnap,
                ackNum = ackSnap,
                tcpFlags = PacketChecksum.TCP_RST or PacketChecksum.TCP_ACK,
                windowSize = 0
            )
            writeTunPacket(rstPkt)
        }

        fun forceClose() {
            synchronized(stateLock) {
                closed = true
                stateLock.notifyAll()
            }
            clientQueue.offer(EOF_SENTINEL)
            relayJob?.cancel()
            tcpSessions.remove(key, this)
        }

        private fun seqDiff(a: Long, b: Long): Int = a.toInt() - b.toInt()
    }
}
