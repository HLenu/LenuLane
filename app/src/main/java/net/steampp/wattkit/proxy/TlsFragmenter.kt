package net.steampp.wattkit.proxy

import java.io.ByteArrayOutputStream
import java.io.OutputStream

enum class TlsDesyncMode(val displayName: String, val badge: String) {
    ADAPTIVE("自适应分片 (推荐)", "自适应"),
    TLS_RECORD_SPLIT("TLS 记录层分片", "记录分片"),
    TCP_SEGMENT_SPLIT("TCP 载荷层分片", "TCP 分片");

    companion object {
        fun fromName(name: String?): TlsDesyncMode =
            entries.firstOrNull { it.name == name } ?: ADAPTIVE
    }
}

/**
 * Dual-mode ClientHello DPI desync helper inspired by Outline SDK / GoodbyeDPI:
 * 1) TLS_RECORD_SPLIT: splits an outbound TLS ClientHello into 3 RFC-compliant TLS Handshake records
 *    across the SNI hostname midpoint.
 * 2) TCP_SEGMENT_SPLIT: keeps the TLS record header intact and flushes the raw ClientHello bytes
 *    across separate TCP segments (initial 2 bytes, up to SNI midpoint, remainder) for strict servers
 *    that reject multi-record ClientHello handshakes.
 */
object TlsFragmenter {
    private const val TCP_SEGMENT_PACING_MS = 1L

    fun fragmentClientHello(data: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0 || length > data.size) return emptyList()
        if (length <= 10) return listOf(data.copyOf(length))

        val contentType = data[0].toInt() and 0xFF
        val major = data[1].toInt() and 0xFF
        val minor = data[2].toInt() and 0xFF
        if (contentType != 0x16 || major != 3) {
            return listOf(data.copyOf(length))
        }

        val recordLength = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (recordLength < 4 || recordLength > length - 5) {
            return listOf(data.copyOf(length))
        }

        val payloadStart = 5
        val payloadEnd = payloadStart + recordLength
        val sniRange = SniSniffer.locateSniRange(data, length)

        val records = mutableListOf<ByteArray>()
        if (sniRange != null && sniRange.first >= payloadStart + 2 && sniRange.last < payloadEnd) {
            val sniLen = sniRange.last - sniRange.first + 1
            val sniSplitAbs = if (sniLen >= 2) {
                sniRange.first + (sniLen / 2)
            } else {
                sniRange.first
            }

            // Record 1: first 2 bytes of handshake header (type + high len byte)
            val split1 = payloadStart + 2
            records += buildRecord(major, minor, data, payloadStart, split1)

            // Record 2: up to the midpoint of SNI hostname
            if (sniSplitAbs > split1) {
                records += buildRecord(major, minor, data, split1, sniSplitAbs)
            }

            // Record 3: from SNI midpoint to end of TLS record payload
            if (sniSplitAbs < payloadEnd) {
                records += buildRecord(major, minor, data, sniSplitAbs.coerceAtLeast(split1), payloadEnd)
            }
        } else {
            val mid = payloadStart + (recordLength / 2).coerceAtLeast(1)
            records += buildRecord(major, minor, data, payloadStart, mid)
            if (mid < payloadEnd) {
                records += buildRecord(major, minor, data, mid, payloadEnd)
            }
        }

        if (payloadEnd < length) {
            records += data.copyOfRange(payloadEnd, length)
        }
        return records
    }

    /**
     * Splits ClientHello raw bytes across TCP segments without modifying TLS record headers.
     */
    fun fragmentTcpSegments(data: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0 || length > data.size) return emptyList()
        if (length <= 10) return listOf(data.copyOf(length))

        val contentType = data[0].toInt() and 0xFF
        val major = data[1].toInt() and 0xFF
        if (contentType != 0x16 || major != 3) {
            return listOf(data.copyOf(length))
        }

        val sniRange = SniSniffer.locateSniRange(data, length)
        val segments = mutableListOf<ByteArray>()
        if (sniRange != null && sniRange.first >= 7 && sniRange.last < length) {
            val sniLen = sniRange.last - sniRange.first + 1
            val sniSplit = if (sniLen >= 2) sniRange.first + (sniLen / 2) else sniRange.first
            val firstCut = 2
            segments += data.copyOfRange(0, firstCut)
            if (sniSplit > firstCut) {
                segments += data.copyOfRange(firstCut, sniSplit)
            }
            if (sniSplit < length) {
                segments += data.copyOfRange(sniSplit.coerceAtLeast(firstCut), length)
            }
        } else {
            val mid = (length / 2).coerceAtLeast(2)
            segments += data.copyOfRange(0, mid)
            if (mid < length) {
                segments += data.copyOfRange(mid, length)
            }
        }
        return segments
    }

    fun writeFragmented(
        output: OutputStream,
        data: ByteArray,
        length: Int,
        enableFragment: Boolean,
        mode: TlsDesyncMode = TlsDesyncMode.TLS_RECORD_SPLIT
    ) {
        if (length <= 0) return
        if (!enableFragment) {
            output.write(data, 0, length)
            output.flush()
            return
        }
        val chunks = when (mode) {
            TlsDesyncMode.TCP_SEGMENT_SPLIT -> fragmentTcpSegments(data, length)
            TlsDesyncMode.TLS_RECORD_SPLIT,
            TlsDesyncMode.ADAPTIVE -> fragmentClientHello(data, length)
        }
        val paceTcp = chunks.size > 1 && output !is ByteArrayOutputStream
        for (i in chunks.indices) {
            output.write(chunks[i])
            output.flush()
            if (paceTcp && i < chunks.lastIndex) {
                try {
                    Thread.sleep(TCP_SEGMENT_PACING_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun buildRecord(
        major: Int,
        minor: Int,
        src: ByteArray,
        from: Int,
        to: Int
    ): ByteArray {
        val partLen = to - from
        val rec = ByteArray(5 + partLen)
        rec[0] = 0x16
        rec[1] = major.toByte()
        rec[2] = minor.toByte()
        rec[3] = (partLen shr 8).toByte()
        rec[4] = (partLen and 0xFF).toByte()
        System.arraycopy(src, from, rec, 5, partLen)
        return rec
    }
}
