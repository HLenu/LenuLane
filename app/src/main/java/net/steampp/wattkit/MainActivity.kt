package net.steampp.wattkit

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.steampp.wattkit.ui.screens.AccelerationScreen
import net.steampp.wattkit.ui.screens.AuthenticatorScreen
import net.steampp.wattkit.ui.screens.SettingsScreen
import net.steampp.wattkit.ui.theme.*
import net.steampp.wattkit.vpn.WattVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WattKitTheme {
                WattKitApp()
            }
        }
    }
}

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
            Toast.makeText(context, "VPN 授权已取消，加速未能启动", Toast.LENGTH_SHORT).show()
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
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurfaceLowest,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = "加速") },
                    label = {
                        Text(
                            text = "网络加速",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = SteamDeepBlue.copy(alpha = 0.25f),
                        selectedIconColor = SteamCyan,
                        selectedTextColor = SteamCyan,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Security, contentDescription = "令牌") },
                    label = {
                        Text(
                            text = "Steam 令牌",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = EpicPurple.copy(alpha = 0.25f),
                        selectedIconColor = EpicPurple,
                        selectedTextColor = EpicPurple,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "诊断") },
                    label = {
                        Text(
                            text = "设置/诊断",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = NeonEmerald.copy(alpha = 0.25f),
                        selectedIconColor = NeonEmerald,
                        selectedTextColor = NeonEmerald,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary
                    )
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
