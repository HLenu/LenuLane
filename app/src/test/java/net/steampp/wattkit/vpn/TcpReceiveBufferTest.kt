package net.steampp.wattkit.vpn

import org.junit.Test

class TcpReceiveBufferTest {
    @Test fun boundedBufferWrapsAndPreservesOrder() {
        val buffer = TcpReceiveBuffer(8)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5, 6), 0, 6)
        val first = ByteArray(4)
        check(buffer.read(first, 0, 4) == 4)
        check(first.contentEquals(byteArrayOf(1, 2, 3, 4)))
        buffer.write(byteArrayOf(7, 8, 9, 10, 11, 12), 0, 6)
        check(buffer.remaining == 0)
        check(runCatching { buffer.write(byteArrayOf(13), 0, 1) }.isFailure)
        val output = ByteArray(8)
        check(buffer.read(output, 0, 8) == 8)
        check(output.contentEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12)))
        check(buffer.remaining == 8)
        check(buffer.read(output, 0, 8) == 0)
    }

    @Test fun growthPreservesWrappedBytesAndClearReleasesCapacity() {
        val buffer = TcpReceiveBuffer(8192)
        val input = ByteArray(4096) { it.toByte() }
        buffer.write(input, 0, input.size)
        val prefix = ByteArray(2048)
        buffer.read(prefix, 0, prefix.size)
        buffer.write(input, 0, input.size)
        val output = ByteArray(6144)
        check(buffer.read(output, 0, output.size) == output.size)
        check(output.contentEquals(input.copyOfRange(2048, 4096) + input))
        buffer.clear()
        check(buffer.size == 0 && buffer.remaining == 8192)
    }
}
