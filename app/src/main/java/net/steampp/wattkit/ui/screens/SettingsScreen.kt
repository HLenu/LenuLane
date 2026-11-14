package net.steampp.wattkit.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.steampp.wattkit.ui.components.BentoCard
import net.steampp.wattkit.ui.theme.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Composable
fun SettingsScreen() {
    val coroutineScope = rememberCoroutineScope()
    var pingResults by remember { mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList()) }
    var isPinging by remember { mutableStateOf(false) }

    fun runDiagnostics() {
        isPinging = true
        coroutineScope.launch {
            val results = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .hostnameVerifier { _, _ -> true }
                    .build()

                val testSites = listOf(
                    "Steam 社区反代节点" to "https://steamcommunity.rmbgame.net",
                    "Pixiv 插画加速节点" to "https://i.pixiv.re",
                    "Steam 官方令牌服务" to "https://api.steampowered.com/ITwoFactorService/QueryTime/v0001/",
                    "GitHub 开发者网络" to "https://api.github.com"
                )

                testSites.map { (name, url) ->
                    val start = System.currentTimeMillis()
                    try {
                        val reqBuilder = Request.Builder()
                            .url(url)
                            .header("User-Agent", "WattKit/1.0.0")
                        if (url.contains("rmbgame")) {
                            reqBuilder.header("Host", "steamcommunity.com")
                        }
                        if (url.contains("QueryTime")) {
                            reqBuilder.post(ByteArray(0).toRequestBody(null))
                        }
                        val res = client.newCall(reqBuilder.build()).execute()
                        val elapsed = System.currentTimeMillis() - start
                        res.close()
                        Triple(name, "正常 (HTTP ${res.code}, ${elapsed}ms)", true)
                    } catch (e: Exception) {
                        Triple(name, "失败 (${e.message ?: "连接超时"})", false)
                    }
                }
            }
            pingResults = results
            isPinging = false
        }
    }

    // Auto-test on first display
    LaunchedEffect(Unit) {
        if (pingResults.isEmpty()) {
            runDiagnostics()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "网络连通性雷达",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Diagnostic Radar Bento Card
            BentoCard(
                cornerRadius = 24.dp,
                containerColor = DarkSurfaceLow
            ) {
                Text(
                    text = "实时探测各平台核心节点与反向代理链路往返延迟：",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isPinging && pingResults.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = SteamCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "正在探测加速节点与时钟服务延迟...",
                            fontSize = 13.sp,
                            color = TextTertiary
                        )
                    }
                } else {
                    pingResults.forEach { (name, info, isSuccess) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (isSuccess) NeonEmerald else Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                            }

                            Surface(
                                color = if (isSuccess) NeonEmerald.copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSuccess) NeonEmerald.copy(alpha = 0.3f) else Color(0xFFEF4444).copy(alpha = 0.3f)
                                )
                            ) {
                                Text(
                                    text = info,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSuccess) NeonEmerald else Color(0xFFEF4444),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = { runDiagnostics() },
                    enabled = !isPinging,
                    colors = ButtonDefaults.buttonColors(containerColor = SteamDeepBlue),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    if (isPinging) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在探测网络链路...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("一键重新测速探针", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Tech Specs Bento Card
            Text(
                text = "技术规范与设计架构",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            BentoCard(
                cornerRadius = 24.dp,
                containerColor = DarkSurfaceLow
            ) {
                Text(
                    text = "WattKit Android (Native Engine)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "版本: 1.0.0 (Release 64KB/16KB 对齐)", fontSize = 12.sp, color = TextSecondary)
                Text(text = "架构: 纯 Kotlin + Jetpack Compose + C 原生 tun2socks", fontSize = 12.sp, color = TextSecondary)
                Text(text = "安全: 免系统 CA 证书注入、SNI 动态嗅探代理、透明双向管道", fontSize = 12.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "遵循 GPL-3.0 开源协议。彻底废除旧版庞大臃肿且在 Android 15 上崩溃的 Xamarin/Mono 历史包袱。安装包由 150MB+ 缩减至 19MB，冷启动 < 400ms，内存极轻。",
                    fontSize = 12.sp,
                    color = TextTertiary,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
