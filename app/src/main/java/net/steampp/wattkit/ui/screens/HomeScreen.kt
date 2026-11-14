package net.steampp.wattkit.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.ui.cupertino.CupertinoColors
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
            .background(CupertinoColors.systemBackground)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. iOS Top Navigation Bar (Menu + Title + Actions)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { onNavigateToTab(3) },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(CupertinoColors.secondarySystemBackground)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "菜单",
                        tint = CupertinoColors.label,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "WATT TOOLKIT",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.secondaryLabel,
                    letterSpacing = 1.2.sp
                )

                Row {
                    IconButton(
                        onClick = { onNavigateToTab(1) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CupertinoColors.secondarySystemBackground)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑规则",
                            tint = CupertinoColors.label,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { onNavigateToTab(3) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CupertinoColors.secondarySystemBackground)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = CupertinoColors.label,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. iOS Large Title: "概览"
            Text(
                text = "概览",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoColors.label,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            // 3. Apple Health Style Hero Service Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF162130),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    // Status Header Line
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isVpnRunning) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isVpnRunning) Color(0xFF34D399) else CupertinoColors.secondaryLabel,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isVpnRunning) "网络加速 · 已连接" else "网络加速 · 未开启",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVpnRunning) Color(0xFF34D399) else CupertinoColors.secondaryLabel
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Big Bold Status
                    Text(
                        text = if (isVpnRunning) "连接已受保护" else "连接未受保护",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.label
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isVpnRunning) "已启用 Steam 社区、GitHub 等 $enabledRuleCount 项加速规则" else "点击下方开关开启免证书透明加速分流",
                        fontSize = 13.sp,
                        color = CupertinoColors.secondaryLabel
                    )

                    Spacer(modifier = Modifier.height(22.dp))

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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.label
                            )
                        }
                        Column {
                            Text(text = "下载速度", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isVpnRunning) liveDownloadSpeed else "0.0 KB/s",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.label
                            )
                        }
                        Column {
                            Text(text = "上传速度", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isVpnRunning) liveUploadSpeed else "0.0 KB/s",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.label
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Light Mint Green Master Pill Button (as in uploaded screenshot)
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.96f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                        label = "btn_scale"
                    )

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isVpnRunning) Color(0xFFD1FADF) else Color(0xFFE2E8F0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
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
                                tint = if (isVpnRunning) Color(0xFF065F46) else Color(0xFF334155),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isVpnRunning) "管理加速 (点击停止)" else "管理加速 (点击启动)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isVpnRunning) Color(0xFF065F46) else Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 4. Apple Shortcuts 2x2 Grid: "快捷功能"
            Text(
                text = "快捷功能",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoColors.label,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "本地令牌",
                    subtitle = "Steam Guard",
                    icon = Icons.Outlined.Shield,
                    iconColor = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(2) } // To Tab 2 (Authenticator)
                )

                ShortcutCard(
                    title = "账号管理",
                    subtitle = "多账号切换",
                    icon = Icons.Outlined.People,
                    iconColor = Color(0xFF818CF8),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(2) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "加速服务",
                    subtitle = "22 项规则配置",
                    icon = Icons.Outlined.SportsEsports,
                    iconColor = Color(0xFFF472B6),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(1) } // To Tab 1 (Accelerate)
                )

                ShortcutCard(
                    title = "工具箱",
                    subtitle = "网络诊断与系统",
                    icon = Icons.Outlined.Build,
                    iconColor = Color(0xFF34D399),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToTab(3) } // To Tab 3 (Tools/Settings)
                )
            }

            Spacer(modifier = Modifier.height(100.dp)) // Space for floating Liquid Glass TabBar
        }
    }
}

@Composable
fun ShortcutCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
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
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF162130),
        modifier = modifier
            .height(118.dp)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )

            Column {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = CupertinoColors.secondaryLabel
                )
            }
        }
    }
}
