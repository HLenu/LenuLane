package net.steampp.wattkit

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.steampp.wattkit.ui.cupertino.CupertinoColors
import net.steampp.wattkit.ui.cupertino.liquidGlass
import net.steampp.wattkit.ui.screens.AccelerationScreen
import net.steampp.wattkit.ui.screens.AuthenticatorScreen
import net.steampp.wattkit.ui.screens.HomeScreen
import net.steampp.wattkit.ui.screens.SettingsScreen
import net.steampp.wattkit.vpn.WattVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WattKitCupertinoApp()
        }
    }
}

@Composable
fun WattKitCupertinoApp() {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Main Screen View with Crossfade
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_transition",
            modifier = Modifier.fillMaxSize()
        ) { tabIndex ->
            when (tabIndex) {
                0 -> HomeScreen(
                    isVpnRunning = isVpnRunning,
                    onToggleVpn = { toggleVpn() },
                    onNavigateToTab = { tab -> selectedTab = tab }
                )
                1 -> AccelerationScreen(
                    isVpnRunning = isVpnRunning,
                    onToggleVpn = { toggleVpn() }
                )
                2 -> AuthenticatorScreen()
                3 -> SettingsScreen()
            }
        }

        // Apple Music Floating Liquid Glass Dock TabBar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(64.dp)
                .liquidGlass(
                    shape = RoundedCornerShape(32.dp),
                    backgroundColor = Color(0xEB141F2E),
                    showSpecularBorder = true
                )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CupertinoDockTabItem(
                    title = "首页",
                    selected = selectedTab == 0,
                    selectedIcon = Icons.Filled.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    onClick = { selectedTab = 0 }
                )
                CupertinoDockTabItem(
                    title = "加速",
                    selected = selectedTab == 1,
                    selectedIcon = Icons.Filled.Bolt,
                    unselectedIcon = Icons.Outlined.Bolt,
                    onClick = { selectedTab = 1 }
                )
                CupertinoDockTabItem(
                    title = "令牌",
                    selected = selectedTab == 2,
                    selectedIcon = Icons.Filled.Shield,
                    unselectedIcon = Icons.Outlined.Shield,
                    onClick = { selectedTab = 2 }
                )
                CupertinoDockTabItem(
                    title = "工具",
                    selected = selectedTab == 3,
                    selectedIcon = Icons.Filled.Widgets,
                    unselectedIcon = Icons.Outlined.Widgets,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    }
}

@Composable
private fun CupertinoDockTabItem(
    title: String,
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "tab_scale"
    )

    val activeColor = Color(0xFF34D399) // Mint green matching user's mockup
    val inactiveColor = Color(0xFF8E8E93) // CupertinoColors.systemGray

    Column(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = title,
            tint = if (selected) activeColor else inactiveColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) activeColor else inactiveColor
        )
    }
}
