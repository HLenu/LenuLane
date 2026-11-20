package net.steampp.wattkit.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class RuleRuntimeStats(
    val dnsHits: Long = 0,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val lastResolvedIp: String = "",
    val lastLatencyMs: Long = 0
)

data class ConnectionLogEntry(
    val id: Long,
    val timestampMs: Long,
    val host: String,
    val ruleName: String,
    val strategyBadge: String,
    val targetIp: String,
    val latencyMs: Long,
    val status: String, // "ACTIVE", "OK", "ERR"
    val rxBytes: Long = 0,
    val txBytes: Long = 0
)

/**
 * Thread-safe runtime telemetry for rule DNS/TCP hits, per-rule byte counters,
 * and a 60-entry live connection event log displayed in the UI.
 */
object TrafficTelemetry {
    private const val MAX_LOG_ENTRIES = 60

    private val lock = Any()
    private val nextEntryId = AtomicLong(1L)
    private val statsMap = HashMap<String, RuleRuntimeStats>()
    private val logs = ArrayDeque<ConnectionLogEntry>(MAX_LOG_ENTRIES)
    private var lastBytePublishMs = 0L

    private val _ruleStats = MutableStateFlow<Map<String, RuleRuntimeStats>>(emptyMap())
    val ruleStats = _ruleStats.asStateFlow()

    private val _recentConnections = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    val recentConnections = _recentConnections.asStateFlow()

    private val _totalHits = MutableStateFlow(0L)
    val totalHits = _totalHits.asStateFlow()

    private val _activeConnectionCount = MutableStateFlow(0)
    val activeConnectionCount = _activeConnectionCount.asStateFlow()

    fun recordDnsHit(ruleId: String, @Suppress("UNUSED_PARAMETER") host: String) {
        synchronized(lock) {
            val prev = statsMap[ruleId] ?: RuleRuntimeStats()
            statsMap[ruleId] = prev.copy(dnsHits = prev.dnsHits + 1)
            _totalHits.value += 1
            publishStatsLocked()
        }
    }

    fun onConnectionStarted(
        ruleId: String?,
        ruleName: String,
        strategyBadge: String,
        host: String,
        targetIp: String,
        latencyMs: Long
    ): Long {
        val id = nextEntryId.getAndIncrement()
        synchronized(lock) {
            if (ruleId != null) {
                val prev = statsMap[ruleId] ?: RuleRuntimeStats()
                statsMap[ruleId] = prev.copy(
                    activeConnections = prev.activeConnections + 1,
                    totalConnections = prev.totalConnections + 1,
                    lastResolvedIp = targetIp,
                    lastLatencyMs = latencyMs
                )
                if (prev.dnsHits == 0L) {
                    // HTTP/SOCKS/non-Fake-IP direct connection hit
                    _totalHits.value += 1
                }
            }
            _activeConnectionCount.value += 1

            if (logs.size >= MAX_LOG_ENTRIES) {
                logs.removeLast()
            }
            logs.addFirst(
                ConnectionLogEntry(
                    id = id,
                    timestampMs = System.currentTimeMillis(),
                    host = host,
                    ruleName = ruleName,
                    strategyBadge = strategyBadge,
                    targetIp = targetIp,
                    latencyMs = latencyMs,
                    status = "ACTIVE"
                )
            )
            publishStatsLocked()
            publishLogsLocked()
        }
        return id
    }

    fun onConnectionBytes(entryId: Long, ruleId: String?, rxDelta: Long, txDelta: Long) {
        if (rxDelta <= 0L && txDelta <= 0L) return
        synchronized(lock) {
            if (ruleId != null) {
                val prev = statsMap[ruleId] ?: RuleRuntimeStats()
                statsMap[ruleId] = prev.copy(
                    rxBytes = prev.rxBytes + rxDelta,
                    txBytes = prev.txBytes + txDelta
                )
            }
            val idx = logs.indexOfFirst { it.id == entryId }
            if (idx >= 0) {
                val cur = logs[idx]
                logs[idx] = cur.copy(
                    rxBytes = cur.rxBytes + rxDelta,
                    txBytes = cur.txBytes + txDelta
                )
            }
            val now = System.currentTimeMillis()
            if (now - lastBytePublishMs >= 400L) {
                lastBytePublishMs = now
                publishStatsLocked()
                publishLogsLocked()
            }
        }
    }

    fun onConnectionFinished(
        entryId: Long,
        ruleId: String?,
        success: Boolean,
        latencyMs: Long? = null
    ) {
        synchronized(lock) {
            if (ruleId != null) {
                val prev = statsMap[ruleId] ?: RuleRuntimeStats()
                statsMap[ruleId] = prev.copy(
                    activeConnections = (prev.activeConnections - 1).coerceAtLeast(0),
                    lastLatencyMs = latencyMs ?: prev.lastLatencyMs
                )
            }
            _activeConnectionCount.value = (_activeConnectionCount.value - 1).coerceAtLeast(0)

            val idx = logs.indexOfFirst { it.id == entryId }
            if (idx >= 0) {
                val cur = logs[idx]
                logs[idx] = cur.copy(
                    status = if (success) "OK" else "ERR",
                    latencyMs = latencyMs ?: cur.latencyMs
                )
            }
            publishStatsLocked()
            publishLogsLocked()
        }
    }

    fun reset() {
        synchronized(lock) {
            statsMap.clear()
            logs.clear()
            _totalHits.value = 0L
            _activeConnectionCount.value = 0
            publishStatsLocked()
            publishLogsLocked()
        }
    }

    private fun publishStatsLocked() {
        _ruleStats.value = HashMap(statsMap)
    }

    private fun publishLogsLocked() {
        _recentConnections.value = logs.toList()
    }
}
