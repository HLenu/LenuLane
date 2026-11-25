package net.steampp.wattkit.vpn

/** A send must fit the peer's remaining advertised window and retransmit capacity. */
internal object TcpSendWindow {
    fun available(requested: Int, window: Int, inFlight: Int, pending: Int, limit: Int): Int {
        if (pending >= limit || inFlight < 0) return 0
        return minOf(requested, (window - inFlight).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
