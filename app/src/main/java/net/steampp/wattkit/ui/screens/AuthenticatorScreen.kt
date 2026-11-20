package net.steampp.wattkit.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.steampp.wattkit.authenticator.AccountStorage
import net.steampp.wattkit.authenticator.SteamAccount
import net.steampp.wattkit.authenticator.SteamTotp
import net.steampp.wattkit.ui.cupertino.*

@Composable
fun AuthenticatorScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storage = remember { AccountStorage.get(context) }

    // Clean up legacy hardcoded dummy account left by earlier versions without user consent
    var accounts by remember {
        val loaded = storage.getAccounts()
        val hasLegacyFake = loaded.any {
            it.id == "demo_account" && it.sharedSecret == "gU1+wY9vGf5k5a1l2x3y4z5w6e7="
        }
        if (hasLegacyFake) {
            storage.removeAccount("demo_account")
            mutableStateOf(storage.getAccounts())
        } else {
            mutableStateOf(loaded)
        }
    }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showManualAddDialog by remember { mutableStateOf(false) }
    var showMaFileDialog by remember { mutableStateOf(false) }

    var remainingSeconds by remember { mutableIntStateOf(SteamTotp.getRemainingSeconds()) }
    var progress by remember { mutableFloatStateOf(SteamTotp.getProgress()) }
    var timeSyncedFresh by remember { mutableStateOf(false) }
    var hasCalibratedCache by remember { mutableStateOf(SteamTotp.hasClockCalibration()) }
    var isSyncingTime by remember { mutableStateOf(false) }

    // Decoupled Valve clock sync: works cleanly regardless of VPN toggle state
    LaunchedEffect(Unit) {
        val synced = SteamTotp.syncServerTime()
        timeSyncedFresh = synced
        hasCalibratedCache = SteamTotp.hasClockCalibration()
    }

    // 1-second ticker for TOTP countdown & regeneration
    LaunchedEffect(Unit) {
        while (true) {
            remainingSeconds = SteamTotp.getRemainingSeconds()
            progress = SteamTotp.getProgress()
            delay(1000)
        }
    }

    val activeAccount = accounts.getOrNull(selectedIndex) ?: accounts.firstOrNull()
    val currentCode = remember(activeAccount, remainingSeconds) {
        if (activeAccount != null && activeAccount.sharedSecret.isNotBlank()) {
            SteamTotp.generateSteamGuardCode(activeAccount.sharedSecret)
        } else {
            "-----"
        }
    }

    fun copyToClipboard(code: String) {
        if (code == "-----" || code == "ERROR") return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Steam Guard Code", code)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制验证码: $code", Toast.LENGTH_SHORT).show()
    }

    fun syncTime() {
        if (isSyncingTime) return
        isSyncingTime = true
        coroutineScope.launch {
            val success = SteamTotp.syncServerTime()
            timeSyncedFresh = success
            hasCalibratedCache = SteamTotp.hasClockCalibration()
            isSyncingTime = false
            Toast.makeText(
                context,
                if (success) "已校准 Valve 官方时钟（偏差 ${SteamTotp.serverTimeOffset}s）"
                else if (hasCalibratedCache) "网络超时，沿用已持久化校准时钟"
                else "时钟校准失败，当前使用本地时间",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val clockLabel = when {
        isSyncingTime -> "校准中..."
        timeSyncedFresh -> "Valve 官方时钟"
        hasCalibratedCache -> "已校准时钟（离线）"
        else -> "本地时钟"
    }
    val clockDotColor = when {
        timeSyncedFresh -> CupertinoColors.systemGreen
        hasCalibratedCache -> CupertinoColors.systemTeal
        else -> CupertinoColors.systemOrange
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
                title = "Steam 令牌",
                subtitle = "独立运行 · Keystore 硬件加密隔离与 Valve 时钟同步"
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (activeAccount == null) {
            // Honest empty state when no real account has been imported yet
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
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFFF2F2F7))
                                    .border(0.5.dp, Color(0x1A000000), CircleShape)
                                    .clickable { syncTime() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(clockDotColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = clockLabel,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        color = CupertinoColors.secondaryLabel
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(CupertinoColors.systemTeal.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = CupertinoColors.systemTeal,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "尚未绑定 Steam 令牌账号",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.label
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "可导入 .maFile 配置文件或手动输入 shared_secret 私钥，密钥仅保存在本机 Keystore 中。",
                            fontSize = 13.sp,
                            color = CupertinoColors.secondaryLabel,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = CupertinoColors.systemBlue,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clickable { showMaFileDialog = true }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "导入 .maFile",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFE8F5E9),
                                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clickable { showManualAddDialog = true }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "手动添加密钥",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF065F46)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "加载 RFC 标准测试令牌（仅用于功能验证）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = CupertinoColors.systemBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val testAcc = SteamAccount(
                                        id = "rfc_test_vector",
                                        accountName = "RFC 测试令牌 (验证用)",
                                        sharedSecret = "CNcj+i4xMTIyMzM0NDU1Njc3ODg5OTAw"
                                    )
                                    storage.addAccount(testAcc)
                                    accounts = storage.getAccounts()
                                    selectedIndex = accounts.indexOfFirst { it.id == testAcc.id }.coerceAtLeast(0)
                                    Toast.makeText(context, "已加载测试向量令牌，可随时删除", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        } else {
            // Section 1: Pure White TOTP Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = CupertinoColors.secondarySystemGroupedBackground,
                    border = BorderStroke(0.5.dp, Color(0x1F000000)),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copyToClipboard(currentCode) }
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
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(CupertinoColors.systemBlue.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = CupertinoColors.systemBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = activeAccount.accountName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = CupertinoColors.label
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFFF2F2F7))
                                    .border(0.5.dp, Color(0x1A000000), CircleShape)
                                    .clickable { syncTime() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(clockDotColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = clockLabel,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        color = CupertinoColors.secondaryLabel
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "STEAM GUARD 动态验证码",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.2.sp,
                            color = CupertinoColors.secondaryLabel,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = currentCode,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 8.sp,
                            color = CupertinoColors.label,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        val animatedProgress by animateFloatAsState(
                            targetValue = progress,
                            label = "totp_progress"
                        )
                        val progressColor = if (remainingSeconds <= 5) CupertinoColors.systemRed else CupertinoColors.systemGreen

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "30s 动态刷新 · 时差 ${SteamTotp.serverTimeOffset}s",
                                    fontSize = 12.sp,
                                    color = CupertinoColors.secondaryLabel
                                )
                                Text(
                                    text = "${remainingSeconds} 秒后更换",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = progressColor
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE5E5EA))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction = animatedProgress.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(progressColor)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFE8F5E9),
                            border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { copyToClipboard(currentCode) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = Color(0xFF065F46),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "点击复制动态验证码",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF065F46)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Section 2: Account List & Deletion
        if (accounts.isNotEmpty()) {
            item {
                CupertinoSection(
                    title = "已保存账号 (${accounts.size})"
                ) {
                    accounts.forEachIndexed { index, acc ->
                        val isSelected = index == selectedIndex
                        CupertinoCell(
                            title = acc.accountName,
                            subtitle = "ID: ${acc.id}",
                            icon = Icons.Default.AccountCircle,
                            iconBackgroundColor = if (isSelected) CupertinoColors.systemGreen else CupertinoColors.systemGray3,
                            showDivider = index < accounts.size - 1,
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "当前选中",
                                            tint = CupertinoColors.systemGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            storage.removeAccount(acc.id)
                                            accounts = storage.getAccounts()
                                            if (selectedIndex >= accounts.size) {
                                                selectedIndex = (accounts.size - 1).coerceAtLeast(0)
                                            }
                                            Toast.makeText(context, "已删除账号 ${acc.accountName}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "删除账号",
                                            tint = CupertinoColors.systemRed.copy(alpha = 0.85f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            onClick = { selectedIndex = index }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Section 3: Actions & Setup
        item {
            CupertinoSection(
                title = "管理与导入",
                footer = "WattKit 令牌完全遵循 Steam Guard RFC-6238 规范，经 Android Keystore AES-256-GCM 加密落盘，且时钟校准独立于加速开关运行。"
            ) {
                CupertinoCell(
                    title = "导入 .maFile 令牌文件",
                    subtitle = "支持导入 Steam Desktop Authenticator 导出的 maFile",
                    icon = Icons.Default.FileOpen,
                    iconBackgroundColor = CupertinoColors.systemTeal,
                    onClick = { showMaFileDialog = true }
                )
                CupertinoCell(
                    title = "手动添加共享密钥 (Shared Secret)",
                    subtitle = "填入并校验 Base64 格式共享私钥",
                    icon = Icons.Default.Key,
                    iconBackgroundColor = CupertinoColors.systemOrange,
                    onClick = { showManualAddDialog = true }
                )
                CupertinoCell(
                    title = "立即与 Valve 官方校时",
                    subtitle = "通过 DoH 独立同步服务器时间并持久化偏差",
                    icon = Icons.Default.Sync,
                    iconBackgroundColor = CupertinoColors.systemGreen,
                    showDivider = false,
                    onClick = { syncTime() }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(120.dp))
        }
    }

    // Dialog 1: Manual Add Account
    if (showManualAddDialog) {
        var inputName by remember { mutableStateOf("") }
        var inputSecret by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showManualAddDialog = false },
            containerColor = CupertinoColors.secondarySystemGroupedBackground,
            title = {
                Text(
                    text = "添加 Steam 令牌",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "账号名称", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                    Spacer(modifier = Modifier.height(4.dp))
                    CupertinoSearchField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        placeholder = "例如: SteamPlayer"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = "Shared Secret (Base64)", fontSize = 12.sp, color = CupertinoColors.secondaryLabel)
                    Spacer(modifier = Modifier.height(4.dp))
                    CupertinoSearchField(
                        value = inputSecret,
                        onValueChange = { inputSecret = it },
                        placeholder = "输入 Base64 共享密钥"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputName.isBlank()) {
                            Toast.makeText(context, "请输入账号名称", Toast.LENGTH_SHORT).show()
                        } else if (!SteamTotp.isValidBase64Secret(inputSecret)) {
                            Toast.makeText(context, "Shared Secret 不是有效的 Base64 私钥", Toast.LENGTH_LONG).show()
                        } else {
                            val newAcc = SteamAccount(
                                id = "steam_${System.currentTimeMillis()}",
                                accountName = inputName.trim(),
                                sharedSecret = inputSecret.trim()
                            )
                            storage.addAccount(newAcc)
                            accounts = storage.getAccounts()
                            selectedIndex = accounts.indexOfFirst { it.id == newAcc.id }.coerceAtLeast(0)
                            showManualAddDialog = false
                            Toast.makeText(context, "已加密保存令牌账号", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("添加", color = CupertinoColors.systemGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualAddDialog = false }) {
                    Text("取消", color = CupertinoColors.secondaryLabel)
                }
            }
        )
    }

    // Dialog 2: Paste maFile JSON
    if (showMaFileDialog) {
        var inputJson by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showMaFileDialog = false },
            containerColor = CupertinoColors.secondarySystemGroupedBackground,
            title = {
                Text(
                    text = "导入 .maFile 令牌文本",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.label
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "请粘贴 .maFile 文件中的 JSON 文本内容:",
                        fontSize = 12.sp,
                        color = CupertinoColors.secondaryLabel
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CupertinoSearchField(
                        value = inputJson,
                        onValueChange = { inputJson = it },
                        placeholder = "{\"shared_secret\":\"...\",\"account_name\":\"...\"}"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = storage.parseMaFile(inputJson)
                        if (parsed != null && SteamTotp.isValidBase64Secret(parsed.sharedSecret)) {
                            storage.addAccount(parsed)
                            accounts = storage.getAccounts()
                            selectedIndex = accounts.indexOfFirst { it.id == parsed.id }.coerceAtLeast(0)
                            showMaFileDialog = false
                            Toast.makeText(context, "已成功导入 ${parsed.accountName}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "JSON 解析失败或 shared_secret 无效", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("导入", color = CupertinoColors.systemTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaFileDialog = false }) {
                    Text("取消", color = CupertinoColors.secondaryLabel)
                }
            }
        )
    }
}
