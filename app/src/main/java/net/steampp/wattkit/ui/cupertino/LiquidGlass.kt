package net.steampp.wattkit.ui.cupertino

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass Modifier inspired by Apple Music & iOS 26 translucent refractive materials.
 * Produces crisp content atop a rich translucent backdrop with specular highlight rim.
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(24.dp),
    backgroundColor: Color = Color(0xDE141F2E), // Translucent deep slate
    borderStrokeWidth: Dp = 0.6.dp,
    showSpecularBorder: Boolean = true
): Modifier {
    val base = this
        .clip(shape)
        .background(backgroundColor)

    return if (showSpecularBorder) {
        base.border(
            BorderStroke(
                width = borderStrokeWidth,
                brush = Brush.verticalGradient(
                    listOf(
                        Color(0x4DFFFFFF),
                        Color(0x14FFFFFF),
                        Color(0x05FFFFFF)
                    )
                )
            ),
            shape = shape
        )
    } else {
        base
    }
}
