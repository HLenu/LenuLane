package net.steampp.wattkit

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.steampp.wattkit.authenticator.AccountStorage
import net.steampp.wattkit.authenticator.SteamAccount
import net.steampp.wattkit.authenticator.SteamTotp
import net.steampp.wattkit.proxy.RuleManager
import net.steampp.wattkit.vpn.WattVpnService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WattKitApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WattKitApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val isVpnRunning by WattVpnService.isRunning.collectAsState()

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = Intent(context, WattVpnService::class.java).apply {
                action = WattVpnService.ACTION_START
            }
            context.startService(intent)
        } else {
            Toast.makeText(context, "VPN 权限未授予，无法启动加速", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleVpn() {
        if (isVpnRunning) {
            val intent = Intent(context, WattVpnService::class.java).apply {
                action = WattVpnService.ACTION_STOP
            }
            context.startService(intent)
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnLauncher.launch(prepareIntent)
            } else {
                val intent = Intent(context, WattVpnService::class.java).apply {
                    action = WattVpnService.ACTION_START
                }
                context.startService(intent)
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = "加速") },
                    label = { Text("网络加速") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Security, contentDescription = "令牌") },
                    label = { Text("Steam 令牌") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置/诊断") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> AccelerationScreen(isVpnRunning = isVpnRunning, onToggleVpn = { toggleVpn() })
                1 -> AuthenticatorScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun AccelerationScreen(isVpnRunning: Boolean, onToggleVpn: () -> Unit) {
    val rules = remember { mutableStateListOf(*RuleManager.defaultRules.toTypedArray()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            // Big Power Switch
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVpnRunning) Color(0xFF1E3A5F) else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val scale by animateFloatAsState(
                        targetValue = if (isVpnRunning) 1.05f else 1.0f,
                        animationSpec = tween(300),
                        label = "buttonScale"
                    )

                    IconButton(
                        onClick = onToggleVpn,
                        modifier = Modifier
                            .size(110.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(
                                if (isVpnRunning) Brush.radialGradient(
                                    listOf(Color(0xFF66C0F4), Color(0xFF1B2838))
                                ) else Brush.radialGradient(
                                    listOf(Color(0xFF9E9E9E), Color(0xFF424242))
                                )
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "加速开关",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isVpnRunning) "加速已启动" else "加速已停止",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isVpnRunning) Color(0xFF66C0F4) else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isVpnRunning) "免 CA 直连代理中 (127.0.0.1:28282)" else "点击上方开关开启 Steam 社区与 GitHub 加速",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "加速规则列表",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(rules, key = { it.id }) { rule ->
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rule.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rule.isEnabled,
                        onCheckedChange = { checked ->
                            rule.isEnabled = checked
                            val index = rules.indexOfFirst { it.id == rule.id }
                            if (index != -1) {
                                rules[index] = rule.copy(isEnabled = checked)
                            }
                        }
                    )
                }
            }
        }
    }
}

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

    // Auto-create a demo Steam account if empty
    LaunchedEffect(Unit) {
        if (accounts.isEmpty()) {
            val demo = SteamAccount(
                id = "demo_account",
                accountName = "DemoSteamAccount",
                sharedSecret = "gU1+wY9vGf5k5a1l2x3y4z5w6e7="
            )
            storage.addAccount(demo)
            accounts = storage.getAccounts()
        }
    }

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
        Spacer(modifier = Modifier.height(24.dp))

        // Account Selector Chip
        if (accounts.isNotEmpty()) {
            SuggestionChip(
                onClick = {
                    selectedIndex = (selectedIndex + 1) % accounts.size
                },
                label = { Text("账号: ${currentAccount?.accountName} (点击切换)") },
                icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // TOTP Code Display Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Steam 动态验证码",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = steamCode.chunked(1).joinToString(" "),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(8.dp)
                        .clip(CircleShape),
                    color = if (remainingSeconds <= 5) Color.Red else MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (timeSynced) "官方服务器时间已校准 · ${remainingSeconds}s 后刷新" else "${remainingSeconds} 秒后更新",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(20.dp))

                FilledTonalButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SteamGuard", steamCode))
                        Toast.makeText(context, "已复制验证码: $steamCode", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("复制验证码")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("导入 / 添加 Steam 令牌账号")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showAddDialog) {
        var inputSecret by remember { mutableStateOf("") }
        var inputName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加 Steam 令牌") },
            text = {
                Column {
                    Text(
                        text = "支持粘贴 Steam Desktop Authenticator (.maFile) JSON 或直接输入 Base64 shared_secret：",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("账号备注 (选填)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputSecret,
                        onValueChange = { inputSecret = it },
                        label = { Text("shared_secret 或 JSON 内容") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
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
                            Toast.makeText(context, "添加成功: ${finalAccount.accountName}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("确认添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun SettingsScreen() {
    val coroutineScope = rememberCoroutineScope()
    var pingResult by remember { mutableStateOf("未测试") }
    var isPinging by remember { mutableStateOf(false) }

    fun runDiagnostics() {
        isPinging = true
        pingResult = "正在测试与 Steam/GitHub 连接..."
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .hostnameVerifier { _, _ -> true }
                    .build()

                val testSites = listOf(
                    "Steam 社区加速节点" to "https://steamcommunity.rmbgame.net",
                    "Steam 官方令牌服务" to "https://api.steampowered.com/ITwoFactorService/QueryTime/v0001/",
                    "GitHub 连通性" to "https://api.github.com"
                )

                val sb = StringBuilder()
                for ((name, url) in testSites) {
                    val start = System.currentTimeMillis()
                    try {
                        val reqBuilder = Request.Builder()
                            .url(url)
                            .header("User-Agent", "WattKit/1.0")
                        if (url.contains("rmbgame")) {
                            reqBuilder.header("Host", "steamcommunity.com")
                        }
                        if (url.contains("QueryTime")) {
                            reqBuilder.post(okhttp3.RequestBody.create(null, ByteArray(0)))
                        }
                        val req = reqBuilder.build()
                        val res = client.newCall(req).execute()
                        val elapsed = System.currentTimeMillis() - start
                        sb.append("✓ $name: 正常 (状态码: ${res.code}, 耗时: ${elapsed}ms)\n")
                        res.close()
                    } catch (e: Exception) {
                        sb.append("✗ $name: 失败 (${e.message ?: "连接超时"})\n")
                    }
                }
                sb.toString().trimEnd()
            }
            pingResult = result
            isPinging = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                text = "网络连通性诊断",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "测试当前设备直连 Steam 社区反代节点与 GitHub 的延迟与可用性：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = pingResult,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { runDiagnostics() },
                        enabled = !isPinging,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isPinging) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("测试中...")
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开始网络测试")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "关于 WattKit",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "WattKit Android 原生重构版", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "版本: 1.0.0 (Native Release)", fontSize = 13.sp)
                    Text(text = "架构: 纯 Kotlin + Jetpack Compose + 16KB 原生二进制", fontSize = 13.sp)
                    Text(text = "特性: 免 CA 证书、内存占用极低 (<50MB)、安装包体积小于 15MB", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "遵循 GPL-3.0 协议。完全丢弃历史 Xamarin 堆栈，专注极速响应与稳定加速。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
