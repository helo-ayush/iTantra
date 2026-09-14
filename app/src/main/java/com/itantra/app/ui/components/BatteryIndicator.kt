package com.itantra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.ui.theme.MinimalColorsInstance

/**
 * Soft vector battery indicator: rounded micro-shell with proportional fill.
 *  - >50%: sage green   ·  20-50%: apricot   ·  <20%: coral (critical)
 */
@Composable
fun BatteryIndicator(
    batteryPercent: Int,
    modifier: Modifier = Modifier,
    showText: Boolean = true,
    heightDp: Dp = 12.dp,
    textColor: Color = MinimalColorsInstance.textSecondary
) {
    val colors = MinimalColorsInstance
    val level = batteryPercent.coerceIn(0, 100)
    val batteryColor = when {
        level > 50 -> colors.mesh
        level >= 20 -> colors.rescue
        else -> colors.error
    }
    val shellColor = colors.textTertiary

    val shellWidth = heightDp * 1.8f
    val shellHeight = heightDp
    val capWidth = shellWidth * 0.12f
    val capHeight = shellHeight * 0.44f

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(modifier = Modifier.size(width = shellWidth + capWidth + 2.dp, height = shellHeight)) {
            val strokeW = 1.2.dp.toPx()
            val radius = 2.5.dp.toPx()

            // Outer battery body outline
            drawRoundRect(
                color = shellColor,
                topLeft = Offset(0f, 0f),
                size = Size(shellWidth.toPx(), shellHeight.toPx()),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = strokeW)
            )

            // Positive terminal cap on right side
            val capX = shellWidth.toPx() + 1.dp.toPx()
            val capY = (shellHeight.toPx() - capHeight.toPx()) / 2f
            drawRoundRect(
                color = shellColor,
                topLeft = Offset(capX, capY),
                size = Size(capWidth.toPx(), capHeight.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )

            // Inner fill bar
            val pad = strokeW + 1.2.dp.toPx()
            val maxFillW = (shellWidth.toPx() - pad * 2f).coerceAtLeast(0f)
            val fillW = maxFillW * (level / 100f)
            val fillH = (shellHeight.toPx() - pad * 2f).coerceAtLeast(0f)

            if (fillW > 0f) {
                drawRoundRect(
                    color = batteryColor,
                    topLeft = Offset(pad, pad),
                    size = Size(fillW, fillH),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                )
            }
        }

        if (showText) {
            Text(
                text = "$level%",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                modifier = Modifier.padding(0.dp)
            )
        }
    }
}
