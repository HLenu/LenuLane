package net.steampp.wattkit.ui.cupertino

import androidx.compose.ui.graphics.Color

object CupertinoColors {
    // iOS Standard Light System Colors (白色底子)
    val systemBackground = Color(0xFFFFFFFF) // Pure White
    val secondarySystemBackground = Color(0xFFF2F2F7) // iOS Standard Light Gray
    val tertiarySystemBackground = Color(0xFFFFFFFF)

    val systemGroupedBackground = Color(0xFFF2F2F7) // iOS Grouped Background (#F2F2F7)
    val secondarySystemGroupedBackground = Color(0xFFFFFFFF) // Crisp White Grouped Containers
    val tertiarySystemGroupedBackground = Color(0xFFE5E5EA)

    // iOS Accent Palette
    val systemBlue = Color(0xFF007AFF) // Official iOS Blue
    val systemGreen = Color(0xFF34C759) // Official iOS Green
    val systemIndigo = Color(0xFF5856D6)
    val systemOrange = Color(0xFFFF9500)
    val systemPink = Color(0xFFFF2D55)
    val systemPurple = Color(0xFFAF52DE)
    val systemRed = Color(0xFFFF3B30)
    val systemTeal = Color(0xFF30B0C7)
    val systemYellow = Color(0xFFFFCC00)
    val systemMint = Color(0xFF00C7BE)
    val activeMint = Color(0xFF10B981)

    // iOS Grayscale
    val systemGray = Color(0xFF8E8E93)
    val systemGray2 = Color(0xFFAEAEB2)
    val systemGray3 = Color(0xFFC7C7CC)
    val systemGray4 = Color(0xFFD1D1D6)
    val systemGray5 = Color(0xFFE5E5EA)
    val systemGray6 = Color(0xFFF2F2F7)

    // Label & Content Hierarchy
    val label = Color(0xFF000000) // Deep Crisp Black
    val secondaryLabel = Color(0xFF6C6C70) // Neutral iOS Secondary Gray
    val tertiaryLabel = Color(0xFF8E8E93)
    val quaternaryLabel = Color(0xFFC7C7CC)

    // Apple 0.5dp Retinal Separator
    val separator = Color(0x293C3C43) // #E5E5EA / light divider
    val opaqueSeparator = Color(0xFFC6C6C8)

    // Tertiary Fill (Search Bar background)
    val tertiarySystemFill = Color(0x14767680)

    // Liquid Glass / Apple Music Translucency (Light Mode)
    val liquidGlassBar = Color(0xF2FFFFFF) // 95% Translucent Frosty White
    val liquidGlassBorder = Color(0x1F000000) // Subtle Soft Border
    val liquidGlassCard = Color(0xFAFFFFFF)
}
