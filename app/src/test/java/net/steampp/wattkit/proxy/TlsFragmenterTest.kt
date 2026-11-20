package net.steampp.wattkit.proxy

import java.io.ByteArrayOutputStream
import org.junit.Test

class TlsFragmenterTest {
    @Test fun fragmentsClientHelloAcrossSniMidpointAndPreservesExactPayload() {
        val host = "steamcommunity.com"
        val packet = buildClientHello(serverNameExtension(host))
        val fragments = TlsFragmenter.fragmentClientHello(packet, packet.size)

        check(fragments.size == 3) { "Expected 3 TLS records, got ${fragments.size}" }

        val reassembledPayload = mutableListOf<Byte>()
        for (rec in fragments) {
            check(rec.size > 5)
            check((rec[0].toInt() and 0xFF) == 0x16)
            check((rec[1].toInt() and 0xFF) == 3)
            val declaredLen = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            check(declaredLen == rec.size - 5) { "Record length mismatch: $declaredLen vs ${rec.size - 5}" }

            // No single fragmented TLS record should contain the full continuous SNI hostname
            val asAscii = String(rec, Charsets.ISO_8859_1)
            check(!asAscii.contains(host)) { "Complete SNI leaked inside a single TLS fragment" }

            for (i in 5 until rec.size) {
                reassembledPayload.add(rec[i])
            }
        }

        val originalPayload = packet.copyOfRange(5, packet.size)
        check(reassembledPayload.toByteArray().contentEquals(originalPayload)) {
            "Concatenated fragment payloads do not match original ClientHello byte-for-byte"
        }
    }

    @Test fun fragmentsClientHelloWithoutSniExtensionIntoTwoValidRecords() {
        val packet = buildClientHello(extension(43, byteArrayOf(3, 4)))
        val fragments = TlsFragmenter.fragmentClientHello(packet, packet.size)

        check(fragments.size == 2) { "Expected 2 TLS records, got ${fragments.size}" }
        val joined = fragments.flatMap { rec ->
            val declaredLen = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            check(declaredLen == rec.size - 5)
            rec.copyOfRange(5, rec.size).toList()
        }.toByteArray()
        check(joined.contentEquals(packet.copyOfRange(5, packet.size)))
    }

    @Test fun preservesNonTlsHttpPayloadIntact() {
        val http = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val out = TlsFragmenter.fragmentClientHello(http, http.size)
        check(out.size == 1 && out[0].contentEquals(http))
    }

    @Test fun tcpSegmentSplitBreaksSniWithoutAlteringSingleTlsRecord() {
        val host = "i.pximg.net"
        val packet = buildClientHello(serverNameExtension(host))
        val segments = TlsFragmenter.fragmentTcpSegments(packet, packet.size)

        check(segments.size == 3) { "Expected 3 TCP payload segments, got ${segments.size}" }
        for (seg in segments) {
            val asAscii = String(seg, Charsets.ISO_8859_1)
            check(!asAscii.contains(host)) { "Complete SNI leaked inside a single TCP segment" }
        }

        val concatenated = segments.flatMap { it.toList() }.toByteArray()
        check(concatenated.contentEquals(packet)) { "TCP segment split modified original ClientHello bytes" }

        val bos = ByteArrayOutputStream()
        TlsFragmenter.writeFragmented(bos, packet, packet.size, true, TlsDesyncMode.TCP_SEGMENT_SPLIT)
        check(bos.toByteArray().contentEquals(packet))
    }

    private fun serverNameExtension(host: String): ByteArray {
        val bytes = host.toByteArray(Charsets.US_ASCII)
        val entry = byteArrayOf(0) + uint16(bytes.size) + bytes
        return extension(0, uint16(entry.size) + entry)
    }

    private fun extension(type: Int, payload: ByteArray) = uint16(type) + uint16(payload.size) + payload

    private fun buildClientHello(extensions: ByteArray): ByteArray {
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
