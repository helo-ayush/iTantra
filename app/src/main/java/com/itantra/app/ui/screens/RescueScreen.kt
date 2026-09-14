package com.itantra.app.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itantra.app.R
import com.itantra.app.model.DistressVictim
import com.itantra.app.model.RescueConnectionMode
import com.itantra.app.model.SupportedLanguage
import com.itantra.app.model.VoiceStatus
import com.itantra.app.ui.components.BatteryIndicator
import com.itantra.app.ui.components.DigitalAudioVisualizer
import com.itantra.app.ui.components.soft.SoftBadge
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.components.soft.SoftMissionHeader
import com.itantra.app.ui.components.soft.SoftSheetShell
import com.itantra.app.ui.components.soft.SoftStatusDot
import com.itantra.app.ui.theme.SoftFieldShape
import com.itantra.app.ui.theme.minimalColors
import com.itantra.app.viewmodel.MissionControlViewModel
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rescue console, Soft Minimalism edition:
 * 1. Standby: soft apricot "Start rescue" hero dome with radar rings
 * 2. Active: connection console (mode badge, 1-way broadcast trigger, call controls)
 * 3. Live transcription card
 * 4. Tactical rescue map (compact + fullscreen, pinch-zoom/pan)
 * 5. Distress beacons queue with connect / switch / end actions
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RescueScreen(
    viewModel: MissionControlViewModel,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.minimalColors
    val isRescueActive by viewModel.isRescueActive.collectAsState()
    val connectionMode by viewModel.rescueConnectionMode.collectAsState()
    val isBroadcastingToAll by viewModel.isBroadcastingToAll.collectAsState()
    val compassHeading by viewModel.compassHeading.collectAsState()
    val activeVictims by viewModel.activeDistressVictims.collectAsState()
    val connectedVictim by viewModel.connectedVictimIntercom.collectAsState()
    val selectedVictim by viewModel.selectedVictim.collectAsState()
    val isMapExpanded by viewModel.isMapExpanded.collectAsState()
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val isSpeakerphoneOn by viewModel.isSpeakerphoneOn.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val modelWarning by viewModel.modelWarningMessage.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val selectedLanguage = uiState.selectedLanguage
    val modelPacks by viewModel.modelPacks.collectAsState()
    val messageLogs by viewModel.messageLogs.collectAsState()
    val currentTranscript = uiState.currentTranscript
    val voiceStatus = uiState.voiceStatus
    val isVadSpeaking by viewModel.isVadSpeaking.collectAsState()
    val isPttActive by viewModel.isPttActive.collectAsState()
    val isModelInstalled = modelPacks.firstOrNull { it.iso == selectedLanguage.code || it.languageTag.startsWith(selectedLanguage.code) }?.isInstalled == true

    var showLanguageSheet by remember { mutableStateOf(false) }
    var languageSearchQuery by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    // Standby Button Press State Physics
    val bootButtonSource = remember { MutableInteractionSource() }
    val isBootPressed by bootButtonSource.collectIsPressedAsState()
    val bootButtonScale by animateFloatAsState(
        targetValue = if (isBootPressed) 0.96f else 1.0f,
        animationSpec = tween(120),
        label = "bootButtonScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =========================================================================
        // TOP TELEMETRY PILLS (Consistent with SOS & Walkie pages)
        // =========================================================================
        SoftMissionHeader(
            mode = "Rescue radar",
            statusText = when {
                !isRescueActive -> "Standby"
                connectionMode == RescueConnectionMode.BROADCAST_ALL -> "Broadcast active"
                connectionMode == RescueConnectionMode.ONE_TO_ONE -> "1-to-1 call"
                else -> "${activeVictims.size} in range"
            },
            statusActive = isRescueActive,
            activeColor = when {
                connectionMode == RescueConnectionMode.BROADCAST_ALL -> colors.rescue
                connectionMode == RescueConnectionMode.ONE_TO_ONE -> colors.error
                else -> colors.mesh
            }
        )

        // =========================================================================
        // RESCUE OPERATING LANGUAGE SELECTOR & 1-TAP DIALECT BAR
        // =========================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Top Row: Selected Language summary & Switch trigger
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showLanguageSheet = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.accentContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = selectedLanguage.nativeInitial,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Rescue dialect: ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = "${selectedLanguage.englishName} (${selectedLanguage.code.uppercase()})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                            }
                            val isInstalled = modelPacks.firstOrNull { it.iso == selectedLanguage.code || it.languageTag.startsWith(selectedLanguage.code) }?.isInstalled == true
                            Text(
                                text = if (isInstalled) "Neural pack ready" else "Pack not installed · ${selectedLanguage.downloadSizeMb} MB",
                                fontSize = 11.sp,
                                fontWeight = if (isInstalled) FontWeight.Medium else FontWeight.Normal,
                                color = if (isInstalled) colors.meshContainerText else colors.rescueContainerText
                            )
                        }
                    }

                    Surface(
                        onClick = { showLanguageSheet = true },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.cardSecondaryBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Switch",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            SoftIcon(
                                resId = R.drawable.ic_soft_chevron_down,
                                contentDescription = "Switch Language",
                                tint = colors.accent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.6f), thickness = 1.dp)

                // 1-Tap Dialect Chips: Hindi, English, Bengali, Marathi, etc.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val quickLangs = listOf(
                        SupportedLanguage.HINDI,
                        SupportedLanguage.ENGLISH,
                        SupportedLanguage.BENGALI,
                        SupportedLanguage.MARATHI
                    )

                    quickLangs.forEach { lang ->
                        val isLangActive = selectedLanguage == lang
                        val isInstalled = modelPacks.firstOrNull { it.iso == lang.code || it.languageTag.startsWith(lang.code) }?.isInstalled == true
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isLangActive) colors.accent else colors.cardSecondaryBg)
                                .border(
                                    width = 1.dp,
                                    color = if (isLangActive) colors.accent else colors.outline,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.setSelectedLanguage(lang) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = lang.englishName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isLangActive) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isLangActive) colors.onAccent else colors.textPrimary
                                )
                                if (isInstalled) {
                                    Text(
                                        text = "Ready",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isLangActive) colors.onAccent.copy(alpha = 0.8f) else colors.meshContainerText
                                    )
                                }
                            }
                        }
                    }

                    // "+More" Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.cardSecondaryBg)
                            .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
                            .clickable { showLanguageSheet = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+6",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        // =========================================================================
        // VIEW SWITCHER: STANDBY (PRE-BOOT) vs ACTIVE RESCUE CONSOLE
        // =========================================================================
        if (!isRescueActive) {
            // =====================================================================
            // STANDBY MODE: SOFT TACTICAL BOOT-UP DOME
            // =====================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer subtle radar rings
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(CircleShape)
                        .border(1.dp, colors.outline, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(CircleShape)
                        .border(1.dp, colors.outline.copy(alpha = 0.5f), CircleShape)
                )

                // 4 Cardinal Micro-Ticks
                Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(width = 2.dp, height = 6.dp).background(colors.outlineStrong, RoundedCornerShape(1.dp)))
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).size(width = 2.dp, height = 6.dp).background(colors.outlineStrong, RoundedCornerShape(1.dp)))
                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp).size(width = 6.dp, height = 2.dp).background(colors.outlineStrong, RoundedCornerShape(1.dp)))
                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(width = 6.dp, height = 2.dp).background(colors.outlineStrong, RoundedCornerShape(1.dp)))

                // Highlighted Hero Tactile Button (Major Action to Boot Rescue System)
                Surface(
                    onClick = { viewModel.bootRescueSystem(true) },
                    shape = CircleShape,
                    color = Color.Transparent,
                    interactionSource = bootButtonSource,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(178.dp)
                        .scale(bootButtonScale)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        colors.rescue,
                                        colors.rescueDeep
                                    )
                                )
                            )
                            .border(
                                width = 2.5.dp,
                                brush = Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.10f))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_radar,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Search & rescue",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.0.sp,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "Rescue",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.5).sp,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.20f))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_tower,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Start",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.4.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Reassuring Info Banner (No jargon)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(colors.rescueContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_bell,
                            contentDescription = null,
                            tint = colors.rescueContainerText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Automatic distress beacon detection",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Starting begins offline radar scanning for nearby SOS phones. Yours vibrates when a beacon is found.",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            // Quick Capabilities (Clean, 2x2 grid, zero technical jargon)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("100% offline minimap", "Compass oriented").forEach { feature ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.cardSecondaryBg)
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = feature,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("1-way all broadcast", "1-to-1 voice link").forEach { feature ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.cardSecondaryBg)
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = feature,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        } else {
            // =====================================================================
            // ACTIVE RESCUE CONSOLE (Booted Up)
            // =====================================================================

            // 1. TACTICAL CONNECTION & BROADCAST CONTROL CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 4.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Header with Active Status & Shutdown button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SoftBadge(
                            text = when (connectionMode) {
                                RescueConnectionMode.BROADCAST_ALL -> "Broadcast to all"
                                RescueConnectionMode.ONE_TO_ONE -> "1-to-1 voice link"
                                else -> "Scanning vicinity"
                            },
                            containerColor = when (connectionMode) {
                                RescueConnectionMode.BROADCAST_ALL -> colors.rescueContainer
                                RescueConnectionMode.ONE_TO_ONE -> colors.sosContainer
                                else -> colors.accentContainer
                            },
                            contentColor = when (connectionMode) {
                                RescueConnectionMode.BROADCAST_ALL -> colors.rescueContainerText
                                RescueConnectionMode.ONE_TO_ONE -> colors.sosContainerText
                                else -> colors.accentContainerText
                            },
                            leadingIconRes = when (connectionMode) {
                                RescueConnectionMode.BROADCAST_ALL -> R.drawable.ic_soft_megaphone
                                else -> null
                            }
                        )

                        // Highlighted "Leave Rescue" Button (High visibility, prominent)
                        Surface(
                            onClick = { viewModel.bootRescueSystem(false) },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.sosContainer,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.error.copy(alpha = 0.35f)),
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_power,
                                    contentDescription = "Leave Rescue",
                                    tint = colors.sosContainerText,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Leave rescue",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.sosContainerText
                                )
                            }
                        }
                    }

                    // 1-Way Emergency Broadcast Trigger Button
                    Surface(
                        onClick = { viewModel.toggleBroadcastToAll() },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isBroadcastingToAll) colors.rescueContainer else colors.rescueContainer.copy(alpha = 0.45f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isBroadcastingToAll) colors.rescue else colors.rescue.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (isBroadcastingToAll) colors.rescue else colors.rescue.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_megaphone,
                                        contentDescription = "Broadcast",
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.padding(end = 8.dp)) {
                                    Text(
                                        text = if (isBroadcastingToAll) "Broadcasting to all" else "Broadcast to all (1-way)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.rescueContainerText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isBroadcastingToAll) "Live, to ${activeVictims.size} phones…" else "Stream an announcement to every SOS phone",
                                        fontSize = 11.sp,
                                        color = colors.rescueContainerText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isBroadcastingToAll) colors.rescue else colors.surface)
                                    .border(1.dp, if (isBroadcastingToAll) Color.Transparent else colors.rescue.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isBroadcastingToAll) "Stop" else "Broadcast",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isBroadcastingToAll) colors.onAccent else colors.rescueContainerText,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // =======================================================
                    // RESCUE CALL CONSOLE CARD (Similar to Walkie-Talkie Mode)
                    // (Features Big Mic, Big Speaker, and Big Disconnect Button)
                    // =======================================================
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (connectedVictim != null) colors.error.copy(alpha = 0.08f)
                                else if (isBroadcastingToAll) colors.rescueContainer.copy(alpha = 0.45f)
                                else colors.cardSecondaryBg
                            )
                            .border(
                                width = 1.dp,
                                color = if (connectedVictim != null) colors.error.copy(alpha = 0.4f)
                                else if (isBroadcastingToAll) colors.rescue.copy(alpha = 0.45f)
                                else colors.outline,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .padding(16.dp)
                    ) {
                        val activeVictimLink = connectedVictim
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Room / Call Info Header (Zero distress quote jargon)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = when {
                                            activeVictimLink != null -> "Connected: ${activeVictimLink.callsign}"
                                            isBroadcastingToAll -> "Transmitting to everyone"
                                            else -> "Rescue audio console"
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            activeVictimLink != null -> colors.sosContainerText
                                            isBroadcastingToAll -> colors.rescueContainerText
                                            else -> colors.textPrimary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            activeVictimLink != null -> "~${activeVictimLink.distanceMeters} m away · Direct 2-way voice"
                                            isBroadcastingToAll -> "1-way rescuer announcement channel"
                                            else -> "Hands-free voice · Select a person to link"
                                        },
                                        fontSize = 11.sp,
                                        color = when {
                                            activeVictimLink != null -> colors.sosContainerText
                                            isBroadcastingToAll -> colors.rescueContainerText
                                            else -> colors.textSecondary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                SoftBadge(
                                    text = when {
                                        connectedVictim != null -> "Live call"
                                        isBroadcastingToAll -> "On air"
                                        else -> "Ready"
                                    },
                                    containerColor = when {
                                        connectedVictim != null -> colors.error
                                        isBroadcastingToAll -> colors.rescue
                                        else -> colors.badgeMintContainer
                                    },
                                    contentColor = when {
                                        connectedVictim != null -> colors.onAccent
                                        isBroadcastingToAll -> colors.onAccent
                                        else -> colors.badgeMintText
                                    }
                                )
                            }

                            // Divider
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(
                                        if (connectedVictim != null) colors.error.copy(alpha = 0.25f)
                                        else if (isBroadcastingToAll) colors.rescue.copy(alpha = 0.25f)
                                        else colors.outline
                                    )
                            )

                            // Model Download Warning Banner (if model pack is missing)
                            if (modelWarning != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.rescueContainer)
                                        .border(1.dp, colors.rescue.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            SoftIcon(
                                                resId = R.drawable.ic_soft_warning,
                                                contentDescription = null,
                                                tint = colors.rescueContainerText,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = modelWarning ?: "",
                                                fontSize = 12.sp,
                                                color = colors.rescueContainerText,
                                                lineHeight = 16.sp
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.dismissModelWarning() },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            SoftIcon(
                                                resId = R.drawable.ic_soft_close,
                                                contentDescription = "Dismiss",
                                                tint = colors.rescueContainerText,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Soft Audio Visualizer
                            DigitalAudioVisualizer(
                                audioLevel = audioLevel,
                                isActive = connectedVictim != null || isBroadcastingToAll,
                                label = when {
                                    connectedVictim != null -> "Rescuer 2-way intercom"
                                    isBroadcastingToAll -> "Rescue broadcast on air"
                                    else -> "Audio standby"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Big Hands-Free Audio Controls Row (Big Mic, Big Speaker, Big Disconnect)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Big Mic Mute / Unmute Button (58dp)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Surface(
                                        onClick = { viewModel.toggleMicMute() },
                                        shape = CircleShape,
                                        color = if (isMicMuted) colors.sosContainer else colors.surface,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isMicMuted) colors.error.copy(alpha = 0.35f) else colors.outline
                                        ),
                                        shadowElevation = 0.dp,
                                        modifier = Modifier.size(58.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            SoftIcon(
                                                resId = if (isMicMuted) R.drawable.ic_soft_mic_off else R.drawable.ic_soft_mic,
                                                contentDescription = "Mute Mic",
                                                tint = if (isMicMuted) colors.sosContainerText else colors.textPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isMicMuted) "Unmute" else "Mute mic",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isMicMuted) colors.sosContainerText else colors.textSecondary
                                    )
                                }

                                // 2. Big Speakerphone Toggle Button (58dp)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Surface(
                                        onClick = { viewModel.toggleSpeakerphone() },
                                        shape = CircleShape,
                                        color = if (isSpeakerphoneOn) colors.accentContainer else colors.surface,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSpeakerphoneOn) colors.accent.copy(alpha = 0.35f) else colors.outline
                                        ),
                                        shadowElevation = 0.dp,
                                        modifier = Modifier.size(58.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            SoftIcon(
                                                resId = if (isSpeakerphoneOn) R.drawable.ic_soft_volume else R.drawable.ic_soft_hearing,
                                                contentDescription = "Speaker",
                                                tint = if (isSpeakerphoneOn) colors.accent else colors.textPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isSpeakerphoneOn) "Speaker" else "Earpiece",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSpeakerphoneOn) colors.accent else colors.textSecondary
                                    )
                                }

                                // 3. Big Disconnect Button (58dp)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Surface(
                                        onClick = {
                                            when {
                                                connectedVictim != null -> viewModel.disconnectVictimIntercom()
                                                isBroadcastingToAll -> viewModel.toggleBroadcastToAll()
                                                else -> viewModel.bootRescueSystem(false)
                                            }
                                        },
                                        shape = CircleShape,
                                        color = colors.error,
                                        shadowElevation = 2.dp,
                                        modifier = Modifier.size(58.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            SoftIcon(
                                                resId = R.drawable.ic_soft_call_end,
                                                contentDescription = "Disconnect",
                                                tint = colors.onAccent,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = when {
                                            connectedVictim != null -> "End call"
                                            isBroadcastingToAll -> "Stop"
                                            else -> "Standby"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.sosContainerText
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =====================================================
            // LIVE TRANSCRIPTION CARD (Rescuer side)
            // =====================================================
            if (connectedVictim != null || isBroadcastingToAll || isRescueActive || messageLogs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Live transcription",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                // Model status indicator badge
                                SoftBadge(
                                    text = if (isModelInstalled) "STT active" else "Pack required",
                                    containerColor = if (isModelInstalled) colors.badgeMintContainer else colors.rescueContainer,
                                    contentColor = if (isModelInstalled) colors.badgeMintText else colors.rescueContainerText
                                )
                            }

                            if (messageLogs.isNotEmpty()) {
                                SoftBadge(
                                    text = "${messageLogs.size} msgs",
                                    containerColor = colors.badgeMintContainer,
                                    contentColor = colors.badgeMintText
                                )
                            }
                        }

                        // Warning banner if neural model pack is missing
                        if (modelWarning != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.rescueContainer)
                                    .border(1.dp, colors.rescue.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_warning,
                                        contentDescription = null,
                                        tint = colors.rescueContainerText,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Text(
                                        text = modelWarning ?: "",
                                        fontSize = 12.sp,
                                        color = colors.rescueContainerText,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        // Active speaking recording pulse banner
                        if (isVadSpeaking || isPttActive) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.rescue.copy(alpha = 0.12f))
                                    .border(0.5.dp, colors.rescue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SoftStatusDot(color = colors.rescue, dotSize = 8.dp)
                                    Text(
                                        text = "Recording… release or pause to send",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.rescueContainerText
                                    )
                                }
                            }
                        }

                        // Live transcript / processing status banner
                        if (currentTranscript.isNotBlank()) {
                            val isListening = voiceStatus == VoiceStatus.LISTENING || voiceStatus == VoiceStatus.LISTENING_PTT
                            val isTranscribing = voiceStatus == VoiceStatus.TRANSCRIBING
                            val isWarning = voiceStatus == VoiceStatus.UNCLEAR

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        when {
                                            isListening -> colors.rescue.copy(alpha = 0.12f)
                                            isTranscribing -> colors.meshContainer.copy(alpha = 0.55f)
                                            isWarning -> colors.rescueContainer
                                            else -> colors.accentContainer.copy(alpha = 0.6f)
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        when {
                                            isListening -> colors.rescue.copy(alpha = 0.45f)
                                            isTranscribing -> colors.mesh.copy(alpha = 0.4f)
                                            isWarning -> colors.rescue.copy(alpha = 0.5f)
                                            else -> colors.accent.copy(alpha = 0.3f)
                                        },
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (isListening || isTranscribing || isWarning) currentTranscript
                                        else "\"$currentTranscript\"",
                                        fontSize = 13.sp,
                                        fontWeight = if (isListening || isTranscribing) FontWeight.SemiBold else FontWeight.Medium,
                                        color = when {
                                            isListening -> colors.rescueContainerText
                                            isTranscribing -> colors.meshContainerText
                                            isWarning -> colors.rescueContainerText
                                            else -> colors.accentContainerText
                                        },
                                        lineHeight = 18.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!isListening && !isTranscribing && !isWarning) {
                                        SoftBadge(
                                            text = "Sent",
                                            containerColor = colors.badgeMintContainer,
                                            contentColor = colors.badgeMintText
                                        )
                                    }
                                }
                            }
                        } else if (messageLogs.isEmpty()) {
                            // Standby waiting state
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.cardSecondaryBg)
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Rescue intercom channel ready",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = if (isModelInstalled)
                                            "Hands-free voice is active. Speech is transcribed and broadcast to victims over the mesh for instant playback."
                                        else
                                            "The neural STT pack isn't downloaded yet. Voice-to-text needs the offline language model.",
                                        fontSize = 11.sp,
                                        color = colors.textSecondary,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        // Message log (last 5 messages)
                        messageLogs.take(5).forEach { msg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.cardSecondaryBg)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Sent/Received indicator
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (msg.isLocal) colors.accentContainer
                                            else colors.rescueContainer
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (msg.isLocal) "↑" else "↓",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (msg.isLocal) colors.accentContainerText else colors.rescueContainerText
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = msg.text,
                                        fontSize = 12.sp,
                                        color = colors.textPrimary,
                                        lineHeight = 16.sp,
                                        maxLines = 3
                                    )
                                    Text(
                                        text = if (msg.isLocal) "You · ${msg.senderCallsign}" else "Person · ${msg.senderCallsign}",
                                        fontSize = 10.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // =====================================================================
            // 2. SOFT TACTICAL MAP CARD
            // =====================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                    .clickable { viewModel.toggleMapExpanded(true) } // Clicking ANYWHERE on the map expands it!
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Map Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftStatusDot(color = colors.accent, dotSize = 8.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Tactical rescue map",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                            }
                            Text(
                                text = "Live compass orientation · Tap anywhere to expand",
                                fontSize = 11.sp,
                                color = colors.textSecondary
                            )
                        }

                        // Expand Map Badge / Trigger
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.cardSecondaryBg)
                                .clickable { viewModel.toggleMapExpanded(true) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_expand,
                                    contentDescription = "Expand",
                                    tint = colors.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Expand",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.accent,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Soft Vector Map Canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.background)
                            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                            .clickable { viewModel.toggleMapExpanded(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        MinimalBrightMapCanvas(
                            compassHeading = compassHeading,
                            victims = activeVictims,
                            selectedVictim = selectedVictim,
                            connectedVictim = connectedVictim,
                            onVictimSelected = {
                                viewModel.selectVictim(it)
                                viewModel.toggleMapExpanded(true)
                            },
                            onMapTapped = { viewModel.toggleMapExpanded(true) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Map Readout Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Heading: ${compassHeading.toInt()}° · Everyone visible",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                        Text(
                            text = "Tap map to zoom & explore",
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            // =====================================================================
            // 3. DISCOVERED SOS VICTIMS QUEUE (Streamlined Modern Cards)
            // =====================================================================
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "People in distress (${activeVictims.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    SoftBadge(
                        text = "Vibrating on alert",
                        containerColor = colors.sosContainer,
                        contentColor = colors.sosContainerText
                    )
                }

                activeVictims.forEach { victim ->
                    val isThisVictimConnected = connectedVictim?.id == victim.id
                    val isThisVictimSelected = selectedVictim?.id == victim.id
                    val someoneElseConnected = connectedVictim != null && !isThisVictimConnected

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(elevation = 3.dp, shape = RoundedCornerShape(18.dp), spotColor = colors.shadowTint)
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .border(
                                width = if (isThisVictimConnected) 2.dp else if (isThisVictimSelected) 1.5.dp else 1.dp,
                                color = if (isThisVictimConnected) colors.error else if (isThisVictimSelected) colors.accent else colors.outline,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { viewModel.selectVictim(victim) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(colors.sosContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_warning,
                                        contentDescription = null,
                                        tint = colors.sosContainerText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = victim.callsign,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "~${victim.distanceMeters} m away" +
                                                (victim.identityLabel?.let { " · $it" } ?: ""),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.accent
                                        )
                                        Text(
                                            text = "·",
                                            fontSize = 11.sp,
                                            color = colors.textSecondary
                                        )
                                        BatteryIndicator(
                                            batteryPercent = victim.batteryPercent,
                                            heightDp = 10.dp
                                        )
                                    }
                                }
                            }

                            // Action Button: Connect vs Switch vs End
                            if (isThisVictimConnected) {
                                Button(
                                    onClick = { viewModel.disconnectVictimIntercom() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.error,
                                        contentColor = colors.onAccent
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_phone_off,
                                        contentDescription = null,
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "End", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else if (someoneElseConnected) {
                                Button(
                                    onClick = { viewModel.switchVictimIntercom(victim) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.rescueContainer,
                                        contentColor = colors.rescueContainerText
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.rescue.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_swap,
                                        contentDescription = null,
                                        tint = colors.rescueContainerText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Switch", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.connectVictimIntercom(victim) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.accent,
                                        contentColor = colors.onAccent
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_phone,
                                        contentDescription = null,
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Connect", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // =============================================================================
    // FULLSCREEN INTERACTIVE SOFT TACTICAL MAP (Pinch-to-zoom, Pan, Compass)
    // =============================================================================
    if (isMapExpanded) {
        Dialog(
            onDismissRequest = { viewModel.toggleMapExpanded(false) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            var zoomScale by remember { mutableFloatStateOf(1.2f) }
            var panOffset by remember { mutableStateOf(Offset.Zero) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                // 1. Solid Top App Bar covering the status bar and header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.surface,
                    shadowElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SoftStatusDot(color = colors.accent, dotSize = 8.dp)
                                    Text(
                                        text = "Tactical rescue map",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary
                                    )
                                }
                                Text(
                                    text = "Heading: ${compassHeading.toInt()}° · Pinch to zoom, drag to pan",
                                    fontSize = 11.sp,
                                    color = colors.textSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // Close (X) Button (Clean, high-contrast)
                            Surface(
                                onClick = { viewModel.toggleMapExpanded(false) },
                                shape = CircleShape,
                                color = colors.cardSecondaryBg,
                                modifier = Modifier
                                    .size(40.dp)
                                    .border(1.dp, colors.outline, CircleShape)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_close,
                                        contentDescription = "Close Map",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Map Viewport Box (Strictly fills the space between top bar and bottom bar)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    // Soft Vector Map Canvas
                    MinimalBrightMapCanvas(
                        compassHeading = compassHeading,
                        victims = activeVictims,
                        selectedVictim = selectedVictim,
                        connectedVictim = connectedVictim,
                        onVictimSelected = { viewModel.selectVictim(it) },
                        zoomScale = zoomScale,
                        panOffset = panOffset,
                        showCardLabels = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    zoomScale = (zoomScale * zoom).coerceIn(0.6f, 4.0f)
                                    panOffset += pan
                                }
                            }
                    )

                    // Floating Zoom & Recenter Controls (Right Side)
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Zoom In (+)
                        Surface(
                            onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(4.0f) },
                            shape = CircleShape,
                            color = colors.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .border(1.dp, colors.outline, CircleShape)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_add,
                                    contentDescription = "Zoom In",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Zoom Out (-)
                        Surface(
                            onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(0.6f) },
                            shape = CircleShape,
                            color = colors.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .border(1.dp, colors.outline, CircleShape)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_minus,
                                    contentDescription = "Zoom Out",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Recenter
                        Surface(
                            onClick = {
                                zoomScale = 1.2f
                                panOffset = Offset.Zero
                            },
                            shape = CircleShape,
                            color = colors.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .border(1.dp, colors.outline, CircleShape)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_locate,
                                    contentDescription = "Recenter",
                                    tint = colors.accent,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }

                    // Bottom Floating Selected Victim Card
                    val victimToInspect = selectedVictim ?: activeVictims.firstOrNull()
                    if (victimToInspect != null) {
                        val isConnected = connectedVictim?.id == victimToInspect.id
                        val someoneElseConnected = connectedVictim != null && !isConnected

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .align(Alignment.BottomCenter)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp), spotColor = colors.shadowTint)
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.outline, RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(colors.sosContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_warning,
                                            contentDescription = null,
                                            tint = colors.sosContainerText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            text = victimToInspect.callsign,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textPrimary
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "~${victimToInspect.distanceMeters} m away" +
                                                    (victimToInspect.identityLabel?.let { " · $it" } ?: ""),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.accent
                                            )
                                            Text(
                                                text = "·",
                                                fontSize = 11.sp,
                                                color = colors.textSecondary
                                            )
                                            BatteryIndicator(
                                                batteryPercent = victimToInspect.batteryPercent,
                                                heightDp = 10.dp
                                            )
                                        }
                                    }
                                }

                                if (isConnected) {
                                    Button(
                                        onClick = { viewModel.disconnectVictimIntercom() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.error,
                                            contentColor = colors.onAccent
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_phone_off,
                                            contentDescription = null,
                                            tint = colors.onAccent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("End", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                } else if (someoneElseConnected) {
                                    Button(
                                        onClick = { viewModel.switchVictimIntercom(victimToInspect) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.rescueContainer,
                                            contentColor = colors.rescueContainerText
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.rescue.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_swap,
                                            contentDescription = null,
                                            tint = colors.rescueContainerText,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Switch", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.connectVictimIntercom(victimToInspect) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.accent,
                                            contentColor = colors.onAccent
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_phone,
                                            contentDescription = null,
                                            tint = colors.onAccent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Solid Bottom Scrim covering the system navigation buttons area
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.surface,
                    shadowElevation = 4.dp
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet for All 10 Supported Indian Dialects
    if (showLanguageSheet) {
        SoftSheetShell(
            title = "Rescue language",
            subtitle = "Pick the language for voice and transcription",
            onDismiss = { showLanguageSheet = false }
        ) {
            OutlinedTextField(
                value = languageSearchQuery,
                onValueChange = { languageSearchQuery = it },
                placeholder = { Text("Search language or dialect…", fontSize = 13.sp, color = colors.textTertiary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = SoftFieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.outline,
                    focusedContainerColor = colors.cardSecondaryBg,
                    unfocusedContainerColor = colors.cardSecondaryBg
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            val filtered = remember(languageSearchQuery) {
                if (languageSearchQuery.isBlank()) SupportedLanguage.entries
                else {
                    val q = languageSearchQuery.trim().lowercase()
                    SupportedLanguage.entries.filter {
                        it.englishName.lowercase().contains(q) ||
                            it.nativeName.lowercase().contains(q) ||
                            it.code.lowercase().contains(q)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { lang ->
                    val isSelected = selectedLanguage == lang
                    val isInstalled = modelPacks.firstOrNull { it.iso == lang.code || it.languageTag.startsWith(lang.code) }?.isInstalled == true
                    Surface(
                        onClick = {
                            viewModel.setSelectedLanguage(lang)
                            showLanguageSheet = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) colors.accentContainer else colors.cardSecondaryBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.outline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${lang.englishName} (${lang.nativeName})",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) colors.accentContainerText else colors.textPrimary
                                )
                                Text(
                                    text = if (isInstalled) "Installed & ready" else "Neural pack · ${lang.downloadSizeMb} MB",
                                    fontSize = 11.sp,
                                    color = if (isInstalled) colors.meshContainerText else colors.textSecondary
                                )
                            }
                            if (isSelected) {
                                SoftStatusDot(color = colors.accent, dotSize = 8.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Soft tactical map canvas:
 * - Warm off-white cartographic surface (deep-night variant in dark theme).
 * - Subtle road networks and city block geometry in soft tones.
 * - Rotates dynamically with the phone's real compass heading.
 * - Soft indigo rescuer puck at center with directional vision cone and pulsing halo.
 * - Victims rendered as soft pin markers with floating call sign/distance cards.
 * - Interactive tap selection for victim pins.
 *
 * NOTE: pin/badge geometry here must stay in sync with the tap hit-testing below.
 */
@Composable
fun MinimalBrightMapCanvas(
    compassHeading: Float,
    victims: List<DistressVictim>,
    selectedVictim: DistressVictim?,
    connectedVictim: DistressVictim?,
    onVictimSelected: (DistressVictim) -> Unit,
    modifier: Modifier = Modifier,
    zoomScale: Float = 1.0f,
    panOffset: Offset = Offset.Zero,
    showCardLabels: Boolean = true,
    onMapTapped: (() -> Unit)? = null
) {
    val colors = MaterialTheme.minimalColors
    val isDark = colors.isDark

    val infiniteTransition = rememberInfiniteTransition(label = "rescuerPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val currentCompassHeading by rememberUpdatedState(compassHeading)
    val currentVictims by rememberUpdatedState(victims)
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentPanOffset by rememberUpdatedState(panOffset)
    val currentOnVictimSelected by rememberUpdatedState(onVictimSelected)
    val currentOnMapTapped by rememberUpdatedState(onMapTapped)

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
            val density = this
            detectTapGestures { tapOffset ->
                val cx = size.width / 2f + currentPanOffset.x
                val cy = size.height / 2f + currentPanOffset.y
                val maxRadius = minOf(size.width, size.height) * 0.42f * currentZoomScale

                var hitVictim: DistressVictim? = null
                val pinHitRadiusPx = with(density) { 42.dp.toPx() }
                val badgeHPx = with(density) { 26.dp.toPx() }
                val badgeWPx = with(density) { 150.dp.toPx() }
                val badgePadPx = with(density) { 16.dp.toPx() }

                currentVictims.forEach { victim ->
                    val angleRad = Math.toRadians(victim.relativeBearingDegrees.toDouble() - 90.0)
                    val normDist = (victim.distanceMeters / 90f).coerceIn(0.15f, 0.92f)
                    val r = normDist * maxRadius
                    val vx = cx + (r * cos(angleRad)).toFloat()
                    val vy = cy + (r * sin(angleRad)).toFloat()

                    val d2 = (tapOffset.x - vx) * (tapOffset.x - vx) + (tapOffset.y - vy) * (tapOffset.y - vy)
                    val cardY = vy - with(density) { 24.dp.toPx() }
                    val inBadge = tapOffset.x >= (vx - badgeWPx / 2f - badgePadPx) &&
                                  tapOffset.x <= (vx + badgeWPx / 2f + badgePadPx) &&
                                  tapOffset.y >= (cardY - badgePadPx) &&
                                  tapOffset.y <= (vy + badgePadPx)

                    if (d2 <= pinHitRadiusPx * pinHitRadiusPx || inBadge) {
                        hitVictim = victim
                    }
                }

                if (hitVictim != null) {
                    currentOnVictimSelected(hitVictim)
                } else {
                    currentOnMapTapped?.invoke()
                }
            }
        }
    ) {
        clipRect {
            val cx = size.width / 2f + panOffset.x
            val cy = size.height / 2f + panOffset.y
            val maxRadius = minOf(size.width, size.height) * 0.42f * zoomScale

            // 1. Warm / Deep Minimal Cartographic Background
            drawRect(colors.background)

            // 2. Subtle Cartographic Road / Street Grid Network (North-aligned tactical grid)
            val blockSize = 55.dp.toPx() * zoomScale
            val blockColor = colors.cardSecondaryBg
            for (ix in -4..4) {
                for (iy in -4..4) {
                    if ((ix + iy) % 2 == 0) {
                        val bx = cx + ix * (blockSize + 16.dp.toPx() * zoomScale)
                        val by = cy + iy * (blockSize + 16.dp.toPx() * zoomScale)
                        drawRoundRect(
                            color = blockColor,
                            topLeft = Offset(bx, by),
                            size = Size(blockSize, blockSize),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        )
                    }
                }
            }

            val roadWidth = 10.dp.toPx() * zoomScale
            val roadOutlineWidth = 12.dp.toPx() * zoomScale
            val roadColor = if (isDark) Color(0xFF1A1D22) else Color.White
            val roadBorder = colors.outline

            for (i in -3..3) {
                val offsetVal = i * (blockSize + 16.dp.toPx() * zoomScale)
                drawLine(
                    color = roadBorder,
                    start = Offset(cx - size.width * 2, cy + offsetVal),
                    end = Offset(cx + size.width * 2, cy + offsetVal),
                    strokeWidth = roadOutlineWidth
                )
                drawLine(
                    color = roadColor,
                    start = Offset(cx - size.width * 2, cy + offsetVal),
                    end = Offset(cx + size.width * 2, cy + offsetVal),
                    strokeWidth = roadWidth
                )

                drawLine(
                    color = roadBorder,
                    start = Offset(cx + offsetVal, cy - size.height * 2),
                    end = Offset(cx + offsetVal, cy + size.height * 2),
                    strokeWidth = roadOutlineWidth
                )
                drawLine(
                    color = roadColor,
                    start = Offset(cx + offsetVal, cy - size.height * 2),
                    end = Offset(cx + offsetVal, cy + size.height * 2),
                    strokeWidth = roadWidth
                )
            }

        // 3. Subtle Distance Reference Rings
        val distanceSteps = listOf(0.33f to "25m", 0.66f to "50m", 1.0f to "100m")
        distanceSteps.forEach { (step, _) ->
            val r = maxRadius * step
            drawCircle(
                color = colors.outline,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // 4. Cardinal Compass Indicators on Map Boundary (N, E, S, W)
        val cardinalPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#837D73")
            textSize = 10.sp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val northPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#EC6A5C")
            textSize = 11.sp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val cardinalDirections = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        cardinalDirections.forEach { (label, bearing) ->
            val angleRad = Math.toRadians(bearing.toDouble() - 90.0)
            val labelR = maxRadius * 1.06f
            val lx = cx + (labelR * cos(angleRad)).toFloat()
            val ly = cy + (labelR * sin(angleRad)).toFloat() + 3.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(
                label,
                lx,
                ly,
                if (label == "N") northPaint else cardinalPaint
            )
        }

        // 5. Victim Map Pins & Small Floating Cards (True relative coordinates)
        val cardTextPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#211F1C")
            textSize = 10.sp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val cardDistPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#7C7FE6")
            textSize = 9.sp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        victims.forEach { victim ->
            val angleRad = Math.toRadians(victim.relativeBearingDegrees.toDouble() - 90.0)
            val normDist = (victim.distanceMeters / 90f).coerceIn(0.15f, 0.92f)
            val r = normDist * maxRadius
            val vx = cx + (r * cos(angleRad)).toFloat()
            val vy = cy + (r * sin(angleRad)).toFloat()

            val isConnected = connectedVictim?.id == victim.id
            val isSelected = selectedVictim?.id == victim.id

            // Selection/Connection outer glow
            if (isSelected || isConnected) {
                drawCircle(
                    color = if (isConnected) colors.error.copy(alpha = 0.20f) else colors.accent.copy(alpha = 0.20f),
                    radius = 16.dp.toPx(),
                    center = Offset(vx, vy)
                )
            }

            // Pin marker (border adapts to theme)
            drawCircle(
                color = if (isDark) colors.background else Color.White,
                radius = 8.dp.toPx(),
                center = Offset(vx, vy)
            )
            drawCircle(
                color = if (isConnected) colors.sosDeep else if (isSelected) colors.accent else colors.error,
                radius = 6.dp.toPx(),
                center = Offset(vx, vy)
            )

            // Small Floating Card above pin
            if (showCardLabels) {
                val shortName = victim.callsign.replace("VICTIM-", "")
                val labelText = "$shortName · ${victim.distanceMeters}m"
                val textWidth = cardTextPaint.measureText(labelText)
                val cardW = textWidth + 14.dp.toPx()
                val cardH = 18.dp.toPx()
                val cardX = vx - cardW / 2f
                val cardY = vy - 24.dp.toPx()

                drawRoundRect(
                    color = if (isDark) colors.surface else Color.White,
                    topLeft = Offset(cardX, cardY),
                    size = Size(cardW, cardH),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                drawRoundRect(
                    color = when {
                        isConnected -> colors.error
                        isSelected -> colors.accent
                        else -> colors.outlineStrong
                    },
                    topLeft = Offset(cardX, cardY),
                    size = Size(cardW, cardH),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = if (isConnected || isSelected) 1.5.dp.toPx() else 1.dp.toPx())
                )

                drawContext.canvas.nativeCanvas.drawText(
                    labelText,
                    vx,
                    cardY + cardH * 0.72f,
                    if (isSelected) cardDistPaint else cardTextPaint
                )
            }
        }

        // 6. Rescuer GPS Location Puck at Center with rotating vision cone & chevron
        withTransform({
            rotate(currentCompassHeading, pivot = Offset(cx, cy))
        }) {
            val conePath = Path().apply {
                moveTo(cx, cy)
                lineTo(cx - 24.dp.toPx(), cy - 55.dp.toPx())
                lineTo(cx + 24.dp.toPx(), cy - 55.dp.toPx())
                close()
            }
            drawPath(
                path = conePath,
                brush = Brush.verticalGradient(
                    colors = listOf(colors.accent.copy(alpha = 0.20f), Color.Transparent),
                    startY = cy - 50.dp.toPx(),
                    endY = cy
                )
            )
            // Forward Chevron Arrow
            drawLine(
                color = Color.White,
                start = Offset(cx, cy - 10.dp.toPx()),
                end = Offset(cx - 4.dp.toPx(), cy - 4.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = Color.White,
                start = Offset(cx, cy - 10.dp.toPx()),
                end = Offset(cx + 4.dp.toPx(), cy - 4.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Center Rescuer Marker
        drawCircle(
            color = colors.accent.copy(alpha = 0.3f),
            radius = 12.dp.toPx(),
            center = Offset(cx, cy)
        )
        drawCircle(
            color = colors.accentDeep,
            radius = 6.dp.toPx(),
            center = Offset(cx, cy)
        )
        }
    }
}
