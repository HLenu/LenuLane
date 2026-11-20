package net.steampp.wattkit.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.steampp.wattkit.proxy.*
import net.steampp.wattkit.ui.cupertino.*
import java.util.Locale

@Composable
fun AccelerationScreen(
    isVpnRunning: Boolean,
    onToggleVpn: () -> Unit,
    onRestartVpn: () -> Unit
) {
    val context = LocalContext.current
    val rules = RuleManager.allRules
    val routingMode by RuleManager.routingMode.collectAsState()
    val dohProvider by RuleManager.dohProvider.collectAsState()
    val tlsFragmentEnabled by RuleManager.tlsFragmentEnabled.collectAsState()
    val upstreamProxy by RuleManager.upstreamProxy.collectAsState()
    val ruleStats by TrafficTelemetry.ruleStats.collectAsState()

    var selectedCategory by remember { mutableStateOf(RuleCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedRuleId by remember { mutableStateOf<String?>(null) }
    var showEngineSettings by remember { mutableStateOf(true) }
    var showUpstreamDialog by remember { mutableStateOf(false) }

    val filteredRules = remember(rules.toList(), selectedCategory, searchQuery) {
        rules.filter { rule ->
            val matchCategory = selectedCategory == RuleCategory.ALL || rule.category == selectedCategory
            val matchSearch = searchQuery.isBlank() ||
                rule.name.contains(searchQuery, ignoreCase = true) ||
                rule.description.contains(searchQuery, ignoreCase = true) ||
                rule.domains.any { it.contains(searchQuery, ignoreCase = true) }
            matchCategory && matchSearch
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
                title = "网络加速",
                subtitle = "进程内 Fake-IP 智能分流 · DoH 净网与 RFC TLS 分片"
            )
        }

        // Section 1: Master Switch
        item {
            CupertinoSection(
                title = "加速隧道开关",
                footer = routingMode.subtitle
            ) {
                CupertinoCell(
                    title = "WattKit 进程内加速引擎",
                    subtitle = if (isVpnRunning) {
                        "运行中 · ${routingMode.displayName}"
                    } else {
                        "未启动 · 授权 VPN 后建立本地分流隧道"
                    },
                    icon = Icons.Default.Bolt,
                    iconBackgroundColor = if (isVpnRunning) CupertinoColors.systemGreen else CupertinoColors.systemGray3,
                    showDivider = false,
                    trailingContent = {
                        CupertinoSwitch(
                            checked = isVpnRunning,
                            onCheckedChange = { onToggleVpn() }
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Section 2: Engine & Node Configuration
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEngineSettings = !showEngineSettings }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "引擎与节点策略配置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = CupertinoColors.secondaryLabel
                )
                Text(
                    text = if (showEngineSettings) "收起 ▲" else "展开 ▼",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CupertinoColors.systemBlue
                )
            }

            AnimatedVisibility(visible = showEngineSettings) {
                CupertinoSection {
                    // Routing Mode
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "VPN 路由模式",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupertinoColors.label
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoutingMode.entries.forEach { mode ->
                                CupertinoFilterPill(
                                    text = mode.displayName,
                                    isSelected = routingMode == mode,
                                    onClick = {
                                        if (routingMode != mode) {
                                            RuleManager.setRoutingMode(mode)
                                            if (isVpnRunning) {
                                                onRestartVpn()
                                                Toast.makeText(
                                                    context,
                                                    "已切换为 ${mode.displayName} 并重建隧道",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(0.5.dp)
                            .background(CupertinoColors.separator)
                    )

                    // DoH Node Selector
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "防污染 DoH 解析节点",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupertinoColors.label
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DoHProvider.entries.forEach { provider ->
                                CupertinoFilterPill(
                                    text = provider.displayName,
                                    isSelected = dohProvider == provider,
                                    onClick = {
                                        RuleManager.setDoHProvider(provider)
                                        Toast.makeText(context, "DoH 节点已切换: ${provider.displayName}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(0.5.dp)
                            .background(CupertinoColors.separator)
                    )

                    CupertinoCell(
                        title = "RFC TLS 记录分片",
                        subtitle = "将 ClientHello 按 SNI 中点拆分发送，绕过浅层 SNI 阻断",
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        iconBackgroundColor = CupertinoColors.systemIndigo,
                        showDivider = true,
                        trailingContent = {
                            CupertinoSwitch(
                                checked = tlsFragmentEnabled,
                                onCheckedChange = { RuleManager.setTlsFragmentEnabled(it) }
                            )
                        }
                    )

                    CupertinoCell(
                        title = "自定义上游代理",
                        subtitle = if (upstreamProxy.enabled) {
                            val proto = if (upstreamProxy.isSocks5) "SOCKS5" else "HTTP"
                            "已启用 $proto · ${upstreamProxy.host}:${upstreamProxy.port}"
                        } else {
                            "未启用 · 可配置本地或局域网 SOCKS5 / HTTP 级联代理"
                        },
                        icon = Icons.Default.Route,
                        iconBackgroundColor = if (upstreamProxy.enabled) CupertinoColors.systemTeal else CupertinoColors.systemGray3,
                        showDivider = false,
                        onClick = { showUpstreamDialog = true },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = CupertinoColors.tertiaryLabel
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Section 3: Search & Category Pills
        item {
            CupertinoSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索规则或域名 (如 steamcommunity, pixiv, github)..."
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RuleCategory.entries.forEach { category ->
                    CupertinoFilterPill(
                        text = category.displayName,
                        isSelected = selectedCategory == category,
                        onClick = { selectedCategory = category }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 4: Rules List Header
        item {
            Text(
                text = "服务规则 (${rules.count { it.isEnabled }}/${rules.size} 启用 · 显示 ${filteredRules.size}) · 点击条目可调策略".uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = CupertinoColors.secondaryLabel,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }

        item {
            CupertinoSection {
                if (filteredRules.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "未找到匹配的加速规则",
                            color = CupertinoColors.secondaryLabel,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    filteredRules.forEachIndexed { index, rule ->
                        val isExpanded = expandedRuleId == rule.id
                        val (icon, iconBg) = getRuleIconAndColor(rule.category)
                        val stats = ruleStats[rule.id]
                        val hitCount = (stats?.dnsHits ?: 0L).coerceAtLeast(stats?.totalConnections ?: 0L)

                        val metaSubtitle = buildString {
                            append("[${rule.strategy.badgeText}]")
                            if (hitCount > 0) {
                                append(" · 命中 $hitCount 次")
                            }
                            if (stats != null && stats.lastResolvedIp.isNotBlank()) {
                                append(" · ${stats.lastResolvedIp}")
                            }
                            append("\n")
                            append(rule.description)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedRuleId = if (isExpanded) null else rule.id
                                }
                        ) {
                            CupertinoCell(
                                title = rule.name,
                                subtitle = metaSubtitle,
                                icon = icon,
                                iconBackgroundColor = iconBg,
                                showDivider = !isExpanded && index < filteredRules.size - 1,
                                trailingContent = {
                                    CupertinoSwitch(
                                        checked = rule.isEnabled,
                                        onCheckedChange = { checked ->
                                            RuleManager.setRuleEnabled(rule.id, checked)
                                        }
                                    )
                                }
                            )

                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(animationSpec = tween(200)),
                                exit = shrinkVertically(animationSpec = tween(200))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CupertinoColors.tertiarySystemGroupedBackground)
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    // Runtime metrics bar
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "命中/连接: ${stats?.dnsHits ?: 0} / ${stats?.totalConnections ?: 0}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = CupertinoColors.secondaryLabel
                                        )
                                        Text(
                                            text = "流量: ${formatBytes((stats?.rxBytes ?: 0L) + (stats?.txBytes ?: 0L))}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = CupertinoColors.secondaryLabel
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = "转发策略 (点击切换):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = CupertinoColors.secondaryLabel
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    val availableStrategies = AccelerationStrategy.entries.filter { strat ->
                                        strat != AccelerationStrategy.REVERSE_PROXY_MIRROR || rule.mirrorHost != null
                                    }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableStrategies.forEach { strat ->
                                            CupertinoFilterPill(
                                                text = strat.badgeText,
                                                isSelected = rule.strategy == strat,
                                                onClick = {
                                                    RuleManager.setRuleStrategy(rule.id, strat)
                                                    Toast.makeText(
                                                        context,
                                                        "${rule.name}: 已切换为 ${strat.displayName}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            )
                                        }
                                    }

                                    if (rule.mirrorHost != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "可选镜像节点: ${rule.mirrorHost}:${rule.targetPort}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = CupertinoColors.systemTeal
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "匹配域名 (${rule.domains.size} 个):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = CupertinoColors.secondaryLabel
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    rule.domains.take(6).forEach { domain ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(CupertinoColors.systemMint)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = domain,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = CupertinoColors.label
                                            )
                                        }
                                    }

                                    if (rule.domains.size > 6) {
                                        Text(
                                            text = "... 以及另外 ${rule.domains.size - 6} 个相关子域名",
                                            fontSize = 11.sp,
                                            color = CupertinoColors.tertiaryLabel,
                                            modifier = Modifier.padding(start = 10.dp, top = 2.dp)
                                        )
                                    }
                                }
                            }

                            if (isExpanded && index < filteredRules.size - 1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 58.dp)
                                        .height(0.5.dp)
                                        .background(CupertinoColors.separator)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(120.dp))
        }
    }

    if (showUpstreamDialog) {
        var enabled by remember { mutableStateOf(upstreamProxy.enabled) }
        var host by remember { mutableStateOf(upstreamProxy.host) }
        var portText by remember { mutableStateOf(upstreamProxy.port.toString()) }
        var isSocks5 by remember { mutableStateOf(upstreamProxy.isSocks5) }

        AlertDialog(
            onDismissRequest = { showUpstreamDialog = false },
            containerColor = CupertinoColors.secondarySystemGroupedBackground,
            title = {
                Text(
                    text = "自定义上游代理节点",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "启用上游代理", fontSize = 14.sp, color = CupertinoColors.label)
                        CupertinoSwitch(checked = enabled, onCheckedChange = { enabled = it })
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CupertinoFilterPill(
                            text = "SOCKS5",
                            isSelected = isSocks5,
                            onClick = { isSocks5 = true }
                        )
                        CupertinoFilterPill(
                            text = "HTTP CONNECT",
                            isSelected = !isSocks5,
                            onClick = { isSocks5 = false }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "代理主机 IP / 域名", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                    Spacer(modifier = Modifier.height(4.dp))
                    CupertinoSearchField(
                        value = host,
                        onValueChange = { host = it },
                        placeholder = "127.0.0.1 或局域网 IP"
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "端口", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                    Spacer(modifier = Modifier.height(4.dp))
                    CupertinoSearchField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        placeholder = "7890"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 7890
                        RuleManager.setUpstreamProxy(
                            UpstreamProxyConfig(
                                enabled = enabled,
                                host = host.trim().ifEmpty { "127.0.0.1" },
                                port = port,
                                isSocks5 = isSocks5
                            )
                        )
                        showUpstreamDialog = false
                        Toast.makeText(context, "上游代理配置已保存", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("保存", color = CupertinoColors.systemBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpstreamDialog = false }) {
                    Text("取消", color = CupertinoColors.secondaryLabel)
                }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2fMB", bytes / (1024.0 * 1024.0))
}

private fun getRuleIconAndColor(category: RuleCategory): Pair<ImageVector, Color> {
    return when (category) {
        RuleCategory.STEAM -> Pair(Icons.Default.SportsEsports, Color(0xFF00ADEE))
        RuleCategory.PIXIV -> Pair(Icons.Default.Brush, Color(0xFFFF4081))
        RuleCategory.GAMING -> Pair(Icons.Default.Gamepad, Color(0xFFBF5AF2))
        RuleCategory.MODS -> Pair(Icons.Default.Extension, Color(0xFFFF9F0A))
        RuleCategory.DEV -> Pair(Icons.Default.Code, Color(0xFF30D158))
        RuleCategory.COMMUNITY -> Pair(Icons.Default.Forum, Color(0xFF5E5CE6))
        RuleCategory.TOOLS -> Pair(Icons.Default.Build, Color(0xFF64D2FF))
        RuleCategory.ALL -> Pair(Icons.Default.Language, Color(0xFF0A84FF))
    }
}
