package net.steampp.wattkit.proxy

import java.io.OutputStream

/**
 * RFC 5246 / RFC 8446 compliant TLS Record Fragmentation helper.
 * Splits an outbound TLS ClientHello record across multiple valid TLS Handshake records,
 * cutting squarely through the middle of the SNI hostname bytes so middlebox DPI cannot
 * extract the full SNI in one contiguous record while every TLS server reassembles cleanly.
 */
object TlsFragmenter {
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
            // Split point inside the SNI host_name string (relative to data, >= payloadStart + 3 if len >= 2)
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

    fun writeFragmented(
        output: OutputStream,
        data: ByteArray,
        length: Int,
        enableFragment: Boolean
    ) {
        if (length <= 0) return
        if (!enableFragment) {
            output.write(data, 0, length)
            output.flush()
            return
        }
        val chunks = fragmentClientHello(data, length)
        for (chunk in chunks) {
            output.write(chunk)
            output.flush()
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
