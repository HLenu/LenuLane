package net.steampp.wattkit.vpn

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class DnsQuestion(
    val transactionId: Int,
    val flags: Int,
    val qName: String,
    val qType: Int,
    val qClass: Int,
    val questionEndOffset: Int
)

/**
 * Pure-Kotlin DNS wire-format parser and builder (RFC 1035).
 * Shared by SmartDnsEngine (TUN UDP/53 interception) and DoHResolver.
 */
object DnsPacketCodec {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    fun parseQuestion(packet: ByteArray, length: Int = packet.size): DnsQuestion? {
        if (length < 12 || length > packet.size) return null
        val txId = u16(packet, 0)
        val flags = u16(packet, 2)
        // Must be a standard query (QR == 0)
        if ((flags and 0x8000) != 0) return null
        val qdCount = u16(packet, 4)
        if (qdCount < 1) return null

        val nameResult = readName(packet, 12, length) ?: return null
        val qName = nameResult.first.lowercase().trimEnd('.')
        val afterName = nameResult.second
        if (afterName + 4 > length) return null

        val qType = u16(packet, afterName)
        val qClass = u16(packet, afterName + 2)
        return DnsQuestion(
            transactionId = txId,
            flags = flags,
            qName = qName,
            qType = qType,
            qClass = qClass,
            questionEndOffset = afterName + 4
        )
    }

    fun buildQuery(domain: String, qType: Int = TYPE_A, transactionId: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream(64)
        // Header: txId, RD=1 (0x0100), QD=1, AN=0, NS=0, AR=0
        writeU16(out, transactionId)
        writeU16(out, 0x0100)
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)

        val clean = domain.trim().trimEnd('.')
        if (clean.isNotEmpty()) {
            for (label in clean.split('.')) {
                val bytes = label.toByteArray(StandardCharsets.US_ASCII)
                val len = bytes.size.coerceAtMost(63)
                out.write(len)
                out.write(bytes, 0, len)
            }
        }
        out.write(0)
        writeU16(out, qType)
        writeU16(out, CLASS_IN)
        return out.toByteArray()
    }

    fun buildAResponse(
        query: ByteArray,
        queryLen: Int,
        question: DnsQuestion,
        ipInt: Int,
        ttlSeconds: Int = 60
    ): ByteArray {
        val qEnd = question.questionEndOffset.coerceAtMost(queryLen)
        val qLen = (qEnd - 12).coerceAtLeast(0)
        val out = ByteArray(12 + qLen + 16)

        // Header
        out[0] = (question.transactionId shr 8).toByte()
        out[1] = (question.transactionId and 0xFF).toByte()
        val respFlags = (question.flags and 0x7900) or 0x8080 // QR=1, keep OPCODE & RD, set RA=1, RCODE=0
        out[2] = (respFlags shr 8).toByte()
        out[3] = (respFlags and 0xFF).toByte()
        out[4] = 0; out[5] = 1 // QDCOUNT = 1
        out[6] = 0; out[7] = 1 // ANCOUNT = 1
        out[8] = 0; out[9] = 0 // NSCOUNT = 0
        out[10] = 0; out[11] = 0 // ARCOUNT = 0

        if (qLen > 0) {
            System.arraycopy(query, 12, out, 12, qLen)
        }

        val ans = 12 + qLen
        out[ans] = 0xC0.toByte(); out[ans + 1] = 0x0C // Pointer to offset 12 (QNAME)
        out[ans + 2] = 0; out[ans + 3] = TYPE_A.toByte()
        out[ans + 4] = 0; out[ans + 5] = CLASS_IN.toByte()
        out[ans + 6] = (ttlSeconds shr 24).toByte()
        out[ans + 7] = (ttlSeconds shr 16).toByte()
        out[ans + 8] = (ttlSeconds shr 8).toByte()
        out[ans + 9] = (ttlSeconds and 0xFF).toByte()
        out[ans + 10] = 0; out[ans + 11] = 4 // RDLENGTH = 4
        out[ans + 12] = (ipInt ushr 24).toByte()
        out[ans + 13] = (ipInt ushr 16).toByte()
        out[ans + 14] = (ipInt ushr 8).toByte()
        out[ans + 15] = (ipInt and 0xFF).toByte()
        return out
    }

    fun buildEmptyNoErrorResponse(
        query: ByteArray,
        queryLen: Int,
        question: DnsQuestion
    ): ByteArray {
        val qEnd = question.questionEndOffset.coerceAtMost(queryLen)
        val qLen = (qEnd - 12).coerceAtLeast(0)
        val out = ByteArray(12 + qLen)
        out[0] = (question.transactionId shr 8).toByte()
        out[1] = (question.transactionId and 0xFF).toByte()
        val respFlags = (question.flags and 0x7900) or 0x8080
        out[2] = (respFlags shr 8).toByte()
        out[3] = (respFlags and 0xFF).toByte()
        out[4] = 0; out[5] = 1
        // ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        if (qLen > 0) {
            System.arraycopy(query, 12, out, 12, qLen)
        }
        return out
    }

    /**
     * Extracts all IPv4 A records (ipInt, ttlSeconds) from a DNS response packet.
     */
    fun extractARecords(packet: ByteArray, length: Int = packet.size): List<Pair<Int, Int>> {
        if (length < 12 || length > packet.size) return emptyList()
        val flags = u16(packet, 2)
        if ((flags and 0x8000) == 0 || (flags and 0x000F) != 0) return emptyList()

        val qdCount = u16(packet, 4)
        val anCount = u16(packet, 6)
        if (anCount == 0) return emptyList()

        var cursor = 12
        for (i in 0 until qdCount) {
            val nameRes = readName(packet, cursor, length) ?: return emptyList()
            cursor = nameRes.second + 4
            if (cursor > length) return emptyList()
        }

        val results = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until anCount) {
            val nameRes = readName(packet, cursor, length) ?: break
            cursor = nameRes.second
            if (cursor + 10 > length) break
            val rType = u16(packet, cursor)
            val rClass = u16(packet, cursor + 2)
            val ttl = ((packet[cursor + 4].toInt() and 0xFF) shl 24) or
                ((packet[cursor + 5].toInt() and 0xFF) shl 16) or
                ((packet[cursor + 6].toInt() and 0xFF) shl 8) or
                (packet[cursor + 7].toInt() and 0xFF)
            val rdLength = u16(packet, cursor + 8)
            cursor += 10
            if (cursor + rdLength > length) break

            if (rType == TYPE_A && rClass == CLASS_IN && rdLength == 4) {
                val ipInt = ((packet[cursor].toInt() and 0xFF) shl 24) or
                    ((packet[cursor + 1].toInt() and 0xFF) shl 16) or
                    ((packet[cursor + 2].toInt() and 0xFF) shl 8) or
                    (packet[cursor + 3].toInt() and 0xFF)
                results += ipInt to ttl.coerceIn(10, 3600)
            }
            cursor += rdLength
        }
        return results
    }

    fun ipIntToString(ipInt: Int): String =
        "${(ipInt ushr 24) and 0xFF}.${(ipInt ushr 16) and 0xFF}.${(ipInt ushr 8) and 0xFF}.${ipInt and 0xFF}"

    fun ipStringToInt(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            acc = (acc shl 8) or v
        }
        return acc
    }

    private fun readName(packet: ByteArray, startOffset: Int, limit: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var offset = startOffset
        var endOffset = -1
        var jumps = 0

        while (offset < limit) {
            val len = packet[offset].toInt() and 0xFF
            if (len == 0) {
                if (endOffset == -1) endOffset = offset + 1
                return sb.toString() to endOffset
            }
            if ((len and 0xC0) == 0xC0) {
                if (offset + 1 >= limit) return null
                if (endOffset == -1) endOffset = offset + 2
                val pointer = ((len and 0x3F) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                if (pointer >= limit || ++jumps > 16) return null
                offset = pointer
                continue
            }
            if (len > 63 || offset + 1 + len > limit) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(packet, offset + 1, len, StandardCharsets.US_ASCII))
            offset += 1 + len
        }
        return null
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
