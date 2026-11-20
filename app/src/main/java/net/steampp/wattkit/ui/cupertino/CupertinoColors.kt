package net.steampp.wattkit.ui.cupertino

import androidx.compose.ui.graphics.Color

object CupertinoColors {
    // LenuLane Mist Blue Brand Palette
    val brandPrimary = Color(0xFF53699A)      // 主色：雾蓝 (Accel Lane)
    val brandSecondary = Color(0xFF8AA8DC)    // 辅色：浅蓝 (Direct Lane)
    val brandContainer = Color(0xFFDFE6F5)    // 浅雾蓝容器背景
    val brandOnContainer = Color(0xFF394B73)  // 深雾蓝容器前景字色
    val cardBorder = Color(0x2453699A)        // 卡片冷雾蓝描边

    // LenuLane Light System Surfaces (#E9EDF7 Cool Mist Background)
    val systemBackground = Color(0xFFFFFFFF) // Pure White
    val secondarySystemBackground = Color(0xFFE9EDF7)
    val tertiarySystemBackground = Color(0xFFFFFFFF)

    val systemGroupedBackground = Color(0xFFE9EDF7) // Brand Background (#E9EDF7)
    val secondarySystemGroupedBackground = Color(0xFFFFFFFF) // Crisp White Grouped Containers
    val tertiarySystemGroupedBackground = Color(0xFFDDE4F2) // Expanded Drawer Tint

    // Unified Accent & Semantic Palette
    val systemBlue = brandPrimary
    val systemGreen = Color(0xFF2E9E6B)
    val systemIndigo = Color(0xFF53699A)
    val systemOrange = Color(0xFFD98A3C)
    val systemPink = Color(0xFFC9688A)
    val systemPurple = Color(0xFF7B73BA)
    val systemRed = Color(0xFFD9534F)
    val systemTeal = Color(0xFF6B8AC4)
    val systemYellow = Color(0xFFD4A73A)
    val systemMint = brandSecondary
    val activeMint = Color(0xFF2E9E6B)

    // Cool Slate Grayscale
    val systemGray = Color(0xFF7D889E)
    val systemGray2 = Color(0xFF98A3B8)
    val systemGray3 = Color(0xFFB5C0D4)
    val systemGray4 = Color(0xFFC9D2E3)
    val systemGray5 = Color(0xFFDDE4F2)
    val systemGray6 = Color(0xFFE9EDF7)

    // Label & Content Hierarchy
    val label = Color(0xFF1A2336)           // Deep Cool Ink
    val secondaryLabel = Color(0xFF55627A)  // Cool Mist Slate
    val tertiaryLabel = Color(0xFF7D889E)
    val quaternaryLabel = Color(0xFFB5C0D4)

    // Subtle Mist Separator
    val separator = Color(0x2653699A)
    val opaqueSeparator = Color(0xFFCFD8EA)

    // Tertiary Fill (Search Bar & Unselected Pill background)
    val tertiarySystemFill = Color(0x1653699A)

    // Liquid Glass Dock (Light Cool Mist Translucency)
    val liquidGlassBar = Color(0xF2F7F9FD)
    val liquidGlassBorder = Color(0x2653699A)
    val liquidGlassCard = Color(0xFAFFFFFF)
}
