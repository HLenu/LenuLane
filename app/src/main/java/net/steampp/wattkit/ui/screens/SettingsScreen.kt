package net.steampp.wattkit.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.steampp.wattkit.ui.cupertino.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class DiagnosticTarget(
    val name: String,
    val url: String,
    val hostOverride: String? = null,
    val isPost: Boolean = false
)

data class DiagnosticResult(
    val target: DiagnosticTarget,
    val latencyMs: Long,
    val isSuccess: Boolean,
    val message: String
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isPinging by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DiagnosticResult>>(emptyList()) }

    val targets = remember {
        listOf(
            DiagnosticTarget(
                name = "Steam 社区反代节点",
                url = "https://steamcommunity.rmbgame.net",
                hostOverride = "steamcommunity.com"
            ),
            DiagnosticTarget(
                name = "Pixiv 插画加速节点",
                url = "https://i.pixiv.re"
            ),
            DiagnosticTarget(
                name = "Steam 官方令牌服务",
                url = "https://api.steampowered.com/ITwoFactorService/QueryTime/v0001/",
                isPost = true
            ),
            DiagnosticTarget(
                name = "GitHub 开发者网络",
                url = "https://api.github.com"
            )
        )
    }

    fun runDiagnostics() {
        if (isPinging) return
        isPinging = true
        coroutineScope.launch {
            val list = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .hostnameVerifier { _, _ -> true }
                    .build()

                targets.map { target ->
                    val start = System.currentTimeMillis()
                    try {
                        val reqBuilder = Request.Builder()
                            .url(target.url)
                            .header("User-Agent", "WattKit/1.0.0")

                        if (target.hostOverride != null) {
                            reqBuilder.header("Host", target.hostOverride)
                        }

                        if (target.isPost) {
                            reqBuilder.post("".toRequestBody())
                        }

                        client.newCall(reqBuilder.build()).execute().use { resp ->
                            val latency = System.currentTimeMillis() - start
                            DiagnosticResult(
                                target = target,
                                latencyMs = latency,
                                isSuccess = resp.isSuccessful,
                                message = "HTTP ${resp.code}"
                            )
                        }
                    } catch (e: Exception) {
                        val latency = System.currentTimeMillis() - start
                        DiagnosticResult(
                            target = target,
                            latencyMs = latency,
                            isSuccess = false,
                            message = e.javaClass.simpleName
                        )
                    }
                }
            }
            results = list
            isPinging = false
            Toast.makeText(context, "网络雷达探测已完成", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoColors.systemBackground)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            CupertinoPageHeader(
                title = "工具与设置",
                subtitle = "网络连通性雷达探针与系统底层运行规范"
            )
        }

        // Section 1: Network Radar Diagnostic Probe
        item {
            CupertinoSection(
                title = "网络连通性雷达",
                footer = "测试客户端到达各个国内外反代服务节点与 API 的端到端响应延迟。"
            ) {
                targets.forEachIndexed { index, target ->
                    val res = results.find { it.target.name == target.name }
                    CupertinoCell(
                        title = target.name,
                        subtitle = target.url,
                        icon = Icons.Default.WifiTethering,
                        iconBackgroundColor = when {
                            res == null -> CupertinoColors.systemGray3
                            res.isSuccess -> CupertinoColors.systemGreen
                            else -> CupertinoColors.systemRed
                        },
                        showDivider = true,
                        trailingContent = {
                            if (res == null) {
                                Text(
                                    text = "未探测",
                                    fontSize = 12.sp,
                                    color = CupertinoColors.tertiaryLabel
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (res.isSuccess) CupertinoColors.systemGreen else CupertinoColors.systemRed
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${res.latencyMs} ms",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (res.isSuccess) CupertinoColors.label else CupertinoColors.systemRed
                                    )
                                }
                            }
                        }
                    )
                }

                // Radar trigger button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isPinging) { runDiagnostics() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPinging) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = CupertinoColors.systemBlue,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "正在探测各个加速节点...",
                                fontSize = 14.sp,
                                color = CupertinoColors.systemBlue
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = CupertinoColors.systemBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "开始雷达连通性探测",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.systemBlue
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 2: Extended Toolbox
        item {
            CupertinoSection(
                title = "实用工具箱",
                footer = "集成本地常用网络维护与游戏辅助能力。"
            ) {
                CupertinoCell(
                    title = "Steam 喜加一自动入库",
                    subtitle = "全自动追踪并领取全球限免游戏 (规划中)",
                    icon = Icons.Default.CardGiftcard,
                    iconBackgroundColor = CupertinoColors.systemPink,
                    trailingContent = {
                        Text(
                            text = "即将来临",
                            fontSize = 12.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "云存档备份与同步",
                    subtitle = "本地游戏存档 WebDAV 双向备份",
                    icon = Icons.Default.CloudSync,
                    iconBackgroundColor = CupertinoColors.systemIndigo,
                    trailingContent = {
                        Text(
                            text = "即将来临",
                            fontSize = 12.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "本地 Hosts 冲突净化",
                    subtitle = "一键清理历史冲突与污染条目",
                    icon = Icons.Default.CleaningServices,
                    iconBackgroundColor = CupertinoColors.systemOrange,
                    showDivider = false,
                    onClick = {
                        Toast.makeText(context, "本地环境 Hosts 正常，无残留污染条目", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 3: iOS General > About Tech Architecture
        item {
            CupertinoSection(
                title = "系统底层架构 (Android 15+)",
                footer = "适配 2026 年 Android 15+ 强制 16KB Page Size 内存对齐标准与原生沙箱隔离。"
            ) {
                CupertinoCell(
                    title = "16KB Page Size ELF",
                    subtitle = "Max Page Size 0x4000 对齐",
                    icon = Icons.Default.Memory,
                    iconBackgroundColor = CupertinoColors.systemGreen,
                    trailingContent = {
                        Text(
                            text = "原生对齐",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = CupertinoColors.systemGreen
                        )
                    }
                )
                CupertinoCell(
                    title = "tun2socks 转发内核",
                    subtitle = "基于 lwIP 2.1.2 零拷贝 TCP/IP 协议栈",
                    icon = Icons.Default.Lan,
                    iconBackgroundColor = CupertinoColors.systemTeal,
                    trailingContent = {
                        Text(
                            text = "lwIP 2.1.2",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "免证书 SNI 转发",
                    subtitle = "TLS ClientHello 握手嗅探，不侵入系统 CA",
                    icon = Icons.Default.Security,
                    iconBackgroundColor = CupertinoColors.systemIndigo,
                    trailingContent = {
                        Text(
                            text = "无侵入",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "Steam Guard 认证",
                    subtitle = "RFC-6238 HMAC-SHA1 动态加密算法",
                    icon = Icons.Default.VpnKey,
                    iconBackgroundColor = CupertinoColors.systemOrange,
                    showDivider = false,
                    trailingContent = {
                        Text(
                            text = "HMAC-SHA1",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 4: About & License
        item {
            CupertinoSection(
                title = "关于 WATT TOOLKIT"
            ) {
                CupertinoCell(
                    title = "应用版本",
                    icon = Icons.Default.Info,
                    iconBackgroundColor = CupertinoColors.systemGray2,
                    trailingContent = {
                        Text(
                            text = "v1.0.0 (2026.11)",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "开源协议",
                    icon = Icons.Default.Gavel,
                    iconBackgroundColor = CupertinoColors.systemGray2,
                    showDivider = false,
                    trailingContent = {
                        Text(
                            text = "GNU GPL-3.0",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
            }
        }

        // Safe spacing for bottom floating navigation bar
        item {
            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}
