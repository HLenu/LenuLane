package net.steampp.wattkit.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.steampp.wattkit.proxy.RuleManager
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
    val enabledRuleCount = RuleManager.getEnabledCount()

    // Persistent VPN Up-time counter (HH:mm:ss) derived from service start time
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

            // iOS Standard Large Title Header (Clean, no redundant cluttered buttons)
            CupertinoPageHeader(
                title = "概览",
                subtitle = if (isVpnRunning) "网络加速隧道活跃中 · 16KB tun2socks" else "WattKit 游戏网络工具箱"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Apple Health Style Master Service Card in Light Mode
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
                    // Status Header Badge
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
                            text = if (isVpnRunning) "网络加速 · 已连接" else "网络加速 · 未启动",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVpnRunning) Color(0xFF1B5E20) else CupertinoColors.secondaryLabel
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Large Status Title
                    Text(
                        text = if (isVpnRunning) "连接已受保护" else "连接未开启",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.label
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isVpnRunning) "已启用 Steam 社区、GitHub 等 $enabledRuleCount 项加速规则" else "点击下方开关建立本地免证书透明反代隧道",
                        fontSize = 13.sp,
                        color = CupertinoColors.secondaryLabel,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Three-column Telemetry Data
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "运行时间", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formattedDuration,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CupertinoColors.label
                            )
                        }
                        Column {
                            Text(text = "下载速度", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isVpnRunning) liveDownloadSpeed else "0 B/s",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CupertinoColors.label
                            )
                        }
                        Column {
                            Text(text = "上传速度", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isVpnRunning) liveUploadSpeed else "0 B/s",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CupertinoColors.label
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // Master Operation Button
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
                                text = if (isVpnRunning) "管理加速 (点击停止)" else "开启网络加速",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isVpnRunning) Color(0xFF065F46) else Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Section Title: "快捷功能" (Shortcuts with 100% genuine utility)
        item {
            Text(
                text = "快捷功能",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoColors.label,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
        }

        // Shortcuts Grid: 4 Genuine, working capabilities
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "Steam 令牌",
                    subtitle = "动态口令与校时",
                    icon = Icons.Default.Security,
                    iconBgColor = CupertinoColors.systemTeal,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(2) } // To Tab 2 (Authenticator)
                )

                ShortcutCard(
                    title = "网络加速",
                    subtitle = "22 项生态规则",
                    icon = Icons.Default.Bolt,
                    iconBgColor = CupertinoColors.systemGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(1) } // To Tab 1 (Acceleration)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "连通性雷达",
                    subtitle = "四节点实时测速",
                    icon = Icons.Default.WifiTethering,
                    iconBgColor = CupertinoColors.systemIndigo,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(3) } // To Tab 3 (Tools & Settings)
                )

                ShortcutCard(
                    title = "账号管理",
                    subtitle = "多账号与 maFile",
                    icon = Icons.Default.People,
                    iconBgColor = CupertinoColors.systemOrange,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(2) } // To Tab 2 (Authenticator)
                )
            }

            Spacer(modifier = Modifier.height(120.dp)) // Space for floating bottom dock
        }
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
                    color = CupertinoColors.secondaryLabel
                )
            }
        }
    }
}
