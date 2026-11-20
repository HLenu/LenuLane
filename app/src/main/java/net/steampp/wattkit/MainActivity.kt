package net.steampp.wattkit

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
        // iOS Default Light Mode: white background with dark status bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
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
            ContextCompat.startForegroundService(context, intent)
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
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun restartVpn() {
        if (isVpnRunning) {
            val intent = Intent(context, WattVpnService::class.java).apply {
                action = WattVpnService.ACTION_RESTART
            }
            context.startService(intent)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoColors.systemGroupedBackground)
            .statusBarsPadding()
    ) {
        // Native iOS Default Parallax Slide Animation between Tabs
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(
                        animationSpec = spring(stiffness = 550f, dampingRatio = 0.85f)
                    ) { width -> width / 4 } + fadeIn(animationSpec = tween(160)))
                        .togetherWith(
                            slideOutHorizontally(
                                animationSpec = spring(stiffness = 550f, dampingRatio = 0.85f)
                            ) { width -> -width / 4 } + fadeOut(animationSpec = tween(160))
                        )
                } else {
                    (slideInHorizontally(
                        animationSpec = spring(stiffness = 550f, dampingRatio = 0.85f)
                    ) { width -> -width / 4 } + fadeIn(animationSpec = tween(160)))
                        .togetherWith(
                            slideOutHorizontally(
                                animationSpec = spring(stiffness = 550f, dampingRatio = 0.85f)
                            ) { width -> width / 4 } + fadeOut(animationSpec = tween(160))
                        )
                }
            },
            label = "ios_tab_transition",
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
                    onToggleVpn = { toggleVpn() },
                    onRestartVpn = { restartVpn() }
                )
                2 -> AuthenticatorScreen()
                3 -> SettingsScreen()
            }
        }

        // Apple Music Style Floating Liquid Glass Dock in Light Mode
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(32.dp),
                    spotColor = Color(0x33000000)
                )
                .liquidGlass(
                    shape = RoundedCornerShape(32.dp),
                    backgroundColor = CupertinoColors.liquidGlassBar,
                    showSpecularBorder = true
                )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CupertinoDockTabItem(
                    title = "概览",
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

    val activeColor = CupertinoColors.brandPrimary
    val inactiveColor = CupertinoColors.systemGray

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
