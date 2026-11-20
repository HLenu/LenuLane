package net.steampp.wattkit.proxy

import java.nio.charset.StandardCharsets

/** Extracts a DNS hostname and its byte range from a complete, single-record TLS ClientHello. */
object SniSniffer {
    fun sniffSni(data: ByteArray, length: Int): String? = parseSni(data, length)?.first

    /**
     * Returns the byte range [start, end) within [data] containing the SNI host_name ASCII bytes,
     * or null if the packet is not a valid single-record TLS ClientHello with a valid SNI extension.
     */
    fun locateSniRange(data: ByteArray, length: Int): IntRange? = parseSni(data, length)?.second

    private fun parseSni(data: ByteArray, length: Int): Pair<String, IntRange>? {
        if (length < 9 || length > data.size) return null

        // TLS record: content type, legacy version, and payload length.
        if (u8(data, 0) != 0x16 || u8(data, 1) != 3) return null
        val recordLength = u16(data, 3)
        if (recordLength < 4 || recordLength > length - 5) return null
        val recordEnd = 5 + recordLength

        // A fragmented ClientHello across multiple TLS records is not parsed.
        if (u8(data, 5) != 1) return null
        val helloLength = (u8(data, 6) shl 16) or (u8(data, 7) shl 8) or u8(data, 8)
        val helloEnd = 9 + helloLength
        if (helloEnd > recordEnd) return null

        var cursor = 9
        // legacy_version (2) + random (32)
        if (helloEnd - cursor < 34) return null
        cursor += 34

        if (helloEnd - cursor < 1) return null
        val sessionIdLength = u8(data, cursor++)
        if (sessionIdLength > helloEnd - cursor) return null
        cursor += sessionIdLength

        if (helloEnd - cursor < 2) return null
        val cipherSuitesLength = u16(data, cursor)
        cursor += 2
        if (cipherSuitesLength < 2 || cipherSuitesLength % 2 != 0 || cipherSuitesLength > helloEnd - cursor) return null
        cursor += cipherSuitesLength

        if (helloEnd - cursor < 1) return null
        val compressionLength = u8(data, cursor++)
        if (compressionLength < 1 || compressionLength > helloEnd - cursor) return null
        cursor += compressionLength

        if (cursor == helloEnd) return null // No extensions.
        if (helloEnd - cursor < 2) return null
        val extensionsLength = u16(data, cursor)
        cursor += 2
        if (extensionsLength != helloEnd - cursor) return null

        while (cursor < helloEnd) {
            if (helloEnd - cursor < 4) return null
            val type = u16(data, cursor)
            val extensionLength = u16(data, cursor + 2)
            cursor += 4
            if (extensionLength > helloEnd - cursor) return null
            if (type == 0) return parseServerName(data, cursor, cursor + extensionLength)
            cursor += extensionLength
        }
        return null
    }

    private fun parseServerName(data: ByteArray, start: Int, end: Int): Pair<String, IntRange>? {
        if (end - start < 2) return null
        val listLength = u16(data, start)
        if (listLength != end - start - 2) return null

        var cursor = start + 2
        var match: Pair<String, IntRange>? = null
        while (cursor < end) {
            if (end - cursor < 3) return null
            val nameType = u8(data, cursor)
            val nameLength = u16(data, cursor + 1)
            cursor += 3
            if (nameLength > end - cursor) return null
            if (nameType == 0) {
                if (match != null) return null // Duplicate host_name entries.
                val hostname = readHostName(data, cursor, nameLength) ?: return null
                match = hostname to (cursor until (cursor + nameLength))
            }
            cursor += nameLength
        }
        return match
    }

    private fun readHostName(data: ByteArray, start: Int, length: Int): String? {
        if (length !in 1..253) return null
        for (i in start until start + length) {
            val ch = u8(data, i)
            if (ch !in 0x30..0x39 && ch !in 0x41..0x5A &&
                ch !in 0x61..0x7A && ch != 0x2D && ch != 0x2E) return null
        }
        val hostname = String(data, start, length, StandardCharsets.US_ASCII)
        if (hostname.split('.').any { label ->
                label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-'
            }) return null
        return hostname
    }

    private fun u8(data: ByteArray, index: Int) = data[index].toInt() and 0xFF
    private fun u16(data: ByteArray, index: Int) = (u8(data, index) shl 8) or u8(data, index + 1)
}
