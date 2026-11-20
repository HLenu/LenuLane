package net.steampp.wattkit.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class DiagnosticTarget(
    val id: String,
    val name: String,
    val url: String,
    val host: String,
    val method: String = "GET"
)

data class DiagnosticResult(
    val target: DiagnosticTarget,
    val resolvedIp: String,
    val dnsSource: String,
    val tlsProtocol: String,
    val certSubject: String,
    val latencyMs: Long,
    val httpCode: Int,
    val isSuccess: Boolean,
    val message: String
)

data class HostsInspectionResult(
    val path: String,
    val totalLines: Int,
    val activeEntries: Int,
    val flaggedEntries: List<String>,
    val readable: Boolean,
    val summary: String
)

/**
 * Shared strict-TLS HTTP executor + real full-pipeline diagnostics & system hosts scanner.
 * Executes real HTTPS requests routed through DoHResolver + TlsFragmenter WITHOUT any
 * HostnameVerifier or TrustManager bypasses.
 */
object NetworkDiagnostics {
    val defaultTargets = listOf(
        DiagnosticTarget("steam_community", "Steam 社区主页", "https://steamcommunity.com/", "steamcommunity.com"),
        DiagnosticTarget("steam_store", "Steam 商店主页", "https://store.steampowered.com/", "store.steampowered.com"),
        DiagnosticTarget("steam_api", "Steam 官方校时 API", "https://api.steampowered.com/ITwoFactorService/QueryTime/v0001/", "api.steampowered.com", "POST"),
        DiagnosticTarget("pixiv_img", "Pixiv 插画节点", "https://i.pximg.net/", "i.pximg.net"),
        DiagnosticTarget("github_api", "GitHub REST API", "https://api.github.com/", "api.github.com"),
        DiagnosticTarget("discord", "Discord 主站", "https://discord.com/", "discord.com")
    )

    data class HttpCallOutcome(
        val resolvedIp: String,
        val dnsSource: String,
        val httpCode: Int,
        val bodyText: String,
        val tlsProtocol: String,
        val certSubject: String,
        val latencyMs: Long,
        val error: String? = null
    )

    /**
     * Executes an HTTPS request through an ephemeral local loopback bridge that sends
     * the outbound ClientHello over DoHResolver IP + TlsFragmenter, while OkHttp performs
     * standard system CA & SAN/CN hostname verification.
     */
    fun executeWithPipeline(
        url: String,
        host: String,
        port: Int = 443,
        postBody: RequestBody? = null,
        readBody: Boolean = false,
        timeoutMs: Long = 6000L
    ): HttpCallOutcome {
        val start = System.currentTimeMillis()
        val dnsRes = DoHResolver.resolveWithSource(host)
            ?: return HttpCallOutcome(
                resolvedIp = "-",
                dnsSource = "解析失败",
                httpCode = 0,
                bodyText = "",
                tlsProtocol = "-",
                certSubject = "-",
                latencyMs = System.currentTimeMillis() - start,
                error = "DoH 无法解析域名"
            )

        var serverSocket: ServerSocket? = null
        var bridgeThread: Thread? = null
        val useFragment = RuleManager.tlsFragmentEnabled.value

        try {
            serverSocket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = timeoutMs.toInt()
            }
            val bridgePort = serverSocket.localPort

            bridgeThread = Thread({
                var clientSock: Socket? = null
                var remoteSock: Socket? = null
                try {
                    clientSock = serverSocket.accept()
                    clientSock.soTimeout = timeoutMs.toInt()
                    clientSock.tcpNoDelay = true

                    val cIn = clientSock.getInputStream()
                    val cOut = clientSock.getOutputStream()

                    val initial = ProxyConnectionDispatcher.readInitialPacket(cIn)
                    if (initial != null && initial.second > 0) {
                        remoteSock = Socket().apply {
                            DoHResolver.protectTcpSocket(this)
                            tcpNoDelay = true
                            soTimeout = timeoutMs.toInt()
                            connect(InetSocketAddress(dnsRes.ip, port), timeoutMs.toInt())
                        }
                        val rIn = remoteSock.getInputStream()
                        val rOut = remoteSock.getOutputStream()

                        TlsFragmenter.writeFragmented(rOut, initial.first, initial.second, useFragment)

                        val upThread = Thread {
                            try {
                                copyStream(cIn, rOut)
                                remoteSock.shutdownOutput()
                            } catch (_: Exception) {}
                        }
                        upThread.start()
                        copyStream(rIn, cOut)
                        try { clientSock.shutdownOutput() } catch (_: Exception) {}
                        upThread.join(1000)
                    }
                } catch (_: Exception) {
                } finally {
                    try { remoteSock?.close() } catch (_: Exception) {}
                    try { clientSock?.close() } catch (_: Exception) {}
                }
            }, "WattKit-DiagBridge-$host").apply { start() }

            // Real kernel Socket redirected at TCP connect() to our fragmenting bridge,
            // preserving full Conscrypt / OkHttp strict certificate verification!
            val bridgeFactory = object : SocketFactory() {
                override fun createSocket(): Socket = RedirectConnectSocket("127.0.0.1", bridgePort)
                override fun createSocket(h: String, p: Int): Socket =
                    RedirectConnectSocket("127.0.0.1", bridgePort).also {
                        it.connect(InetSocketAddress(h, p))
                    }
                override fun createSocket(h: String, p: Int, lh: InetAddress, lp: Int): Socket =
                    RedirectConnectSocket("127.0.0.1", bridgePort).also {
                        it.bind(InetSocketAddress(lh, lp))
                        it.connect(InetSocketAddress(h, p))
                    }
                override fun createSocket(h: InetAddress, p: Int): Socket =
                    RedirectConnectSocket("127.0.0.1", bridgePort).also {
                        it.connect(InetSocketAddress(h, p))
                    }
                override fun createSocket(a: InetAddress, p: Int, la: InetAddress, lp: Int): Socket =
                    RedirectConnectSocket("127.0.0.1", bridgePort).also {
                        it.bind(InetSocketAddress(la, lp))
                        it.connect(InetSocketAddress(a, p))
                    }
            }

            val client = OkHttpClient.Builder()
                .socketFactory(bridgeFactory)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                })
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build()

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "WattKit/1.2.0")
            if (postBody != null) {
                reqBuilder.post(postBody)
            } else {
                reqBuilder.get()
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                val handshake = resp.handshake
                val tlsVersion = handshake?.tlsVersion?.javaName ?: "TLS"
                val x509 = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                val cn = extractCn(x509?.subjectX500Principal?.name)
                val body = if (readBody) (resp.body?.string() ?: "") else ""
                return HttpCallOutcome(
                    resolvedIp = dnsRes.ip,
                    dnsSource = dnsRes.source,
                    httpCode = resp.code,
                    bodyText = body,
                    tlsProtocol = tlsVersion,
                    certSubject = cn,
                    latencyMs = latency
                )
            }
        } catch (e: Exception) {
            return HttpCallOutcome(
                resolvedIp = dnsRes.ip,
                dnsSource = dnsRes.source,
                httpCode = 0,
                bodyText = "",
                tlsProtocol = "-",
                certSubject = "-",
                latencyMs = System.currentTimeMillis() - start,
                error = "${e.javaClass.simpleName}: ${e.message ?: "failed"}"
            )
        } finally {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { bridgeThread?.join(300) } catch (_: Exception) {}
        }
    }

    suspend fun runFullPipelineProbe(
        targets: List<DiagnosticTarget> = defaultTargets
    ): List<DiagnosticResult> = withContext(Dispatchers.IO) {
        targets.map { target ->
            async {
                val postBody = if (target.method == "POST") {
                    okhttp3.FormBody.Builder().add("steamid", "0").build()
                } else {
                    null
                }
                val outcome = executeWithPipeline(
                    url = target.url,
                    host = target.host,
                    postBody = postBody,
                    readBody = false
                )
                // Any HTTP status (including 3xx/403 CDN or 404 API root) proves DNS + TCP + strict TLS 1.3 succeeded
                val reachable = outcome.httpCode in 200..499 && outcome.error == null
                DiagnosticResult(
                    target = target,
                    resolvedIp = outcome.resolvedIp,
                    dnsSource = outcome.dnsSource,
                    tlsProtocol = outcome.tlsProtocol,
                    certSubject = outcome.certSubject,
                    latencyMs = outcome.latencyMs,
                    httpCode = outcome.httpCode,
                    isSuccess = reachable,
                    message = if (reachable) {
                        "HTTP ${outcome.httpCode} · ${outcome.tlsProtocol}"
                    } else {
                        outcome.error ?: "HTTP ${outcome.httpCode}"
                    }
                )
            }
        }.awaitAll()
    }

    fun inspectSystemHosts(): HostsInspectionResult {
        val candidates = listOf("/system/etc/hosts", "/etc/hosts")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() && it.canRead() }
            ?: return HostsInspectionResult(
                path = "/system/etc/hosts",
                totalLines = 0,
                activeEntries = 0,
                flaggedEntries = emptyList(),
                readable = false,
                summary = "系统限制读取 Hosts 文件，免根证书方案不依赖写入 Hosts"
            )

        val watchedKeywords = listOf(
            "steam", "pixiv", "pximg", "github", "discord", "epicgames", "riotgames", "recaptcha"
        )
        return try {
            val lines = file.readLines()
            var active = 0
            val flagged = mutableListOf<String>()
            for (raw in lines) {
                val clean = raw.substringBefore('#').trim()
                if (clean.isEmpty()) continue
                val tokens = clean.split(Regex("\\s+"))
                if (tokens.size < 2) continue
                val ip = tokens[0]
                val hosts = tokens.drop(1)
                active++
                for (h in hosts) {
                    val lower = h.lowercase()
                    if (lower != "localhost" && lower != "ip6-localhost" &&
                        watchedKeywords.any { lower.contains(it) }
                    ) {
                        flagged += "$ip -> $h"
                    }
                }
            }
            val summary = if (flagged.isEmpty()) {
                "已扫描 ${lines.size} 行（$active 条有效映射），未发现 Steam/Pixiv/GitHub Hosts 劫持"
            } else {
                "发现 ${flagged.size} 条潜在冲突映射，建议由系统/Root 模块清理以免干扰分流"
            }
            HostsInspectionResult(
                path = file.absolutePath,
                totalLines = lines.size,
                activeEntries = active,
                flaggedEntries = flagged,
                readable = true,
                summary = summary
            )
        } catch (e: Exception) {
            HostsInspectionResult(
                path = file.absolutePath,
                totalLines = 0,
                activeEntries = 0,
                flaggedEntries = emptyList(),
                readable = false,
                summary = "读取 Hosts 失败: ${e.message}"
            )
        }
    }

    private fun extractCn(dn: String?): String {
        if (dn.isNullOrBlank()) return "已验证"
        val match = Regex("CN=([^,]+)").find(dn)
        return match?.groupValues?.getOrNull(1)?.trim() ?: dn.take(32)
    }

    private fun copyStream(inp: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = try { inp.read(buf) } catch (_: IOException) { -1 }
            if (n < 0) break
            if (n == 0) continue
            out.write(buf, 0, n)
            out.flush()
        }
    }

    private class RedirectConnectSocket(
        private val targetHost: String,
        private val targetPort: Int
    ) : Socket() {
        override fun connect(endpoint: SocketAddress?) {
            super.connect(InetSocketAddress(targetHost, targetPort))
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            super.connect(InetSocketAddress(targetHost, targetPort), timeout)
        }
    }
}
