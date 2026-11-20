package net.steampp.wattkit.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.steampp.wattkit.MainActivity
import net.steampp.wattkit.R
import net.steampp.wattkit.proxy.DoHResolver
import net.steampp.wattkit.proxy.LocalProxyServer
import net.steampp.wattkit.proxy.RoutingMode
import net.steampp.wattkit.proxy.RuleManager
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WattVpnService : VpnService() {
    private val tag = "WattVpnService"
    private val lifecycleLock = Any()

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunEngine: TunPacketEngine? = null
    private var proxyServer: LocalProxyServer? = null
    private var fakeIpPool: FakeIpPool? = null
    private var smartDnsEngine: SmartDnsEngine? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val physicalNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trafficJob: Job? = null

    companion object {
        const val ACTION_START = "net.steampp.wattkit.vpn.START"
        const val ACTION_STOP = "net.steampp.wattkit.vpn.STOP"
        const val ACTION_RESTART = "net.steampp.wattkit.vpn.RESTART"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wattkit_vpn_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _startTime = MutableStateFlow(0L)
        val startTime = _startTime.asStateFlow()

        private val _rxSpeed = MutableStateFlow("0.0 KB/s")
        val rxSpeed = _rxSpeed.asStateFlow()

        private val _txSpeed = MutableStateFlow("0.0 KB/s")
        val txSpeed = _txSpeed.asStateFlow()

        private val _networkStatus = MutableStateFlow("未启动")
        val networkStatus = _networkStatus.asStateFlow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn(stopService = true)
            ACTION_RESTART -> {
                if (_isRunning.value) {
                    stopVpn(stopService = false)
                    startVpn()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        synchronized(lifecycleLock) {
            if (_isRunning.value) return
            val mode = RuleManager.routingMode.value
            Log.i(tag, "Starting WattKit VPN Service (mode=$mode)...")

            try {
                createNotificationChannel()
                val notification = createNotification(mode)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                // Bind DoH resolver protection callbacks so HTTPS DoH queries bypass tun0
                DoHResolver.setVpnProtectCallbacks(
                    tcpProtect = { sock -> protect(sock) },
                    udpProtect = { sock -> protect(sock) }
                )

                // 1. Initialize in-process Fake-IP pool & Smart DNS engine
                val pool = FakeIpPool().also { fakeIpPool = it }
                val dnsEngine = SmartDnsEngine(
                    fakeIpPool = pool,
                    protectUdp = { sock -> protect(sock) }
                ).also { smartDnsEngine = it }

                // 2. Seed physical DNS servers and underlying network BEFORE establishing TUN
                seedInitialPhysicalNetwork(dnsEngine)
                registerPhysicalNetworkCallback(dnsEngine)

                // 3. Configure and establish TUN interface (10.0.0.1 virtual DNS)
                vpnInterface = configureAndEstablishTun(mode)
                if (vpnInterface == null) {
                    Log.e(tag, "Failed to establish VPN interface")
                    stopVpn(stopService = true)
                    return
                }

                // 4. Start in-process pure-Kotlin TUN packet engine
                tunEngine = TunPacketEngine(
                    vpnInterface = vpnInterface!!,
                    smartDnsEngine = dnsEngine,
                    fakeIpPool = pool,
                    protectTcp = { sock -> protect(sock) },
                    protectUdp = { sock -> protect(sock) }
                ).apply { start() }

                // 5. Start local SOCKS5/HTTP CONNECT listener for auxiliary/manual tools
                proxyServer = LocalProxyServer(port = 28282, vpnService = this).apply {
                    start()
                }

                _startTime.value = System.currentTimeMillis()
                _isRunning.value = true
                startTrafficMonitor()
                Log.i(tag, "WattKit VPN started cleanly with in-process TunPacketEngine ($mode)")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start VPN", e)
                stopVpn(stopService = true)
            }
        }
    }

    private fun configureAndEstablishTun(mode: RoutingMode): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("WattKit Acceleration")
            .addAddress("10.0.0.2", 24)
            .addDnsServer("10.0.0.1")
            .setMtu(1500)

        when (mode) {
            RoutingMode.SMART_SPLIT -> {
                // Only intercept virtual DNS (10.0.0.1/32) and Fake-IP subnet (198.18.0.0/15).
                // Unmatched domains receive real public IPs and bypass tun0 over physical Wi-Fi/5G.
                builder.addRoute("10.0.0.1", 32)
                builder.addRoute("198.18.0.0", 15)
            }
            RoutingMode.GLOBAL_TUN -> {
                builder.addRoute("0.0.0.0", 0)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish()
    }

    private fun seedInitialPhysicalNetwork(dnsEngine: SmartDnsEngine) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        var preferred: Network? = null
        val collectedDns = mutableListOf<String>()

        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            physicalNetworks[net] = caps
            if (preferred == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                preferred = net
            }
            cm.getLinkProperties(net)?.let { lp ->
                collectedDns.addAll(extractIpv4Dns(lp))
            }
        }

        if (collectedDns.isNotEmpty()) {
            dnsEngine.updateUpstreamServers(collectedDns)
        }
        if (preferred != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            setUnderlyingNetworks(arrayOf(preferred))
            _networkStatus.value = formatNetworkLabel(physicalNetworks[preferred])
        } else {
            _networkStatus.value = "物理网络就绪"
        }
    }

    private fun registerPhysicalNetworkCallback(dnsEngine: SmartDnsEngine) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        unregisterNetworkCallbackLocked()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                physicalNetworks[network] = caps
                applyPhysicalNetworkChange(cm, dnsEngine)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                physicalNetworks[network] = networkCapabilities
                applyPhysicalNetworkChange(cm, dnsEngine)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                dnsEngine.updateUpstreamServers(extractIpv4Dns(linkProperties))
            }

            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                if (physicalNetworks.isEmpty()) {
                    _networkStatus.value = "等待物理网络恢复..."
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        setUnderlyingNetworks(null)
                    }
                } else {
                    applyPhysicalNetworkChange(cm, dnsEngine)
                }
            }
        }

        networkCallback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            Log.w(tag, "Failed to register physical network callback: ${e.message}")
        }
    }

    private fun applyPhysicalNetworkChange(cm: ConnectivityManager, dnsEngine: SmartDnsEngine) {
        val activeEntry = physicalNetworks.entries.firstOrNull {
            it.value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: physicalNetworks.entries.firstOrNull()

        if (activeEntry != null) {
            val net = activeEntry.key
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(arrayOf(net))
            }
            cm.getLinkProperties(net)?.let { lp ->
                dnsEngine.updateUpstreamServers(extractIpv4Dns(lp))
            }
            DoHResolver.clearCache()
            _networkStatus.value = formatNetworkLabel(activeEntry.value)
        }
    }

    private fun unregisterNetworkCallbackLocked() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        physicalNetworks.clear()
    }

    private fun extractIpv4Dns(lp: LinkProperties): List<String> =
        lp.dnsServers.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it != "10.0.0.1" && !it.startsWith("127.") }

    private fun formatNetworkLabel(caps: NetworkCapabilities?): String {
        if (caps == null) return "物理网络已连接"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 物理网络正常"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝数据网络正常"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网连接正常"
            else -> "物理网络已连接"
        }
    }

    private fun stopVpn(stopService: Boolean) {
        synchronized(lifecycleLock) {
            Log.i(tag, "Stopping WattKit VPN Service...")
            _isRunning.value = false
            trafficJob?.cancel()
            trafficJob = null
            _startTime.value = 0L
            _rxSpeed.value = "0.0 KB/s"
            _txSpeed.value = "0.0 KB/s"
            _networkStatus.value = "未启动"

            unregisterNetworkCallbackLocked()
            DoHResolver.setVpnProtectCallbacks(null, null)

            try { tunEngine?.stop() } catch (_: Exception) {}
            tunEngine = null

            try { proxyServer?.stop() } catch (_: Exception) {}
            proxyServer = null

            fakeIpPool?.clear()
            fakeIpPool = null
            smartDnsEngine = null

            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null

            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopVpn(stopService = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(tag, "VPN permission revoked by system, shutting down")
        stopVpn(stopService = true)
    }

    private fun startTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = serviceScope.launch {
            val myUid = Process.myUid()
            var prevUidRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0L)
            var prevUidTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0L)
            var prevProxyRx = LocalProxyServer.totalRxBytes.get()
            var prevProxyTx = LocalProxyServer.totalTxBytes.get()

            while (_isRunning.value) {
                delay(1000)
                val currUidRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0L)
                val currUidTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0L)
                val currProxyRx = LocalProxyServer.totalRxBytes.get()
                val currProxyTx = LocalProxyServer.totalTxBytes.get()

                val deltaUidRx = if (currUidRx >= prevUidRx) currUidRx - prevUidRx else 0L
                val deltaUidTx = if (currUidTx >= prevUidTx) currUidTx - prevUidTx else 0L
                val deltaProxyRx = if (currProxyRx >= prevProxyRx) currProxyRx - prevProxyRx else 0L
                val deltaProxyTx = if (currProxyTx >= prevProxyTx) currProxyTx - prevProxyTx else 0L

                val deltaRx = maxOf(deltaUidRx, deltaProxyRx)
                val deltaTx = maxOf(deltaUidTx, deltaProxyTx)

                _rxSpeed.value = formatSpeed(deltaRx)
                _txSpeed.value = formatSpeed(deltaTx)

                prevUidRx = currUidRx
                prevUidTx = currUidTx
                prevProxyRx = currProxyRx
                prevProxyTx = currProxyTx
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            bytesPerSec < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.1f GB/s", bytesPerSec / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(mode: RoutingMode): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WattKit 加速引擎运行中")
            .setContentText("${mode.displayName} · 启用 ${RuleManager.getEnabledCount()} 条规则")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
