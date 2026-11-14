package net.steampp.wattkit.proxy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object SniSniffer {
    /**
     * Extracts the Server Name Indication (SNI) from a TLS ClientHello packet.
     * Returns null if the packet is not a valid TLS ClientHello or contains no SNI extension.
     */
    fun sniffSni(data: ByteArray, length: Int): String? {
        if (length < 44) return null
        val buffer = ByteBuffer.wrap(data, 0, length)

        // 1. TLS Record Header
        val contentType = buffer.get().toInt() and 0xFF
        if (contentType != 0x16) return null // Must be Handshake (22)

        val majorVersion = buffer.get().toInt() and 0xFF
        val minorVersion = buffer.get().toInt() and 0xFF
        if (majorVersion != 3) return null // SSL 3.0 / TLS 1.x

        val recordLength = buffer.short.toInt() and 0xFFFF
        if (recordLength > length - 5) {
            // Buffer has partial record, proceed with available bytes if possible
        }

        // 2. Handshake Header
        if (buffer.remaining() < 4) return null
        val handshakeType = buffer.get().toInt() and 0xFF
        if (handshakeType != 1) return null // Must be ClientHello (1)

        val handshakeLen1 = buffer.get().toInt() and 0xFF
        val handshakeLen2 = buffer.get().toInt() and 0xFF
        val handshakeLen3 = buffer.get().toInt() and 0xFF
        val handshakeLength = (handshakeLen1 shl 16) or (handshakeLen2 shl 8) or handshakeLen3

        // Client Version (2 bytes) + Random (32 bytes) = 34 bytes
        if (buffer.remaining() < 34) return null
        buffer.position(buffer.position() + 34)

        // Session ID
        if (buffer.remaining() < 1) return null
        val sessionIdLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < sessionIdLen) return null
        buffer.position(buffer.position() + sessionIdLen)

        // Cipher Suites
        if (buffer.remaining() < 2) return null
        val cipherSuitesLen = buffer.short.toInt() and 0xFFFF
        if (buffer.remaining() < cipherSuitesLen) return null
        buffer.position(buffer.position() + cipherSuitesLen)

        // Compression Methods
        if (buffer.remaining() < 1) return null
        val compressionLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < compressionLen) return null
        buffer.position(buffer.position() + compressionLen)

        // Extensions
        if (buffer.remaining() < 2) return null
        val extensionsLen = buffer.short.toInt() and 0xFFFF

        while (buffer.remaining() >= 4) {
            val extType = buffer.short.toInt() and 0xFFFF
            val extLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < extLen) break

            if (extType == 0) { // server_name extension
                val extEnd = buffer.position() + extLen
                if (buffer.remaining() >= 2) {
                    val serverNameListLen = buffer.short.toInt() and 0xFFFF
                    while (buffer.position() < extEnd && buffer.remaining() >= 3) {
                        val nameType = buffer.get().toInt() and 0xFF
                        val nameLen = buffer.short.toInt() and 0xFFFF
                        if (nameType == 0 && buffer.remaining() >= nameLen) { // host_name
                            val nameBytes = ByteArray(nameLen)
                            buffer.get(nameBytes)
                            return String(nameBytes, StandardCharsets.UTF_8)
                        } else {
                            if (buffer.remaining() >= nameLen) {
                                buffer.position(buffer.position() + nameLen)
                            } else {
                                break
                            }
                        }
                    }
                }
                return null
            } else {
                buffer.position(buffer.position() + extLen)
            }
        }

        return null
    }
}
