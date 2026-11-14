package net.steampp.wattkit.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.steampp.wattkit.MainActivity
import net.steampp.wattkit.R
import net.steampp.wattkit.proxy.LocalProxyServer
import java.io.File
import java.util.Locale

class WattVpnService : VpnService() {
    private val tag = "WattVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: java.lang.Process? = null
    private var proxyServer: LocalProxyServer? = null
    private var sockFile: File? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trafficJob: Job? = null

    companion object {
        const val ACTION_START = "net.steampp.wattkit.vpn.START"
        const val ACTION_STOP = "net.steampp.wattkit.vpn.STOP"
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (_isRunning.value) return
        Log.i(tag, "Starting WattKit VPN Service...")

        try {
            createNotificationChannel()
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // 1. Start local Kotlin SOCKS5 proxy
            proxyServer = LocalProxyServer(port = 28282, vpnService = this).apply {
                start()
            }

            // 2. Establish TUN interface
            val builder = Builder()
                .setSession("WattKit Acceleration")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)

            // Disallow WattKit itself from VPN loopback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.addDisallowedApplication(packageName)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(tag, "Failed to establish VPN interface")
                stopVpn()
                return
            }

            // 3. Prepare UNIX domain socket path for FD passing
            val sockDir = cacheDir
            sockFile = File(sockDir, "tun2socks.sock").apply {
                if (exists()) delete()
            }

            // 4. Launch 16KB native tun2socks process
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val tun2socksBin = File(nativeLibDir, "libtun2socks.so").absolutePath

            val cmd = listOf(
                tun2socksBin,
                "--netif-ipaddr", "10.0.0.2",
                "--netif-netmask", "255.255.255.0",
                "--socks-server-addr", "127.0.0.1:28282",
                "--tunmtu", "1500",
                "--sock-path", sockFile!!.absolutePath,
                "--loglevel", "debug"
            )

            Log.i(tag, "Spawning tun2socks: ${cmd.joinToString(" ")}")
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            tun2socksProcess = proc

            // Monitor tun2socks output
            Thread {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d("tun2socks", line)
                        }
                    }
                    val exit = proc.waitFor()
                    Log.w(tag, "tun2socks exited with code $exit")
                } catch (e: Exception) {
                    Log.e(tag, "tun2socks read error", e)
                }
            }.start()

            // 5. Send TUN FileDescriptor via LocalSocket
            val fd = vpnInterface!!.fileDescriptor
            Thread {
                var connected = false
                val targetPath = sockFile!!.absolutePath
                val localSocket = LocalSocket()
                for (i in 0 until 60) {
                    try {
                        localSocket.connect(
                            LocalSocketAddress(targetPath, LocalSocketAddress.Namespace.FILESYSTEM)
                        )
                        connected = true
                        break
                    } catch (_: Exception) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    }
                }

                if (connected) {
                    try {
                        localSocket.setFileDescriptorsForSend(arrayOf(fd))
                        localSocket.outputStream.write(42)
                        localSocket.outputStream.flush()
                        localSocket.close()
                        Log.i(tag, "TUN file descriptor passed to tun2socks successfully")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to send fd over local socket", e)
                    }
                } else {
                    Log.e(tag, "Timed out waiting for tun2socks socket: $targetPath")
                }
            }.start()

            _startTime.value = System.currentTimeMillis()
            _isRunning.value = true
            startTrafficMonitor()
            Log.i(tag, "WattKit VPN successfully started and running.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(tag, "Stopping WattKit VPN Service...")
        _isRunning.value = false
        trafficJob?.cancel()
        trafficJob = null
        _startTime.value = 0L
        _rxSpeed.value = "0.0 KB/s"
        _txSpeed.value = "0.0 KB/s"

        try {
            tun2socksProcess?.destroy()
        } catch (_: Exception) {}
        tun2socksProcess = null

        sockFile?.let {
            if (it.exists()) it.delete()
        }
        sockFile = null

        proxyServer?.stop()
        proxyServer = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
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

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WattKit 网络加速中")
            .setContentText("Steam 社区与 GitHub 规则加速正常运行")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
