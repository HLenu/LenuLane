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
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.steampp.wattkit.MainActivity
import net.steampp.wattkit.R
import net.steampp.wattkit.proxy.LocalProxyServer
import java.io.File

class WattVpnService : VpnService() {
    private val tag = "WattVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private var proxyServer: LocalProxyServer? = null
    private var sockFile: File? = null

    companion object {
        const val ACTION_START = "net.steampp.wattkit.vpn.START"
        const val ACTION_STOP = "net.steampp.wattkit.vpn.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wattkit_vpn_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
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

            _isRunning.value = true
            Log.i(tag, "WattKit VPN successfully started and running.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(tag, "Stopping WattKit VPN Service...")
        _isRunning.value = false

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
        super.onDestroy()
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
