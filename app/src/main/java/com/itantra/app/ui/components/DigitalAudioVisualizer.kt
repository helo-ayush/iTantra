package com.itantra.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.ui.theme.MinimalColorsInstance
import com.itantra.app.ui.theme.minimalColors
import kotlin.math.sin

/**
 * Soft audio visualizer (rounded bars, calm palette).
 *
 * Displays a row of equalizer bars that dynamically dance in response to
 * live microphone or incoming playback audio levels.
 */
@Composable
fun DigitalAudioVisualizer(
    audioLevel: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    label: String = if (isActive) "Live audio" else "Standby",
    barCount: Int = 22,
    baseColor: Color = MinimalColorsInstance.accent,
    activeColor: Color = MinimalColorsInstance.accentDeep
) {
    val colors = MaterialTheme.minimalColors
    val infiniteTransition = rememberInfiniteTransition(label = "digitalVisualizer")

    // Phase animation for rhythmic wave motion
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val smoothLevel by animateFloatAsState(
        targetValue = if (isActive) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "smoothLevel"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.cardSecondaryBg)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label & Live indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isActive && smoothLevel > 0.05f) colors.mesh else colors.textTertiary)
                )
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    color = colors.textSecondary
                )
            }

            // Equalizer bars
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                modifier = Modifier.height(26.dp)
            ) {
                for (i in 0 until barCount) {
                    val waveFactor = sin(phase + (i.toFloat() * 0.45f)).coerceAtLeast(0.1f)
                    val baseHeight = 4f
                    val maxHeight = 24f
                    val dynamicHeight = if (isActive) {
                        baseHeight + (smoothLevel * (maxHeight - baseHeight) * (0.4f + 0.6f * waveFactor))
                    } else {
                        baseHeight
                    }

                    val isHigh = smoothLevel > 0.4f && waveFactor > 0.7f
                    val barTint = when {
                        !isActive -> colors.textTertiary.copy(alpha = 0.4f)
                        isHigh -> activeColor
                        else -> baseColor
                    }

                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(dynamicHeight.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(barTint)
                    )
                }
            }
        }
    }
}
