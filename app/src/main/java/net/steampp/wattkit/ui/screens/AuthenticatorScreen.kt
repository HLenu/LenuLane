package net.steampp.wattkit.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.steampp.wattkit.authenticator.AccountStorage
import net.steampp.wattkit.authenticator.SteamAccount
import net.steampp.wattkit.authenticator.SteamTotp
import net.steampp.wattkit.ui.components.BentoCard
import net.steampp.wattkit.ui.theme.*

@Composable
fun AuthenticatorScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    var accounts by remember { mutableStateOf(storage.getAccounts()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    var remainingSeconds by remember { mutableIntStateOf(SteamTotp.getRemainingSeconds()) }
    var progress by remember { mutableFloatStateOf(SteamTotp.getProgress()) }
    var timeSynced by remember { mutableStateOf(false) }

    // Auto-create demo account if list is empty
    LaunchedEffect(Unit) {
        if (accounts.isEmpty()) {
            val demo = SteamAccount(
                id = "demo_account",
                accountName = "SteamPlayer",
                sharedSecret = "gU1+wY9vGf5k5a1l2x3y4z5w6e7="
            )
            storage.addAccount(demo)
            accounts = storage.getAccounts()
        }
    }

    // Sync Steam Server Time
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            timeSynced = SteamTotp.syncServerTime()
        }
        while (true) {
            remainingSeconds = SteamTotp.getRemainingSeconds()
            progress = SteamTotp.getProgress()
            delay(500)
        }
    }

    val currentAccount = accounts.getOrNull(selectedIndex)
    val steamCode = remember(currentAccount, remainingSeconds / 30) {
        if (currentAccount != null && currentAccount.sharedSecret.isNotEmpty()) {
            SteamTotp.generateSteamGuardCode(currentAccount.sharedSecret)
        } else {
            "-----"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // 1. Account Switcher Capsule Pill
        if (accounts.isNotEmpty()) {
            Surface(
                color = DarkSurfaceLow,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, DarkOutlineVariant),
                modifier = Modifier.clickable {
                    selectedIndex = (selectedIndex + 1) % accounts.size
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = SteamCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "账号: ${currentAccount?.accountName}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Steam Guard Physical-style Card (PixelPlayer + Jetsnack Inspired)
        Card(
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(20.dp, shape = RoundedCornerShape(28.dp), spotColor = EpicPurple)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E1B4B), DarkBackground, Color(0xFF172554))
                        )
                    )
                    .padding(26.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Steam Shield Branding Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = SteamCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "STEAM GUARD 动态令牌",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = SteamCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Big 5-digit high-tech Monospace Code
                    Text(
                        text = steamCode.chunked(1).joinToString("  "),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(26.dp))

                    // Circular Countdown Timer
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(76.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 6.dp,
                            color = if (remainingSeconds <= 5) Color(0xFFEF4444) else SteamCyan,
                            trackColor = DarkOutline.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "${remainingSeconds}s",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (remainingSeconds <= 5) Color(0xFFEF4444) else TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (timeSynced) "✓ 官方服务器毫秒级对齐已生效" else "正在与 Steam 服务器对齐时钟...",
                        fontSize = 11.sp,
                        color = if (timeSynced) NeonEmerald else TextTertiary
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    // Copy Code Button
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SteamGuard", steamCode))
                            Toast.makeText(context, "已复制验证码: $steamCode", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SteamDeepBlue),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("一键复制动态验证码", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 3. Add Account Action Button
        OutlinedButton(
            onClick = { showAddDialog = true },
            border = BorderStroke(1.dp, DarkOutline),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = SteamCyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "导入 / 绑定新 Steam 账号 (.maFile)",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showAddDialog) {
        var inputSecret by remember { mutableStateOf("") }
        var inputName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("绑定 Steam 令牌", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "支持直接粘贴 Steam Desktop Authenticator (.maFile) JSON 或 Base64 shared_secret：",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("账号别名 (选填)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceLow,
                            unfocusedContainerColor = DarkSurfaceLow,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SteamCyan,
                            unfocusedBorderColor = DarkOutline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputSecret,
                        onValueChange = { inputSecret = it },
                        label = { Text("shared_secret 或 JSON 内容") },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceLow,
                            unfocusedContainerColor = DarkSurfaceLow,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SteamCyan,
                            unfocusedBorderColor = DarkOutline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputSecret.isNotBlank()) {
                            val parsed = storage.parseMaFile(inputSecret)
                            val finalAccount = parsed ?: SteamAccount(
                                id = System.currentTimeMillis().toString(),
                                accountName = inputName.ifBlank { "SteamAccount" },
                                sharedSecret = inputSecret.trim()
                            )
                            storage.addAccount(finalAccount)
                            accounts = storage.getAccounts()
                            selectedIndex = accounts.indexOfFirst { it.id == finalAccount.id }.coerceAtLeast(0)
                            showAddDialog = false
                            Toast.makeText(context, "成功导入: ${finalAccount.accountName}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SteamDeepBlue)
                ) {
                    Text("立即导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消", color = TextTertiary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
