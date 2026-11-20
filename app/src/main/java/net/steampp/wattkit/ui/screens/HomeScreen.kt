package net.steampp.wattkit.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.proxy.TrafficTelemetry
import net.steampp.wattkit.ui.cupertino.CupertinoColors
import net.steampp.wattkit.ui.cupertino.CupertinoPageHeader
import net.steampp.wattkit.vpn.WattVpnService

@Composable
fun HomeScreen(
    isVpnRunning: Boolean,
    onToggleVpn: () -> Unit,
    onNavigateToTab: (Int) -> Unit
) {
    val liveDownloadSpeed by WattVpnService.rxSpeed.collectAsState()
    val liveUploadSpeed by WattVpnService.txSpeed.collectAsState()
    val vpnStartTime by WattVpnService.startTime.collectAsState()
    val networkStatus by WattVpnService.networkStatus.collectAsState()

    val routingMode by RuleManager.routingMode.collectAsState()
    val dohProvider by RuleManager.dohProvider.collectAsState()
    val totalHits by TrafficTelemetry.totalHits.collectAsState()
    val activeConns by TrafficTelemetry.activeConnectionCount.collectAsState()
    val recentConnections by TrafficTelemetry.recentConnections.collectAsState()
    val enabledRuleCount = RuleManager.getEnabledCount()
    val totalRuleCount = RuleManager.allRules.size

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isVpnRunning, vpnStartTime) {
        if (isVpnRunning && vpnStartTime > 0L) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - vpnStartTime) / 1000L
                delay(1000)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    val formattedDuration = if (isVpnRunning) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        "--:--:--"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoColors.systemGroupedBackground)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            CupertinoPageHeader(
                title = "概览",
                subtitle = if (isVpnRunning) {
                    "${routingMode.displayName} · ${dohProvider.displayName}"
                } else {
                    "WattKit 游戏网络工具箱 v1.2.1"
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Master Service Card
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CupertinoColors.secondarySystemGroupedBackground,
                border = BorderStroke(0.5.dp, Color(0x1F000000)),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isVpnRunning) Color(0xFFE8F5E9) else Color(0xFFF2F2F7)
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isVpnRunning) CupertinoColors.systemGreen else CupertinoColors.systemGray
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isVpnRunning) "加速隧道 · 运行中" else "加速隧道 · 未启动",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isVpnRunning) Color(0xFF1B5E20) else CupertinoColors.secondaryLabel
                            )
                        }

                        if (isVpnRunning) {
                            Text(
                                text = networkStatus,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = CupertinoColors.systemTeal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isVpnRunning) "规则分流已接管" else "点击开启智能加速",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.label
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isVpnRunning) {
                            "活跃规则 $enabledRuleCount/$totalRuleCount · 累计命中 $totalHits 次 · 当前活跃连接 $activeConns"
                        } else {
                            "采用进程内 Fake-IP 智能分流与 TLS SNI 记录分片，普通访问零损耗直连"
                        },
                        fontSize = 13.sp,
                        color = CupertinoColors.secondaryLabel,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryStatColumn("运行时间", formattedDuration)
                        TelemetryStatColumn("规则命中", "$totalHits 次")
                        TelemetryStatColumn("下载", if (isVpnRunning) liveDownloadSpeed else "0 B/s")
                        TelemetryStatColumn("上传", if (isVpnRunning) liveUploadSpeed else "0 B/s")
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.96f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                        label = "btn_scale"
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isVpnRunning) Color(0xFFE8F5E9) else CupertinoColors.systemBlue,
                        border = if (isVpnRunning) BorderStroke(1.dp, Color(0xFFA7F3D0)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .scale(buttonScale)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onToggleVpn
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = null,
                                tint = if (isVpnRunning) Color(0xFF065F46) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isVpnRunning) "停止加速服务" else "开启智能加速",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isVpnRunning) Color(0xFF065F46) else Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Live Hit Activity Feed
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "实时加速命中动态",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
                Text(
                    text = "完整日志 >",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = CupertinoColors.systemBlue,
                    modifier = Modifier.clickable { onNavigateToTab(3) }
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CupertinoColors.secondarySystemGroupedBackground,
                border = BorderStroke(0.5.dp, Color(0x1F000000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (recentConnections.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isVpnRunning) "等待加速域名流量进入..." else "启动加速或执行链路诊断后展示真实命中记录",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val preview = recentConnections.take(4)
                        preview.forEachIndexed { index, entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val dotColor = when (entry.status) {
                                    "ACTIVE" -> CupertinoColors.systemBlue
                                    "OK" -> CupertinoColors.systemGreen
                                    else -> CupertinoColors.systemRed
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.host,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = CupertinoColors.label
                                    )
                                    Text(
                                        text = "${entry.ruleName} · ${entry.strategyBadge} -> ${entry.targetIp}",
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = CupertinoColors.secondaryLabel
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${entry.latencyMs}ms",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = CupertinoColors.secondaryLabel
                                )
                            }
                            if (index < preview.size - 1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 32.dp)
                                        .height(0.5.dp)
                                        .background(CupertinoColors.separator)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }

        // Section Title: Shortcuts
        item {
            Text(
                text = "快捷功能",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoColors.label,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "Steam 令牌",
                    subtitle = "独立运行 · 硬件加密",
                    icon = Icons.Default.Security,
                    iconBgColor = CupertinoColors.systemTeal,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(2) }
                )

                ShortcutCard(
                    title = "节点与规则",
                    subtitle = "$enabledRuleCount/$totalRuleCount 条 · ${routingMode.displayName.substringBefore(" ")}",
                    icon = Icons.Default.Bolt,
                    iconBgColor = CupertinoColors.systemGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(1) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "链路诊断雷达",
                    subtitle = "DoH + 严格 TLS 验证",
                    icon = Icons.Default.WifiTethering,
                    iconBgColor = CupertinoColors.systemIndigo,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(3) }
                )

                ShortcutCard(
                    title = "账号与密钥",
                    subtitle = "maFile 导入与校时",
                    icon = Icons.Default.People,
                    iconBgColor = CupertinoColors.systemOrange,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(2) }
                )
            }

            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}

@Composable
private fun TelemetryStatColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            maxLines = 1,
            color = CupertinoColors.secondaryLabel
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = CupertinoColors.label
        )
    }
}

@Composable
fun ShortcutCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBgColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "shortcut_press"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CupertinoColors.secondarySystemGroupedBackground,
        border = BorderStroke(0.5.dp, Color(0x1F000000)),
        shadowElevation = 1.dp,
        modifier = modifier
            .height(110.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CupertinoColors.secondaryLabel
                )
            }
        }
    }
}
