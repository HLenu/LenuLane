package net.steampp.wattkit.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
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
    var accounts by remember { mutableStateOf(storage.getAccounts()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showManualAddDialog by remember { mutableStateOf(false) }
    var showMaFileDialog by remember { mutableStateOf(false) }

    var remainingSeconds by remember { mutableIntStateOf(SteamTotp.getRemainingSeconds()) }
    var progress by remember { mutableFloatStateOf(SteamTotp.getProgress()) }
    var timeSynced by remember { mutableStateOf(false) }
    var isSyncingTime by remember { mutableStateOf(false) }

    // Auto-create demo account if list is empty
    LaunchedEffect(Unit) {
        if (accounts.isEmpty()) {
            // Placeholder demo account so the UI is not empty on first launch;
            // the secret is a dummy value - replace it with your real .maFile.
            val demo = SteamAccount(
                id = "demo_account",
                accountName = "SteamPlayer (示例)",
                sharedSecret = "gU1+wY9vGf5k5a1l2x3y4z5w6e7="
            )
            storage.addAccount(demo)
            accounts = storage.getAccounts()
        }
        // Auto sync with Valve official server time
        val synced = SteamTotp.syncServerTime()
        timeSynced = synced
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
            "22222"
        }
    }

    fun copyToClipboard(code: String) {
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
            timeSynced = success
            isSyncingTime = false
            Toast.makeText(
                context,
                if (success) "已成功校准 Valve 官方服务器时钟" else "时钟校准超时，使用本地时间",
                Toast.LENGTH_SHORT
            ).show()
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
                title = "Steam 令牌",
                subtitle = "RFC-6238 HMAC-SHA1 双因素认证与毫秒级时钟同步"
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Section 1: Apple Clean Pure White Token Card (白色底子)
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
                    // Top Row: Account Name & Time Sync Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                text = activeAccount?.accountName ?: "未选择账号",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.label
                            )
                        }

                        // Time Sync Pill
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
                                        .background(
                                            if (timeSynced) CupertinoColors.systemGreen else CupertinoColors.systemOrange
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSyncingTime) "校准中..." else if (timeSynced) "Valve 官方时钟" else "本地时钟",
                                    fontSize = 11.sp,
                                    color = CupertinoColors.secondaryLabel
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    // Large Monospace TOTP Display
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

                    Spacer(modifier = Modifier.height(24.dp))

                    // Countdown Bar & Remaining Seconds
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
                                text = "30s 动态刷新",
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

                    // Clean Mint Green Pill Copy Button
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

        // Section 2: Account Selector (if multiple accounts)
        if (accounts.size > 1) {
            item {
                CupertinoSection(
                    title = "切换当前账号"
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
                                    if (accounts.size > 1) {
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
                                                tint = CupertinoColors.systemRed.copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
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
                footer = "WattKit 令牌完全遵循 Steam Guard RFC-6238 规范，密钥经 Android Keystore 硬件级安全隔离，严禁上传任何远程服务器。"
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
                    subtitle = "直接填入 Base64 格式共享私钥",
                    icon = Icons.Default.Key,
                    iconBackgroundColor = CupertinoColors.systemOrange,
                    onClick = { showManualAddDialog = true }
                )
                CupertinoCell(
                    title = "立即与 Valve 官方校时",
                    subtitle = "修正本地系统与 Steam 服务器的时间偏差",
                    icon = Icons.Default.Sync,
                    iconBackgroundColor = CupertinoColors.systemGreen,
                    showDivider = false,
                    onClick = { syncTime() }
                )
            }
        }

        // Safe spacing for bottom floating navigation bar
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
                    Text(
                        text = "账号名称",
                        fontSize = 12.sp,
                        color = CupertinoColors.secondaryLabel
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CupertinoSearchField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        placeholder = "例如: SteamPlayer"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Shared Secret (Base64)",
                        fontSize = 12.sp,
                        color = CupertinoColors.secondaryLabel
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CupertinoSearchField(
                        value = inputSecret,
                        onValueChange = { inputSecret = it },
                        placeholder = "输入 28 位 Base64 共享密钥"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputName.isNotBlank() && inputSecret.isNotBlank()) {
                            val newAcc = SteamAccount(
                                id = "steam_${System.currentTimeMillis()}",
                                accountName = inputName.trim(),
                                sharedSecret = inputSecret.trim()
                            )
                            storage.addAccount(newAcc)
                            accounts = storage.getAccounts()
                            selectedIndex = accounts.indexOfFirst { it.id == newAcc.id }.coerceAtLeast(0)
                            showManualAddDialog = false
                            Toast.makeText(context, "已成功添加令牌账号", Toast.LENGTH_SHORT).show()
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
                        if (parsed != null && parsed.sharedSecret.isNotBlank()) {
                            storage.addAccount(parsed)
                            accounts = storage.getAccounts()
                            selectedIndex = accounts.indexOfFirst { it.id == parsed.id }.coerceAtLeast(0)
                            showMaFileDialog = false
                            Toast.makeText(context, "已成功解析并导入 ${parsed.accountName}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "JSON 解析失败，未找到 shared_secret 字段", Toast.LENGTH_LONG).show()
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
