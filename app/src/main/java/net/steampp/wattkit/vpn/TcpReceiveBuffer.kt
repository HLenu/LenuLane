package net.steampp.wattkit.vpn

/** Lazily grown bounded byte ring; the owning session serializes access. */
internal class TcpReceiveBuffer(private val capacity: Int) {
    private var bytes = ByteArray(0)
    private var head = 0
    var size: Int = 0
        private set
    val remaining: Int get() = capacity - size

    fun write(source: ByteArray, offset: Int, count: Int) {
        require(count in 0..remaining)
        if (count == 0) return
        if (size + count > bytes.size) {
            val next = ByteArray(maxOf(4096, bytes.size * 2, size + count).coerceAtMost(capacity))
            val first = minOf(size, bytes.size - head)
            bytes.copyInto(next, 0, head, head + first)
            bytes.copyInto(next, first, 0, size - first)
            bytes = next
            head = 0
        }
        val tail = (head + size) % bytes.size
        val first = minOf(count, bytes.size - tail)
        source.copyInto(bytes, tail, offset, offset + first)
        source.copyInto(bytes, 0, offset + first, offset + count)
        size += count
    }

    fun read(destination: ByteArray, offset: Int, count: Int): Int {
        val n = minOf(count, size)
        if (n == 0) return 0
        val first = minOf(n, bytes.size - head)
        bytes.copyInto(destination, offset, head, head + first)
        bytes.copyInto(destination, offset + first, 0, n - first)
        head = (head + n) % bytes.size
        size -= n
        if (size == 0) head = 0
        return n
    }

    fun clear() {
        bytes = ByteArray(0)
        head = 0
        size = 0
    }
}
