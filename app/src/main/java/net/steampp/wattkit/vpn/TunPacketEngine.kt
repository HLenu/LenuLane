package net.steampp.wattkit.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import kotlinx.coroutines.*
import net.steampp.wattkit.proxy.DoHResolver
import net.steampp.wattkit.proxy.ProxyConnectionDispatcher
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque

/**
 * Pure-Kotlin in-process TUN packet engine (IPv4 TCP / UDP / ICMP).
 * Features:
 * - Os.poll() + Os.pipe() kernel wakeup (0% idle CPU, instant tear-down)
 * - RFC 7323 TCP Window Scaling (up to 512 KB sliding window)
 * - Dedicated background RTO retransmission timer & zombie session reaper
 * - Direct Os.write() syscall on TUN descriptor
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
        private const val MAX_IN_FLIGHT_BYTES = 524_288 // 512 KB with RFC 7323 WSOPT
        private const val MAX_UNACKED_SEGMENTS = 384
        private const val SERVER_WS_SHIFT = 7
        private const val RTO_BASE_MS = 250L
        private const val MAX_RETRANSMITS = 5
        private const val HALF_OPEN_TIMEOUT_MS = 15_000L
        private const val FIN_WAIT_TIMEOUT_MS = 15_000L
        private const val IDLE_TIMEOUT_MS = 120_000L
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

    private val tunFd: FileDescriptor = vpnInterface.fileDescriptor
    private val tunWriteLock = Any()

    @Volatile
    private var running = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val packetDispatcher = Dispatchers.IO.limitedParallelism(64)
    private val scope = CoroutineScope(packetDispatcher + SupervisorJob())

    private val tcpSessions = ConcurrentHashMap<TcpKey, TunTcpSession>()
    private val udpSessions = ConcurrentHashMap<UdpKey, UdpNatSession>()
    private var readerThread: Thread? = null
    private var wakePipeRead: FileDescriptor? = null
    private var wakePipeWrite: FileDescriptor? = null

    fun start() {
        if (running) return
        running = true

        val pipeFds = try {
            Os.pipe()
        } catch (e: Exception) {
            Log.w(TAG, "Os.pipe() unavailable: ${e.message}")
            null
        }
        wakePipeRead = pipeFds?.getOrNull(0)
        wakePipeWrite = pipeFds?.getOrNull(1)

        // Periodic TCP RTO retransmission & zombie session reaper (250ms tick)
        scope.launch {
            var tickCount = 0
            while (running) {
                delay(250)
                if (!running) break
                val now = System.currentTimeMillis()
                for (session in tcpSessions.values) {
                    session.onMaintenanceTick(now)
                }
                tickCount++
                if (tickCount >= 60) {
                    tickCount = 0
                    val cutoff = now - 45_000L
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
        }

        readerThread = Thread({
            val buf = ByteArray(MAX_PACKET_SIZE)
            val wakeReadFd = wakePipeRead
            val pollFds = if (wakeReadFd != null) {
                arrayOf(
                    StructPollfd().apply {
                        fd = tunFd
                        events = OsConstants.POLLIN.toShort()
                    },
                    StructPollfd().apply {
                        fd = wakeReadFd
                        events = OsConstants.POLLIN.toShort()
                    }
                )
            } else {
                null
            }
            val fallbackIn = if (pollFds == null) FileInputStream(tunFd) else null
            Log.i(TAG, "In-process TUN packet engine started (poll=${pollFds != null}, WSOPT enabled)")
            try {
                while (running) {
                    if (pollFds != null) {
                        pollFds[0].revents = 0
                        pollFds[1].revents = 0
                        val ready = try {
                            Os.poll(pollFds, -1)
                        } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EINTR) continue
                            if (running) Log.d(TAG, "TUN poll stopped: ${e.message}")
                            break
                        } catch (_: Exception) {
                            break
                        }
                        if (!running || ready < 0) break

                        val wakeRevents = pollFds[1].revents.toInt()
                        if ((wakeRevents and (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL)) != 0) {
                            break
                        }
                        val tunRevents = pollFds[0].revents.toInt()
                        if ((tunRevents and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
                            if (running) Log.d(TAG, "TUN descriptor closed")
                            break
                        }
                        if ((tunRevents and OsConstants.POLLIN) == 0) {
                            continue
                        }

                        val len = try {
                            Os.read(tunFd, buf, 0, buf.size)
                        } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                                continue
                            }
                            if (running) Log.d(TAG, "TUN read ended: ${e.message}")
                            break
                        } catch (_: Exception) {
                            if (running) Log.d(TAG, "TUN read stream ended")
                            break
                        }
                        if (len < 0) break
                        if (len >= 20) {
                            dispatchPacket(buf, len)
                        }
                    } else {
                        val len = try {
                            fallbackIn?.read(buf) ?: -1
                        } catch (_: Exception) {
                            if (running) Log.d(TAG, "TUN read stream ended")
                            break
                        }
                        if (len < 0) break
                        if (len == 0) {
                            try {
                                Thread.sleep(2)
                            } catch (_: InterruptedException) {
                                break
                            }
                            continue
                        }
                        if (len >= 20) {
                            dispatchPacket(buf, len)
                        }
                    }
                }
            } finally {
                try { fallbackIn?.close() } catch (_: Exception) {}
            }
            Log.i(TAG, "TUN reader thread stopped")
        }, "LenuLane-TunReader").apply { start() }
    }

    fun stop() {
        running = false
        wakePipeWrite?.let { wfd ->
            try {
                Os.write(wfd, byteArrayOf(1), 0, 1)
            } catch (_: Exception) {}
        }
        val thread = readerThread
        readerThread = null
        thread?.interrupt()
        try {
            thread?.join(200)
        } catch (_: InterruptedException) {}

        tcpSessions.values.forEach { it.forceClose() }
        tcpSessions.clear()

        udpSessions.values.forEach { it.close() }
        udpSessions.clear()

        scope.cancel()
        closeFdQuietly(wakePipeWrite); wakePipeWrite = null
        closeFdQuietly(wakePipeRead); wakePipeRead = null
    }

    private fun closeFdQuietly(fd: FileDescriptor?) {
        if (fd != null) {
            try {
                Os.close(fd)
            } catch (_: Exception) {}
        }
    }

    private fun writeTunPacket(packet: ByteArray) {
        if (!running) return
        synchronized(tunWriteLock) {
            if (!running || !tunFd.valid()) return
            try {
                Os.write(tunFd, packet, 0, packet.size)
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
                    val clientWsShift = PacketChecksum.parseTcpWindowScale(pkt, ihl, dataOffset)
                    val session = TunTcpSession(key, seqNum, window, clientWsShift)
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

    private data class SentSegment(
        val endSeq: Long,
        val packet: ByteArray,
        var sentAtMs: Long,
        var retransmitCount: Int = 0
    )

    private inner class TunTcpSession(
        private val key: TcpKey,
        private val initialClientSynSeq: Long,
        initialRawWindow: Int,
        clientWsShift: Int?
    ) {
        private val stateLock = Object()
        private val createdAtMs = System.currentTimeMillis()
        @Volatile
        private var lastActivityMs = createdAtMs

        private val windowScalingActive = clientWsShift != null
        private val clientShift = clientWsShift ?: 0
        private val synAckOptions = if (windowScalingActive) {
            PacketChecksum.SYN_ACK_MSS_WS_OPTION
        } else {
            PacketChecksum.SYN_ACK_MSS_ONLY_OPTION
        }

        private var clientNextSeq: Long = (initialClientSynSeq + 1L) and 0xFFFFFFFFL
        private val initialServerSeq: Long =
            ((System.nanoTime() xor (key.srcPort.toLong() shl 16) xor key.dstPort.toLong()) and 0x7FFFFFFFL)
                .coerceAtLeast(1000L)
        private var serverNextSeq: Long = (initialServerSeq + 1L) and 0xFFFFFFFFL
        private var lastAckFromClient: Long = initialServerSeq
        private var effectiveClientWindow: Int = initialRawWindow.coerceIn(1, 65535)
        private var hasReceivedAck = false
        private var dupAckCount = 0
        private var finFromClientReceived = false
        private var serverFinSent = false

        private val unackedSegments = ArrayDeque<SentSegment>(64)
        private val oooSegments = HashMap<Long, ByteArray>(16)

        @Volatile
        private var closed = false

        private val clientQueue = LinkedBlockingQueue<ByteArray>()
        private var relayJob: Job? = null

        private fun advertisedReceiveWindow(): Int {
            // Queue is unbounded in memory; advertise generous receive window (65535 * 128 when scaled).
            return if (windowScalingActive) (MAX_IN_FLIGHT_BYTES ushr SERVER_WS_SHIFT).coerceIn(1, 65535) else 65535
        }

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
            val synAck = PacketChecksum.buildIpv4TcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = initialServerSeq,
                ackNum = clientNextSeq,
                tcpFlags = PacketChecksum.TCP_SYN or PacketChecksum.TCP_ACK,
                windowSize = 65535,
                options = synAckOptions
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
            val synAck: ByteArray?
            synchronized(stateLock) {
                if (seqNum != initialClientSynSeq || closed) return
                lastActivityMs = System.currentTimeMillis()
                synAck = PacketChecksum.buildIpv4TcpPacket(
                    srcIp = key.dstIp,
                    srcPort = key.dstPort,
                    dstIp = key.srcIp,
                    dstPort = key.srcPort,
                    seqNum = initialServerSeq,
                    ackNum = clientNextSeq,
                    tcpFlags = PacketChecksum.TCP_SYN or PacketChecksum.TCP_ACK,
                    windowSize = 65535,
                    options = synAckOptions
                )
            }
            if (synAck != null) {
                writeTunPacket(synAck)
            }
        }

        fun onMaintenanceTick(now: Long) {
            var retransmitPkt: ByteArray? = null
            var shouldCloseZombie = false

            synchronized(stateLock) {
                if (closed) return

                // Reclaim half-open, FIN-stalled, or long-idle sessions
                val idleMs = now - lastActivityMs
                if ((!hasReceivedAck && (now - createdAtMs) > HALF_OPEN_TIMEOUT_MS) ||
                    ((finFromClientReceived || serverFinSent) && idleMs > FIN_WAIT_TIMEOUT_MS) ||
                    (idleMs > IDLE_TIMEOUT_MS)
                ) {
                    shouldCloseZombie = true
                } else if (unackedSegments.isNotEmpty()) {
                    val head = unackedSegments.first()
                    val rto = (RTO_BASE_MS shl head.retransmitCount.coerceAtMost(3)).coerceAtMost(2000L)
                    if (now - head.sentAtMs >= rto) {
                        if (head.retransmitCount >= MAX_RETRANSMITS) {
                            shouldCloseZombie = true
                        } else {
                            head.retransmitCount++
                            head.sentAtMs = now
                            retransmitPkt = head.packet
                        }
                    }
                }
            }

            if (shouldCloseZombie) {
                forceClose()
                return
            }
            retransmitPkt?.let { writeTunPacket(it) }
        }

        fun handleInbound(
            pkt: ByteArray,
            payloadOffset: Int,
            payloadLen: Int,
            seqNum: Long,
            ackNum: Long,
            flags: Int,
            rawWindow: Int
        ) {
            if ((flags and PacketChecksum.TCP_RST) != 0) {
                forceClose()
                return
            }

            val now = System.currentTimeMillis()
            val ackSeqSnapshot: Long
            val ackClientSeqSnapshot: Long
            var sendAck = false
            var fastRetransmitPkt: ByteArray? = null

            synchronized(stateLock) {
                if (closed) return
                lastActivityMs = now

                val scaledWindow = if (windowScalingActive) {
                    (rawWindow shl clientShift).coerceIn(0, MAX_IN_FLIGHT_BYTES)
                } else {
                    rawWindow.coerceIn(0, 65535)
                }

                if ((flags and PacketChecksum.TCP_ACK) != 0) {
                    hasReceivedAck = true
                    effectiveClientWindow = scaledWindow
                    if (seqDiff(ackNum, lastAckFromClient) > 0 && seqDiff(ackNum, serverNextSeq) <= 0) {
                        lastAckFromClient = ackNum
                        dupAckCount = 0
                        while (unackedSegments.isNotEmpty()) {
                            val head = unackedSegments.first()
                            if (seqDiff(ackNum, head.endSeq) >= 0) {
                                unackedSegments.removeFirst()
                            } else {
                                break
                            }
                        }
                        stateLock.notifyAll()
                    } else if (ackNum == lastAckFromClient && payloadLen == 0 &&
                        (flags and (PacketChecksum.TCP_SYN or PacketChecksum.TCP_FIN or PacketChecksum.TCP_RST)) == 0 &&
                        seqDiff(serverNextSeq, lastAckFromClient) > 0
                    ) {
                        dupAckCount++
                        if (dupAckCount == 3 && unackedSegments.isNotEmpty()) {
                            val head = unackedSegments.first()
                            head.sentAtMs = now
                            fastRetransmitPkt = head.packet
                        }
                    }
                } else {
                    effectiveClientWindow = scaledWindow
                }

                if (payloadLen > 0) {
                    val diff = seqDiff(seqNum, clientNextSeq)
                    if (diff == 0) {
                        clientQueue.offer(pkt.copyOfRange(payloadOffset, payloadOffset + payloadLen))
                        clientNextSeq = (clientNextSeq + payloadLen) and 0xFFFFFFFFL
                        while (oooSegments.isNotEmpty()) {
                            val buffered = oooSegments.remove(clientNextSeq) ?: break
                            clientQueue.offer(buffered)
                            clientNextSeq = (clientNextSeq + buffered.size) and 0xFFFFFFFFL
                        }
                        sendAck = true
                    } else if (diff < 0 && diff + payloadLen > 0) {
                        val skip = -diff
                        val sliceStart = payloadOffset + skip
                        clientQueue.offer(pkt.copyOfRange(sliceStart, payloadOffset + payloadLen))
                        clientNextSeq = (clientNextSeq + (payloadLen - skip)) and 0xFFFFFFFFL
                        while (oooSegments.isNotEmpty()) {
                            val buffered = oooSegments.remove(clientNextSeq) ?: break
                            clientQueue.offer(buffered)
                            clientNextSeq = (clientNextSeq + buffered.size) and 0xFFFFFFFFL
                        }
                        sendAck = true
                    } else if (diff > 0) {
                        if (oooSegments.size < 32 && !oooSegments.containsKey(seqNum)) {
                            oooSegments[seqNum] = pkt.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                        }
                        sendAck = true
                    } else {
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

            fastRetransmitPkt?.let { writeTunPacket(it) }

            if (sendAck) {
                val ackPkt = PacketChecksum.buildIpv4TcpPacket(
                    srcIp = key.dstIp,
                    srcPort = key.dstPort,
                    dstIp = key.srcIp,
                    dstPort = key.srcPort,
                    seqNum = ackSeqSnapshot,
                    ackNum = ackClientSeqSnapshot,
                    tcpFlags = PacketChecksum.TCP_ACK,
                    windowSize = advertisedReceiveWindow()
                )
                writeTunPacket(ackPkt)
            }
        }

        private fun sendDownstreamSegment(buf: ByteArray, offset: Int, length: Int) {
            val segSeq: Long
            val ackSnap: Long
            var timeoutRetransmit: ByteArray? = null
            val winSize = advertisedReceiveWindow()

            synchronized(stateLock) {
                var waitedMs = 0L
                while (!closed && waitedMs < 500L) {
                    val maxInFlight = if (hasReceivedAck) {
                        effectiveClientWindow.coerceAtMost(MAX_IN_FLIGHT_BYTES)
                    } else {
                        65535
                    }
                    val inFlight = seqDiff(serverNextSeq, lastAckFromClient)
                    if (maxInFlight > 0 && inFlight < maxInFlight) {
                        break
                    }
                    if (maxInFlight == 0 && waitedMs >= 250L) {
                        break
                    }
                    val step = 50L
                    try {
                        stateLock.wait(step)
                    } catch (_: InterruptedException) {
                        return
                    }
                    waitedMs += step
                    if (!closed && waitedMs >= 250L && unackedSegments.isNotEmpty()) {
                        val head = unackedSegments.first()
                        head.sentAtMs = System.currentTimeMillis()
                        timeoutRetransmit = head.packet
                        break
                    }
                }
                if (closed) return
                val now = System.currentTimeMillis()
                lastActivityMs = now
                segSeq = serverNextSeq
                ackSnap = clientNextSeq
                serverNextSeq = (serverNextSeq + length) and 0xFFFFFFFFL
            }

            timeoutRetransmit?.let { writeTunPacket(it) }

            val tcpPkt = PacketChecksum.buildIpv4TcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = segSeq,
                ackNum = ackSnap,
                tcpFlags = PacketChecksum.TCP_PSH or PacketChecksum.TCP_ACK,
                windowSize = winSize,
                payload = buf,
                payloadOffset = offset,
                payloadLen = length
            )
            val sentNow = System.currentTimeMillis()
            synchronized(stateLock) {
                if (!closed) {
                    while (unackedSegments.size >= MAX_UNACKED_SEGMENTS) {
                        unackedSegments.removeFirst()
                    }
                    unackedSegments.addLast(SentSegment((segSeq + length) and 0xFFFFFFFFL, tcpPkt, sentNow))
                }
            }
            writeTunPacket(tcpPkt)
        }

        private fun sendFinOnce() {
            val finSeq: Long
            val ackSnap: Long
            synchronized(stateLock) {
                if (serverFinSent || closed) return
                serverFinSent = true
                lastActivityMs = System.currentTimeMillis()
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
                windowSize = advertisedReceiveWindow()
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
                unackedSegments.clear()
                oooSegments.clear()
                stateLock.notifyAll()
            }
            clientQueue.offer(EOF_SENTINEL)
            relayJob?.cancel()
            tcpSessions.remove(key, this)
        }

        private fun seqDiff(a: Long, b: Long): Int = a.toInt() - b.toInt()
    }
}
