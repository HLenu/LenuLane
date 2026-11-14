package net.steampp.wattkit.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.steampp.wattkit.proxy.AccelerationRule
import net.steampp.wattkit.proxy.RuleCategory
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.ui.components.BentoCard
import net.steampp.wattkit.ui.components.PulsePowerButton
import net.steampp.wattkit.ui.components.StatusPill
import net.steampp.wattkit.ui.theme.*

@Composable
fun AccelerationScreen(
    isVpnRunning: Boolean,
    onToggleVpn: () -> Unit
) {
    val rules = remember { mutableStateListOf(*RuleManager.defaultRules.toTypedArray()) }
    var selectedCategory by remember { mutableStateOf(RuleCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }

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

    val activeCount = rules.count { it.isEnabled }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(14.dp))

            // 1. Top Brand Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "WattKit",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = SteamDeepBlue.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, SteamCyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "16KB PRO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = SteamCyan,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                StatusPill(
                    text = if (isVpnRunning) "加速运行中" else "就绪",
                    isActive = isVpnRunning
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Hero Bento Master Control Card
            Card(
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, if (isVpnRunning) SteamCyan.copy(alpha = 0.5f) else DarkOutlineVariant),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceLowest),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isVpnRunning) Brush.verticalGradient(
                                listOf(Color(0xFF0C2442), DarkSurfaceLowest)
                            ) else Brush.verticalGradient(
                                listOf(DarkSurface, DarkSurfaceLowest)
                            )
                        )
                        .padding(22.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Pulse Power Switch
                        PulsePowerButton(
                            isRunning = isVpnRunning,
                            onClick = onToggleVpn,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Dynamic Title with AnimatedContent
                        AnimatedContent(
                            targetState = isVpnRunning,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                            label = "status_text"
                        ) { running ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (running) "网络加速保护中" else "加速已就绪",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (running) SteamCyan else TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (running) "免证书 SNI 动态代理路由已激活 · 全速分流" else "一键加速 Steam、Pixiv、Epic、GitHub 等 22+ 核心服务",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Three-card Bento Telemetry Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkBackground.copy(alpha = 0.7f))
                                .padding(vertical = 12.dp, horizontal = 14.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("转发内核", fontSize = 11.sp, color = TextTertiary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("tun2socks", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SteamCyan)
                            }
                            Box(modifier = Modifier.width(1.dp).height(24.dp).background(DarkOutline))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("规则生效", fontSize = 11.sp, color = TextTertiary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("$activeCount / ${rules.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeonEmerald)
                            }
                            Box(modifier = Modifier.width(1.dp).height(24.dp).background(DarkOutline))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("透明反代", fontSize = 11.sp, color = TextTertiary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("SNI 嗅探", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EpicPurple)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Category Horizontal Filter Pills (Read You style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RuleCategory.values().forEach { cat ->
                    val isSelected = selectedCategory == cat
                    val count = if (cat == RuleCategory.ALL) rules.size else rules.count { it.category == cat }
                    val catColor = getCategoryColor(cat)

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) catColor.copy(alpha = 0.18f) else DarkSurfaceLow,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) catColor else DarkOutlineVariant
                        ),
                        modifier = Modifier
                            .clickable { selectedCategory = cat }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (cat != RuleCategory.ALL) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(catColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = "${cat.displayName} ($count)",
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) catColor else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Batch Operations & Search Bar Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "加速服务规则 (${filteredRules.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )

                Row {
                    TextButton(
                        onClick = {
                            filteredRules.forEach { rule ->
                                rule.isEnabled = true
                                val idx = rules.indexOfFirst { it.id == rule.id }
                                if (idx != -1) rules[idx] = rule.copy(isEnabled = true)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("全开", fontSize = 12.sp, color = SteamCyan)
                    }
                    TextButton(
                        onClick = {
                            filteredRules.forEach { rule ->
                                rule.isEnabled = false
                                val idx = rules.indexOfFirst { it.id == rule.id }
                                if (idx != -1) rules[idx] = rule.copy(isEnabled = false)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("全关", fontSize = 12.sp, color = TextTertiary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Modern Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索平台、服务或加速域名...", fontSize = 13.sp, color = TextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除", tint = TextTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurfaceLow,
                    unfocusedContainerColor = DarkSurfaceLow,
                    focusedBorderColor = SteamCyan,
                    unfocusedBorderColor = DarkOutlineVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 5. Rule Cards List
        items(filteredRules, key = { it.id }) { rule ->
            ModernRuleCard(
                rule = rule,
                onToggle = { checked ->
                    rule.isEnabled = checked
                    val index = rules.indexOfFirst { it.id == rule.id }
                    if (index != -1) {
                        rules[index] = rule.copy(isEnabled = checked)
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        item {
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun ModernRuleCard(
    rule: AccelerationRule,
    onToggle: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val catColor = getCategoryColor(rule.category)

    BentoCard(
        cornerRadius = 18.dp,
        containerColor = DarkSurfaceLow,
        borderColor = if (rule.isEnabled) DarkOutlineVariant else DarkOutlineVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Color Accent Pill
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(catColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (rule.isEnabled) TextPrimary else TextSecondary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = catColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = rule.category.displayName,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = catColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = rule.description,
                    fontSize = 12.sp,
                    color = TextTertiary,
                    maxLines = if (expanded) 10 else 1
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Switch(
                checked = rule.isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SteamCyan,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceHigh
                )
            )
        }

        // Expandable Detail Panel
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBackground.copy(alpha = 0.7f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "加速域名表 (${rule.domains.size} 个目标):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SteamCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = rule.domains.joinToString("\n") { "• $it" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                rule.targetHost?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "反向代理目标: $it:${rule.targetPort} (免证书)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonEmerald
                    )
                }
            }
        }
    }
}

fun getCategoryColor(cat: RuleCategory): Color {
    return when (cat) {
        RuleCategory.ALL -> SteamCyan
        RuleCategory.STEAM -> SteamCyan
        RuleCategory.PIXIV -> PixivPink
        RuleCategory.GAMING -> EpicPurple
        RuleCategory.MODS -> AmberGold
        RuleCategory.DEV -> NeonEmerald
        RuleCategory.COMMUNITY -> CyberTeal
        RuleCategory.TOOLS -> AmberGold
    }
}
