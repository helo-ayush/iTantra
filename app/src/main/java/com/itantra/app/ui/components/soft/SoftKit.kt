package com.itantra.app.ui.components.soft

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.R
import com.itantra.app.ui.theme.ComfortaaBrand
import com.itantra.app.ui.theme.MinimalColorsInstance
import com.itantra.app.ui.theme.SoftCardShape
import com.itantra.app.ui.theme.SoftPillShape
import com.itantra.app.ui.theme.SoftSheetShape
import com.itantra.app.ui.theme.SoftTileShape

/**
 * Soft Minimalism component kit.
 * Calm surfaces, hairline outlines, pill geometry, tinted accents.
 * Purely presentational — no business logic lives here.
 */

// ---------------------------------------------------------------- Icon ----

/** Stroke icon from the soft set, tinted to follow the theme. */
@Composable
fun SoftIcon(
    resId: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null
) {
    Icon(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

// ---------------------------------------------------------------- Card ----

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MinimalColorsInstance.surface,
    borderColor: Color = MinimalColorsInstance.outline,
    cornerRadius: Dp = 22.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val frame = modifier
        .clip(shape)
        .background(containerColor, shape)
        .border(1.dp, borderColor, shape)
    if (onClick != null) {
        Box(frame.clickable(onClick = onClick)) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Box(frame) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

// --------------------------------------------------------------- Badge ----

@Composable
fun SoftBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    leadingIconRes: Int? = null
) {
    Row(
        modifier = modifier
            .clip(SoftPillShape)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (leadingIconRes != null) {
            SoftIcon(leadingIconRes, Modifier.size(13.dp), contentColor)
        }
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = contentColor,
            maxLines = 1
        )
    }
}

// --------------------------------------------------------------- Button ----

enum class SoftButtonVariant { Primary, Tonal, Ghost, Danger }

@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SoftButtonVariant = SoftButtonVariant.Primary,
    enabled: Boolean = true,
    leadingIconRes: Int? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    minHeight: Dp = 50.dp
) {
    val colors = MinimalColorsInstance
    val resolvedContainer = containerColor ?: when (variant) {
        SoftButtonVariant.Primary -> colors.accent
        SoftButtonVariant.Tonal -> colors.accentContainer
        SoftButtonVariant.Ghost -> Color.Transparent
        SoftButtonVariant.Danger -> colors.error
    }
    val resolvedContent = contentColor ?: when (variant) {
        SoftButtonVariant.Primary -> colors.onAccent
        SoftButtonVariant.Tonal -> colors.accentContainerText
        SoftButtonVariant.Ghost -> colors.accent
        SoftButtonVariant.Danger -> colors.onError
    }

    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(SoftPillShape)
            .background(if (enabled) resolvedContainer else resolvedContainer.copy(alpha = 0.32f))
            .then(
                if (variant == SoftButtonVariant.Ghost) {
                    Modifier.border(1.dp, if (enabled) colors.outlineStrong else colors.outline, SoftPillShape)
                } else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIconRes != null) {
            SoftIcon(leadingIconRes, Modifier.size(18.dp), resolvedContent)
            Box(Modifier.size(8.dp))
        }
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = resolvedContent,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------- Icon button ----

@Composable
fun SoftIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    containerColor: Color = MinimalColorsInstance.cardSecondaryBg,
    contentColor: Color = MinimalColorsInstance.textPrimary,
    borderColor: Color? = null,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.4f))
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape) else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SoftIcon(
            iconRes,
            Modifier.size(size * 0.42f),
            if (enabled) contentColor else contentColor.copy(alpha = 0.5f)
        )
    }
}

// -------------------------------------------------------- Section header ----

@Composable
fun SoftSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val colors = MinimalColorsInstance
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing()
    }
}

// ------------------------------------------------------------ Status dot ----

@Composable
fun SoftStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp
) {
    Box(
        modifier = modifier
            .size(dotSize)
            .clip(CircleShape)
            .background(color)
    )
}

// ---------------------------------------------------------------- Banner ----

@Composable
fun SoftBanner(
    message: String,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_soft_info,
    containerColor: Color = MinimalColorsInstance.cardSecondaryBg,
    contentColor: Color = MinimalColorsInstance.textPrimary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SoftIcon(iconRes, Modifier.size(18.dp), contentColor)
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            color = contentColor
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                modifier = Modifier
                    .clip(SoftPillShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// -------------------------------------------------------------- Hero dome ----

/**
 * Soft circular hero action — the SOS / Join / Start rescue dome.
 * Breathing halo when [pulsing]; gentle press-scale; single onClick.
 */
@Composable
fun SoftHeroDome(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    domeSize: Dp = 190.dp,
    containerColor: Color = MinimalColorsInstance.accent,
    deepColor: Color = MinimalColorsInstance.accentDeep,
    glowColor: Color = MinimalColorsInstance.accent,
    contentColor: Color = MinimalColorsInstance.onAccent,
    pulsing: Boolean = false,
    subLabel: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "domePressScale"
    )

    val breathe = if (pulsing) rememberInfiniteTransition(label = "domeBreathe") else null
    val breatheScale = breathe?.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breatheScale"
    )?.value ?: 1f
    val breatheAlpha = breathe?.animateFloat(
        initialValue = 0.30f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "breatheAlpha"
    )?.value ?: 0f

    Box(
        modifier = modifier.size(domeSize),
        contentAlignment = Alignment.Center
    ) {
        if (pulsing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .scale(breatheScale)
                    .clip(CircleShape)
                    .background(glowColor.copy(alpha = breatheAlpha))
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .scale(breatheScale * 0.88f)
                    .clip(CircleShape)
                    .background(glowColor.copy(alpha = breatheAlpha * 0.65f))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .shadow(20.dp, CircleShape, spotColor = glowColor.copy(alpha = 0.42f), ambientColor = glowColor.copy(alpha = 0.20f))
                .clip(CircleShape)
                .background(Brush.radialGradient(colors = listOf(containerColor, deepColor)))
                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(20.dp)
            ) {
                SoftIcon(iconRes, Modifier.size(36.dp), contentColor)
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
                if (subLabel != null) {
                    Text(
                        text = subLabel,
                        fontSize = 12.sp,
                        color = contentColor.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ Switch row ----

@Composable
fun SoftSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconRes: Int? = null,
    enabled: Boolean = true
) {
    val colors = MinimalColorsInstance
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (iconRes != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(SoftTileShape)
                    .background(colors.cardSecondaryBg),
                contentAlignment = Alignment.Center
            ) {
                SoftIcon(iconRes, Modifier.size(17.dp), colors.textSecondary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

// ----------------------------------------------------------- Sheet shell ----

/** Shared mission telemetry header: brand logo + wordmark left, status capsule right. */
@Composable
fun SoftMissionHeader(
    mode: String,
    statusText: String,
    statusActive: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = MinimalColorsInstance.mesh,
    logoRes: Int? = null
) {
    val colors = MinimalColorsInstance
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand & mode pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (logoRes != null) {
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = "iTantra logo",
                    modifier = Modifier.size(20.dp)
                )
            } else {
                SoftStatusDot(color = colors.accent, dotSize = 7.dp)
            }
            Text(
                text = "iTantra",
                fontFamily = ComfortaaBrand,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = colors.textPrimary
            )
            Box(
                Modifier
                    .size(width = 1.dp, height = 12.dp)
                    .background(colors.outline)
            )
            Text(
                text = mode,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary
            )
        }

        // Status capsule
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (statusActive) activeColor.copy(alpha = 0.14f) else colors.surface)
                .border(
                    1.dp,
                    if (statusActive) activeColor.copy(alpha = 0.35f) else colors.outline,
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SoftStatusDot(color = if (statusActive) activeColor else colors.textTertiary, dotSize = 7.dp)
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (statusActive) activeColor else colors.textSecondary
            )
        }
    }
}

/** Shared language-picker / selector bottom sheet shell. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SoftSheetShell(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MinimalColorsInstance
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SoftSheetShape,
        containerColor = colors.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(SoftPillShape)
                    .background(colors.outlineStrong)
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Box(Modifier.size(14.dp))
            content()
        }
    }
}
