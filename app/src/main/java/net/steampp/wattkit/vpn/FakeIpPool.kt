package net.steampp.wattkit.vpn

import java.util.LinkedHashMap

/**
 * Bidirectional Fake-IP allocator in the RFC 2544 benchmarking block (198.18.0.0/15),
 * plus a bounded reverse-lookup cache for snooped non-Fake-IP DNS responses.
 */
class FakeIpPool {
    companion object {
        // Range: 198.18.0.10 .. 198.19.255.250 (~131,050 slots)
        private const val START_IP: Int = (198 shl 24) or (18 shl 16) or 10
        private const val END_IP: Int = (198 shl 24) or (19 shl 16) or (255 shl 8) or 250
        private const val MAX_SNOOP_ENTRIES = 2048

        fun isFakeIp(ipInt: Int): Boolean {
            val b0 = (ipInt ushr 24) and 0xFF
            val b1 = (ipInt ushr 16) and 0xFF
            return b0 == 198 && (b1 == 18 || b1 == 19)
        }
    }

    private val lock = Any()
    private val domainToFakeIp = HashMap<String, Int>()
    private val fakeIpToDomain = HashMap<Int, String>()
    private var cursor: Int = START_IP

    private val snoopedIpToDomain = object : LinkedHashMap<Int, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > MAX_SNOOP_ENTRIES
    }

    fun allocate(domain: String): Int {
        val clean = domain.lowercase().trim().trimEnd('.')
        synchronized(lock) {
            domainToFakeIp[clean]?.let { return it }

            var candidate = cursor
            // Advance linearly; evict if pool wrapped around all 131k addresses.
            val allocated = candidate
            cursor = if (candidate >= END_IP) START_IP else candidate + 1

            fakeIpToDomain.remove(allocated)?.let { oldDomain ->
                domainToFakeIp.remove(oldDomain)
            }

            domainToFakeIp[clean] = allocated
            fakeIpToDomain[allocated] = clean
            return allocated
        }
    }

    fun recordSnoopedIp(ipInt: Int, domain: String) {
        if (isFakeIp(ipInt)) return
        val clean = domain.lowercase().trim().trimEnd('.')
        if (clean.isEmpty()) return
        synchronized(lock) {
            snoopedIpToDomain[ipInt] = clean
        }
    }

    fun lookupDomain(ipInt: Int): String? {
        synchronized(lock) {
            return fakeIpToDomain[ipInt] ?: snoopedIpToDomain[ipInt]
        }
    }

    fun clear() {
        synchronized(lock) {
            domainToFakeIp.clear()
            fakeIpToDomain.clear()
            snoopedIpToDomain.clear()
            cursor = START_IP
        }
    }
}
