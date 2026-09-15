package com.itantra.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.R
import com.itantra.app.localization.LocalAppStrings
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.theme.MinimalColorsInstance

enum class MissionDestination(
    val route: String,
    val title: String,
    val iconRes: Int
) {
    SOS("sos", "SOS", R.drawable.ic_soft_siren),
    WALKIE("walkie", "Walkie", R.drawable.ic_soft_radio),
    RESCUE("rescue", "Rescue", R.drawable.ic_soft_tower),
    SETTINGS("settings", "Settings", R.drawable.ic_soft_settings)
}

/**
 * 4-Tab Bottom Navigation Bar (SOS • Walkie • Rescue • Settings).
 * Soft surface, hairline divider, tinted pill selection.
 */
@Composable
fun MissionBottomNav(
    currentDestination: MissionDestination,
    onDestinationSelected: (MissionDestination) -> Unit,
    alertCount: Int,
    modifier: Modifier = Modifier
) {
    val colors = MinimalColorsInstance
    val strings = LocalAppStrings.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        HorizontalDivider(
            color = colors.outline,
            thickness = 1.dp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MissionDestination.entries.forEach { destination ->
                val isSelected = currentDestination == destination
                val title = when (destination) {
                    MissionDestination.SOS -> strings.navSos
                    MissionDestination.WALKIE -> strings.navWalkie
                    MissionDestination.RESCUE -> strings.navRescue
                    MissionDestination.SETTINGS -> strings.navSettings
                }

                val itemColor by animateColorAsState(
                    targetValue = when {
                        isSelected && destination == MissionDestination.SOS -> colors.error
                        isSelected -> colors.accent
                        else -> colors.textSecondary
                    },
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    label = "navItemColor"
                )

                val pillColor by animateColorAsState(
                    targetValue = when {
                        isSelected && destination == MissionDestination.SOS -> colors.errorContainer
                        isSelected -> colors.accentContainer
                        else -> Color.Transparent
                    },
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    label = "pillColor"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDestinationSelected(destination) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(pillColor)
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BadgedBox(
                                badge = {
                                    if (destination == MissionDestination.RESCUE && alertCount > 0) {
                                        Badge(
                                            containerColor = colors.error,
                                            contentColor = colors.onError
                                        ) {
                                            Text(
                                                text = if (alertCount > 9) "9+" else "$alertCount",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                SoftIcon(
                                    resId = destination.iconRes,
                                    contentDescription = title,
                                    tint = itemColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            letterSpacing = 0.1.sp,
                            color = itemColor
                        )
                    }
                }
            }
        }
    }
}
