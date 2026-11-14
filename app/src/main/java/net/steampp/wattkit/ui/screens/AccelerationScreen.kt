package net.steampp.wattkit.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.steampp.wattkit.proxy.AccelerationRule
import net.steampp.wattkit.proxy.RuleCategory
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.ui.cupertino.*

@Composable
fun AccelerationScreen(
    isVpnRunning: Boolean,
    onToggleVpn: () -> Unit
) {
    val rules = remember { mutableStateListOf(*RuleManager.defaultRules.toTypedArray()) }
    var selectedCategory by remember { mutableStateOf(RuleCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedRuleId by remember { mutableStateOf<String?>(null) }

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
            .background(CupertinoColors.systemBackground)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            CupertinoPageHeader(
                title = "网络加速",
                subtitle = "免证书 SNI 嗅探与 16KB tun2socks 本地反代系统"
            )
        }

        // Section 1: Master Engine Switch
        item {
            CupertinoSection(
                title = "全局核心服务",
                footer = if (isVpnRunning) "VPN 虚拟网卡已激活，匹配的流量将重定向至本地免证书代理。" else "开启后将建立系统底层 VPN 隧道，请在系统弹窗中授予权限。"
            ) {
                CupertinoCell(
                    title = "网络加速引擎",
                    subtitle = if (isVpnRunning) "加速运行中 · tun2socks 活跃" else "服务未启动 · 点击开启全局加速",
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
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 2: Search & Category Pills
        item {
            CupertinoSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索规则或域名 (如 steamcommunity, pixiv)..."
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RuleCategory.values().forEach { category ->
                    CupertinoFilterPill(
                        text = category.displayName,
                        isSelected = selectedCategory == category,
                        onClick = { selectedCategory = category }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 3: Rules List in Inset Grouped Section
        item {
            Text(
                text = "已启用规则 (${rules.count { it.isEnabled }}/${rules.size}) · 过滤结果: ${filteredRules.size}".uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = CupertinoColors.secondaryLabel,
                letterSpacing = 0.5.sp,
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

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedRuleId = if (isExpanded) null else rule.id
                                }
                        ) {
                            CupertinoCell(
                                title = rule.name,
                                subtitle = rule.description,
                                icon = icon,
                                iconBackgroundColor = iconBg,
                                showDivider = !isExpanded && index < filteredRules.size - 1,
                                trailingContent = {
                                    CupertinoSwitch(
                                        checked = rule.isEnabled,
                                        onCheckedChange = { checked ->
                                            val idx = rules.indexOfFirst { it.id == rule.id }
                                            if (idx != -1) {
                                                rules[idx] = rules[idx].copy(isEnabled = checked)
                                            }
                                        }
                                    )
                                }
                            )

                            // Expandable Details (iOS Accordion style)
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "反向代理目标",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = CupertinoColors.secondaryLabel
                                        )
                                        Text(
                                            text = rule.targetHost ?: "原生直连 SNI 伪装",
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (rule.targetHost != null) CupertinoColors.systemTeal else CupertinoColors.systemOrange
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "拦截匹配域名 (${rule.domains.size} 个):",
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

        // Safe spacing for bottom floating navigation bar
        item {
            Spacer(modifier = Modifier.height(120.dp))
        }
    }
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
