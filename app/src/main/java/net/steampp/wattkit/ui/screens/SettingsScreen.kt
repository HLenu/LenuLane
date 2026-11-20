package net.steampp.wattkit.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import net.steampp.wattkit.proxy.*
import net.steampp.wattkit.ui.cupertino.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isPinging by remember { mutableStateOf(false) }
    var isGeneratingHosts by remember { mutableStateOf(false) }
    var generatedHostsText by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<DiagnosticResult>>(emptyList()) }
    var hostsResult by remember { mutableStateOf<HostsInspectionResult?>(null) }
    val recentConnections by TrafficTelemetry.recentConnections.collectAsState()
    val targets = remember { NetworkDiagnostics.defaultTargets }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun runDiagnostics() {
        if (isPinging) return
        isPinging = true
        coroutineScope.launch {
            val list = NetworkDiagnostics.runFullPipelineProbe(targets)
            results = list
            isPinging = false
            val okCount = list.count { it.isSuccess }
            Toast.makeText(
                context,
                "全链路探测完成: $okCount/${list.size} 个目标通过严格 TLS 校验",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun scanHosts() {
        coroutineScope.launch {
            val res = withContext(Dispatchers.IO) {
                NetworkDiagnostics.inspectSystemHosts()
            }
            hostsResult = res
            Toast.makeText(context, res.summary, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoColors.systemGroupedBackground)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            CupertinoPageHeader(
                title = "工具与诊断",
                subtitle = "真实加速链路雷达 · 严格系统 CA 验证与实时命中日志"
            )
        }

        // Section 1: Real Full-Pipeline Diagnostic Radar
        item {
            CupertinoSection(
                title = "全链路加速雷达 (DoH + TLS 分片 + 严格证书校验)",
                footer = "每项探测均经 DoH 解析真实 IP 并执行 RFC TLS 记录分片，使用 Android 系统信任链严格校验证书。"
            ) {
                targets.forEach { target ->
                    val res = results.find { it.target.id == target.id }
                    val subtitleText = if (res == null) {
                        target.url
                    } else if (res.isSuccess) {
                        "${res.dnsSource} -> ${res.resolvedIp} · ${res.tlsProtocol} (${res.certSubject})"
                    } else {
                        "${res.dnsSource} -> ${res.resolvedIp} · ${res.message}"
                    }

                    CupertinoCell(
                        title = target.name,
                        subtitle = subtitleText,
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
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (res.isSuccess) CupertinoColors.systemGreen else CupertinoColors.systemRed
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "${res.latencyMs} ms",
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (res.isSuccess) CupertinoColors.label else CupertinoColors.systemRed
                                        )
                                    }
                                    Text(
                                        text = if (res.httpCode > 0) "HTTP ${res.httpCode}" else "失败",
                                        fontSize = 10.sp,
                                        color = CupertinoColors.secondaryLabel
                                    )
                                }
                            }
                        }
                    )
                }

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
                                text = "正在并发探测全部链路节点...",
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
                                text = "执行真实全链路探测",
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

        // Section 2: Live Connection & Rule Hit Logs
        item {
            CupertinoSection(
                title = "实时连接与规则命中日志 (最近 ${recentConnections.size} 条)",
                footer = "展示通过 TUN 或本地代理建立的真实转发连接、策略与毫秒级耗时。"
            ) {
                if (recentConnections.isEmpty()) {
                    CupertinoCell(
                        title = "暂无连接记录",
                        subtitle = "开启加速并访问 Steam / Pixiv / GitHub，或执行上方链路探测后显示",
                        icon = Icons.Default.Timeline,
                        iconBackgroundColor = CupertinoColors.systemGray3,
                        showDivider = true
                    )
                } else {
                    recentConnections.take(15).forEach { entry ->
                        val timeText = timeFormatter.format(Date(entry.timestampMs))
                        val totalBytes = entry.rxBytes + entry.txBytes
                        val byteLabel = if (totalBytes >= 1024L) {
                            String.format(Locale.US, "%.1fKB", totalBytes / 1024.0)
                        } else {
                            "${totalBytes}B"
                        }
                        CupertinoCell(
                            title = entry.host,
                            subtitle = "$timeText · ${entry.ruleName} [${entry.strategyBadge}] -> ${entry.targetIp}",
                            icon = Icons.Default.SwapVert,
                            iconBackgroundColor = when (entry.status) {
                                "ACTIVE" -> CupertinoColors.systemBlue
                                "OK" -> CupertinoColors.systemGreen
                                else -> CupertinoColors.systemRed
                            },
                            showDivider = true,
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${entry.latencyMs}ms",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CupertinoColors.label
                                    )
                                    Text(
                                        text = "$byteLabel · ${entry.status}",
                                        fontSize = 10.sp,
                                        color = CupertinoColors.secondaryLabel
                                    )
                                }
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            TrafficTelemetry.reset()
                            DoHResolver.clearCache()
                            Toast.makeText(context, "已清空连接日志与 DoH 缓存", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "清空日志与 DoH DNS 缓存",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = CupertinoColors.systemRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 3: System Tools (Real /system/etc/hosts inspector)
        item {
            CupertinoSection(
                title = "实用工具箱",
                footer = hostsResult?.flaggedEntries?.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n", prefix = "冲突明细:\n")
                    ?: "真实读取设备 Hosts 文件，排查 Steam/Pixiv/GitHub 历史静态映射冲突。"
            ) {
                CupertinoCell(
                    title = "系统 Hosts 文件核查",
                    subtitle = hostsResult?.summary ?: "点击扫描 /system/etc/hosts 实际条目",
                    icon = Icons.Default.FindInPage,
                    iconBackgroundColor = if ((hostsResult?.flaggedEntries?.size ?: 0) > 0) {
                        CupertinoColors.systemOrange
                    } else {
                        CupertinoColors.systemTeal
                    },
                    showDivider = true,
                    onClick = { scanHosts() },
                    trailingContent = {
                        Text(
                            text = if (hostsResult == null) "扫描" else "重扫",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = CupertinoColors.systemBlue
                        )
                    }
                )
                CupertinoCell(
                    title = "生成加速 Hosts 映射文本",
                    subtitle = "通过防污染 DoH 批量解析已启用规则域名并导出 Hosts",
                    icon = Icons.Default.Dns,
                    iconBackgroundColor = CupertinoColors.brandPrimary,
                    showDivider = false,
                    onClick = {
                        if (!isGeneratingHosts) {
                            isGeneratingHosts = true
                            coroutineScope.launch {
                                val text = NetworkDiagnostics.generateAcceleratedHosts()
                                isGeneratingHosts = false
                                generatedHostsText = text
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("LenuLane Hosts", text))
                                Toast.makeText(context, "加速 Hosts 已生成并复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    trailingContent = {
                        if (isGeneratingHosts) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = CupertinoColors.brandPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "生成",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = CupertinoColors.brandPrimary
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 4: Architecture Specs
        item {
            CupertinoSection(
                title = "LENULANE 双路径架构 (v1.4.0)",
                footer = "纯 Kotlin 进程内包引擎，支持 RFC 7323 512KB TCP 滑窗、主动 RTO 重传及分应用分流，无原生 SO 崩溃风险。"
            ) {
                CupertinoCell(
                    title = "进程内 TUN 转发引擎",
                    subtitle = "TunPacketEngine RFC 7323 WSOPT · 主动 RTO 与僵尸回收",
                    icon = Icons.Default.Lan,
                    iconBackgroundColor = CupertinoColors.brandPrimary,
                    trailingContent = {
                        Text(
                            text = "512KB Win",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = CupertinoColors.brandPrimary
                        )
                    }
                )
                CupertinoCell(
                    title = "Fake-IP 与 DNS TTL 缓存",
                    subtitle = "10.0.0.1 虚拟 DNS + 198.18.0.0/15 + 512 项常规缓存",
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    iconBackgroundColor = CupertinoColors.brandSecondary,
                    trailingContent = {
                        Text(
                            text = "Dual-Lane",
                            fontSize = 12.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "自适应 TLS 分片与节点优选",
                    subtitle = "记录层/TCP 层自适应抗阻断与候选 IP EWMA 延迟排序",
                    icon = Icons.Default.Security,
                    iconBackgroundColor = Color(0xFF6B8AC4),
                    trailingContent = {
                        Text(
                            text = "严格 CA",
                            fontSize = 12.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "Steam Guard 隔离存储",
                    subtitle = "Keystore AES-256-GCM · otpauth:// · 交易确认 Hash",
                    icon = Icons.Default.VpnKey,
                    iconBackgroundColor = CupertinoColors.systemOrange,
                    showDivider = false,
                    trailingContent = {
                        Text(
                            text = "HMAC-SHA1",
                            fontSize = 12.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 5: About & License
        item {
            CupertinoSection(
                title = "关于 LENULANE"
            ) {
                CupertinoCell(
                    title = "产品定位",
                    icon = Icons.Default.Layers,
                    iconBackgroundColor = CupertinoColors.brandPrimary,
                    trailingContent = {
                        Text(
                            text = "轻量网络加速与工具箱",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "设计标识",
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    iconBackgroundColor = CupertinoColors.brandSecondary,
                    trailingContent = {
                        Text(
                            text = "双 L · 双路径智能分流",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                )
                CupertinoCell(
                    title = "应用版本",
                    icon = Icons.Default.Info,
                    iconBackgroundColor = CupertinoColors.systemGray2,
                    trailingContent = {
                        Text(
                            text = "v1.4.0 (2026.11)",
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

        item {
            Spacer(modifier = Modifier.height(120.dp))
        }
    }

    generatedHostsText?.let { hostsText ->
        AlertDialog(
            onDismissRequest = { generatedHostsText = null },
            containerColor = CupertinoColors.secondarySystemGroupedBackground,
            title = {
                Text(
                    text = "加速 Hosts 映射 (已复制)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = hostsText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CupertinoColors.label
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { generatedHostsText = null }) {
                    Text("关闭", color = CupertinoColors.brandPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
