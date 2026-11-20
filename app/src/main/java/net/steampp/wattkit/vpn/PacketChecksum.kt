package net.steampp.wattkit.vpn

import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 1071 / 791 / 792 / 793 / 768 IPv4, TCP, UDP, and ICMP checksum & packet builder helpers.
 */
object PacketChecksum {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    private val ipIdGenerator = AtomicInteger(1)

    fun internetChecksum(
        data: ByteArray,
        offset: Int,
        length: Int,
        initialSum: Long = 0L
    ): Int {
        var sum = initialSum
        var i = offset
        var remaining = length
        while (remaining > 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word.toLong()
            i += 2
            remaining -= 2
        }
        if (remaining > 0) {
            sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun pseudoHeaderSum(srcIp: Int, dstIp: Int, protocol: Int, transportLen: Int): Long {
        return (((srcIp ushr 16) and 0xFFFF).toLong() +
            (srcIp and 0xFFFF).toLong() +
            ((dstIp ushr 16) and 0xFFFF).toLong() +
            (dstIp and 0xFFFF).toLong() +
            (protocol and 0xFF).toLong() +
            (transportLen and 0xFFFF).toLong())
    }

    fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    fun readUInt32(data: ByteArray, offset: Int): Long =
        readInt32(data, offset).toLong() and 0xFFFFFFFFL

    fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeIpv4Header(
        out: ByteArray,
        totalLen: Int,
        protocol: Int,
        srcIp: Int,
        dstIp: Int
    ) {
        out[0] = 0x45.toByte() // IPv4, IHL = 5 (20 bytes)
        out[1] = 0
        writeUInt16(out, 2, totalLen)
        writeUInt16(out, 4, ipIdGenerator.getAndIncrement() and 0xFFFF)
        writeUInt16(out, 6, 0x4000) // Don't Fragment
        out[8] = 64.toByte() // TTL
        out[9] = protocol.toByte()
        out[10] = 0; out[11] = 0
        writeInt32(out, 12, srcIp)
        writeInt32(out, 16, dstIp)
        val ipCsum = internetChecksum(out, 0, 20)
        writeUInt16(out, 10, ipCsum)
    }

    fun buildIpv4UdpPacket(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLen: Int = payload.size
    ): ByteArray {
        val udpLen = 8 + payloadLen
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        writeIpv4Header(out, totalLen, PROTO_UDP, srcIp, dstIp)

        writeUInt16(out, 20, srcPort)
        writeUInt16(out, 22, dstPort)
        writeUInt16(out, 24, udpLen)
        writeUInt16(out, 26, 0) // Checksum placeholder
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, out, 28, payloadLen)
        }
        val pseudo = pseudoHeaderSum(srcIp, dstIp, PROTO_UDP, udpLen)
        var udpCsum = internetChecksum(out, 20, udpLen, pseudo)
        if (udpCsum == 0) udpCsum = 0xFFFF
        writeUInt16(out, 26, udpCsum)
        return out
    }

    fun buildIpv4TcpPacket(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        tcpFlags: Int,
        windowSize: Int = 65535,
        options: ByteArray? = null,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLen: Int = payload?.size ?: 0
    ): ByteArray {
        val optLen = options?.size ?: 0
        val tcpHeaderLen = 20 + optLen
        val tcpTotalLen = tcpHeaderLen + payloadLen
        val totalLen = 20 + tcpTotalLen
        val out = ByteArray(totalLen)
        writeIpv4Header(out, totalLen, PROTO_TCP, srcIp, dstIp)

        writeUInt16(out, 20, srcPort)
        writeUInt16(out, 22, dstPort)
        writeInt32(out, 24, seqNum.toInt())
        writeInt32(out, 28, ackNum.toInt())
        out[32] = ((tcpHeaderLen / 4) shl 4).toByte()
        out[33] = (tcpFlags and 0xFF).toByte()
        writeUInt16(out, 34, windowSize.coerceIn(0, 65535))
        writeUInt16(out, 36, 0) // Checksum placeholder
        writeUInt16(out, 38, 0) // Urgent pointer

        if (options != null && optLen > 0) {
            System.arraycopy(options, 0, out, 40, optLen)
        }
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, out, 20 + tcpHeaderLen, payloadLen)
        }

        val pseudo = pseudoHeaderSum(srcIp, dstIp, PROTO_TCP, tcpTotalLen)
        val tcpCsum = internetChecksum(out, 20, tcpTotalLen, pseudo)
        writeUInt16(out, 36, tcpCsum)
        return out
    }

    /**
     * Synthesizes an ICMP Echo Reply (Type 0) for an incoming Echo Request (Type 8).
     */
    fun buildIcmpEchoReply(requestPacket: ByteArray, requestLength: Int): ByteArray? {
        if (requestLength < 28 || requestLength > requestPacket.size) return null
        val version = (requestPacket[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val ihl = (requestPacket[0].toInt() and 0x0F) * 4
        if (ihl < 20 || requestLength < ihl + 8) return null
        if ((requestPacket[9].toInt() and 0xFF) != PROTO_ICMP) return null
        val icmpType = requestPacket[ihl].toInt() and 0xFF
        if (icmpType != 8) return null

        val totalLen = readUInt16(requestPacket, 2).coerceAtMost(requestLength)
        if (totalLen < ihl + 8) return null

        val out = requestPacket.copyOf(totalLen)
        // Swap src and dst IPs
        System.arraycopy(requestPacket, 16, out, 12, 4)
        System.arraycopy(requestPacket, 12, out, 16, 4)
        out[8] = 64.toByte()
        out[10] = 0; out[11] = 0
        val ipCsum = internetChecksum(out, 0, ihl)
        writeUInt16(out, 10, ipCsum)

        out[ihl] = 0 // Type 0: Echo Reply
        out[ihl + 1] = 0 // Code 0
        out[ihl + 2] = 0; out[ihl + 3] = 0
        val icmpCsum = internetChecksum(out, ihl, totalLen - ihl)
        writeUInt16(out, ihl + 2, icmpCsum)
        return out
    }

    /**
     * Synthesizes an ICMP Destination Unreachable / Port Unreachable (Type 3, Code 3)
     * packet so QUIC / HTTP3 clients attempting UDP/443 against a Fake-IP fall back to TCP/443 immediately.
     */
    fun buildIcmpPortUnreachable(originalPacket: ByteArray, originalLength: Int): ByteArray? {
        if (originalLength < 28 || originalLength > originalPacket.size) return null
        val version = (originalPacket[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val origIhl = (originalPacket[0].toInt() and 0x0F) * 4
        if (origIhl < 20 || originalLength < origIhl + 8) return null

        val origSrcIp = readInt32(originalPacket, 12)
        val origDstIp = readInt32(originalPacket, 16)
        val quotedLen = (origIhl + 8).coerceAtMost(originalLength)
        val icmpLen = 8 + quotedLen
        val totalLen = 20 + icmpLen
        val out = ByteArray(totalLen)

        writeIpv4Header(out, totalLen, PROTO_ICMP, srcIp = origDstIp, dstIp = origSrcIp)

        out[20] = 3 // Type 3: Destination Unreachable
        out[21] = 3 // Code 3: Port Unreachable
        out[22] = 0; out[23] = 0 // Checksum placeholder
        writeInt32(out, 24, 0) // Unused (4 zero bytes)
        System.arraycopy(originalPacket, 0, out, 28, quotedLen)

        val icmpCsum = internetChecksum(out, 20, icmpLen)
        writeUInt16(out, 22, icmpCsum)
        return out
    }
}
