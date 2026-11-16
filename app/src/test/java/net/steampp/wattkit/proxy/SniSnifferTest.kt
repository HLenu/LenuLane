package net.steampp.wattkit.proxy

import org.junit.Test

class SniSnifferTest {
    @Test fun extractsHostname() {
        val packet = clientHello(serverName("steamcommunity.com"))
        check(SniSniffer.sniffSni(packet, packet.size) == "steamcommunity.com")
    }

    @Test fun skipsUnknownExtensions() {
        val packet = clientHello(extension(43, byteArrayOf(3, 4)) + serverName("github.com"))
        check(SniSniffer.sniffSni(packet, packet.size) == "github.com")
    }

    @Test fun handlesMissingServerName() {
        val packet = clientHello(extension(43, byteArrayOf(3, 4)))
        check(SniSniffer.sniffSni(packet, packet.size) == null)
    }

    @Test fun rejectsTruncatedRecordsAndInvalidLengthArguments() {
        val packet = clientHello(serverName("pixiv.net"))
        for (length in 0 until packet.size) {
            check(SniSniffer.sniffSni(packet, length) == null) { "accepted $length-byte prefix" }
        }
        check(SniSniffer.sniffSni(packet, -1) == null)
        check(SniSniffer.sniffSni(packet, packet.size + 1) == null)
    }

    @Test fun rejectsServerNameListBeyondItsExtension() {
        val broken = serverName("github.com").also {
            it[4] = 0x7f
            it[5] = 0x7f
        }
        val packet = clientHello(broken + extension(43, byteArrayOf(3, 4)))
        check(SniSniffer.sniffSni(packet, packet.size) == null)
    }

    @Test fun rejectsExtensionLengthBeyondClientHello() {
        val broken = serverName("github.com").also {
            it[2] = 0x7f
            it[3] = 0x7f
        }
        val packet = clientHello(broken)
        check(SniSniffer.sniffSni(packet, packet.size) == null)
    }

    @Test fun rejectsTruncatedHandshakeAcrossTlsRecords() {
        val packet = clientHello(serverName("github.com"))
        // Advertise a longer handshake than the first record actually contains.
        packet[8] = (packet[8] + 1).toByte()
        check(SniSniffer.sniffSni(packet, packet.size) == null)
    }

    @Test fun rejectsInvalidHostnames() {
        for (host in listOf("bad/host", "a..b", "-github.com", "github.com-")) {
            val packet = clientHello(serverName(host))
            check(SniSniffer.sniffSni(packet, packet.size) == null) { "accepted $host" }
        }
    }

    @Test fun rejectsDuplicateHostNameEntries() {
        val first = "a.example".toByteArray(Charsets.US_ASCII)
        val second = "b.example".toByteArray(Charsets.US_ASCII)
        val names = byteArrayOf(0) + uint16(first.size) + first + byteArrayOf(0) + uint16(second.size) + second
        val packet = clientHello(extension(0, uint16(names.size) + names))
        check(SniSniffer.sniffSni(packet, packet.size) == null)
    }

    private fun serverName(host: String): ByteArray {
        val bytes = host.toByteArray(Charsets.US_ASCII)
        val entry = byteArrayOf(0) + uint16(bytes.size) + bytes
        return extension(0, uint16(entry.size) + entry)
    }

    private fun extension(type: Int, payload: ByteArray) = uint16(type) + uint16(payload.size) + payload

    private fun clientHello(extensions: ByteArray): ByteArray {
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) +
            uint16(2) + byteArrayOf(0x13, 0x01) + byteArrayOf(1, 0) +
            uint16(extensions.size) + extensions
        val handshake = byteArrayOf(1) + byteArrayOf(
            (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()
        ) + body
        return byteArrayOf(0x16, 3, 3) + uint16(handshake.size) + handshake
    }

    private fun uint16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
}
