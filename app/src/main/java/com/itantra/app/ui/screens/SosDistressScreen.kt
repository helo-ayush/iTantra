package com.itantra.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.R
import com.itantra.app.model.SupportedLanguage
import com.itantra.app.model.VoiceStatus
import com.itantra.app.modelhub.ModelDownloadState
import com.itantra.app.ui.components.DigitalAudioVisualizer
import com.itantra.app.ui.components.soft.SoftBadge
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.components.soft.SoftMissionHeader
import com.itantra.app.ui.components.soft.SoftStatusDot
import com.itantra.app.ui.theme.minimalColors
import com.itantra.app.viewmodel.MissionControlViewModel
import kotlinx.coroutines.launch

/**
 * Emergency SOS screen, Soft Minimalism edition:
 * - Telemetry header: brand pill + live readiness capsule
 * - Soft tactile SOS dome: breathing aura, radar ring, press-scale physics
 * - Reassurance & stop controls
 * - Voice engine card + 1-tap dialect chips + language sheet
 * - Rescuer intercom console (2-way / 1-way receive-only)
 * - Live transcription card with quick emergency phrases
 * - Off-grid radios hardware card
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosDistressScreen(
    viewModel: MissionControlViewModel,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.minimalColors
    val uiState by viewModel.uiState.collectAsState()
    val isSosBroadcasting by viewModel.isSosBroadcasting.collectAsState()
    val wifiDirectEnabled by viewModel.wifiDirectEnabled.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val nearbyRescuers by viewModel.nearbyRescuers.collectAsState()
    val connectedRescuer by viewModel.connectedRescuer.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val modelWarning by viewModel.modelWarningMessage.collectAsState()
    val selectedLanguage = uiState.selectedLanguage
    val modelPacks by viewModel.modelPacks.collectAsState()
    val selectedPack = modelPacks.firstOrNull { it.iso == selectedLanguage.code }
    val selectedPackState = if (selectedPack?.isInstalled == true) ModelDownloadState.Installed
        else (selectedPack?.downloadState ?: ModelDownloadState.Idle)
    val messageLogs by viewModel.messageLogs.collectAsState()
    val currentTranscript = uiState.currentTranscript
    val voiceStatus = uiState.voiceStatus
    val isVadSpeaking by viewModel.isVadSpeaking.collectAsState()
    val isPttActive by viewModel.isPttActive.collectAsState()
    // A rescuer streaming a strictly one-way announcement: this device is a
    // receive-only endpoint, so it must not present talk affordances.
    val isReceivingOneWayBroadcast by viewModel.isReceivingOneWayBroadcast.collectAsState()
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val isSpeakerphoneOn by viewModel.isSpeakerphoneOn.collectAsState()

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var showLanguageSheet by remember { mutableStateOf(false) }
    var languageSearchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Smooth breathing transition for button aura
    val infiniteTransition = rememberInfiniteTransition(label = "sosPulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSosBroadcasting) 1.22f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosBroadcasting) 750 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = if (isSosBroadcasting) 0.45f else 0.15f,
        targetValue = if (isSosBroadcasting) 0.85f else 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosBroadcasting) 750 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    // Animated waveform bars for live intercom audio
    val waveBar1 by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse),
        label = "waveBar1"
    )
    val waveBar2 by infiniteTransition.animateFloat(
        initialValue = 16f, targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "waveBar2"
    )
    val waveBar3 by infiniteTransition.animateFloat(
        initialValue = 8f, targetValue = 26f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "waveBar3"
    )
    val waveBar4 by infiniteTransition.animateFloat(
        initialValue = 20f, targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(460, easing = LinearEasing), RepeatMode.Reverse),
        label = "waveBar4"
    )

    // Button press interaction source for tactile depression
    val buttonInteractionSource = remember { MutableInteractionSource() }
    val isPressed by buttonInteractionSource.collectIsPressedAsState()
    val buttonPressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "buttonPressScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =======================================================
        // 1. MISSION TELEMETRY HEADER
        // =======================================================
        SoftMissionHeader(
            mode = "Off-grid",
            statusText = if (isSosBroadcasting) "Distress active" else "Ready",
            statusActive = isSosBroadcasting,
            activeColor = colors.error
        )

        // =======================================================
        // 2. SOFT TACTILE EMERGENCY SOS DOME
        // =======================================================
        Box(
            modifier = Modifier
                .size(246.dp)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outermost soft breathing pulse halo
            Box(
                modifier = Modifier
                    .size(244.dp)
                    .scale(ringScale)
                    .alpha(ringAlpha)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isSosBroadcasting) {
                                listOf(colors.error.copy(alpha = 0.28f), colors.error.copy(alpha = 0.08f), Color.Transparent)
                            } else {
                                listOf(colors.error.copy(alpha = 0.10f), colors.error.copy(alpha = 0.04f), Color.Transparent)
                            }
                        )
                    )
            )

            // Middle radar reference ring with cardinal markers
            Box(
                modifier = Modifier
                    .size(212.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.dp,
                        color = if (isSosBroadcasting) colors.error.copy(alpha = 0.30f) else colors.outline,
                        shape = CircleShape
                    )
            )

            // 4 Cardinal Micro-Ticks for tactile instrument aesthetic
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
                    .size(width = 2.dp, height = 6.dp)
                    .background(colors.outlineStrong, RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .size(width = 2.dp, height = 6.dp)
                    .background(colors.outlineStrong, RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 18.dp)
                    .size(width = 6.dp, height = 2.dp)
                    .background(colors.outlineStrong, RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 18.dp)
                    .size(width = 6.dp, height = 2.dp)
                    .background(colors.outlineStrong, RoundedCornerShape(1.dp))
            )

            // Central Tactile Dome Button (Using Surface to guarantee 100% reliable click registration)
            Surface(
                onClick = {
                    if (isSosBroadcasting) viewModel.stopSos() else viewModel.startSos()
                },
                shape = CircleShape,
                color = Color.Transparent,
                interactionSource = buttonInteractionSource,
                shadowElevation = if (isSosBroadcasting) 18.dp else 10.dp,
                modifier = Modifier
                    .size(178.dp)
                    .scale(buttonPressScale)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (isSosBroadcasting) {
                                Brush.radialGradient(
                                    colors = listOf(
                                        colors.error,
                                        colors.sosDeep
                                    )
                                )
                            } else {
                                Brush.radialGradient(
                                    colors = listOf(
                                        colors.error.copy(alpha = 0.92f),
                                        colors.sosDeep
                                    )
                                )
                            }
                        )
                        .border(
                            width = 2.5.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.45f),
                                    Color.White.copy(alpha = 0.10f)
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Emergency Upper Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_warning,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Emergency",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }

                        // Hero SOS Headline
                        Text(
                            text = "SOS",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.5).sp,
                            color = Color.White
                        )

                        // Action Micro-Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.20f))
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SoftIcon(
                                    resId = if (isSosBroadcasting) R.drawable.ic_soft_siren else R.drawable.ic_soft_shield,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isSosBroadcasting) "Broadcasting" else "Tap to start",
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

        // =======================================================
        // 3. REASSURING CONTEXT & STOP SOS CONTROLS
        // =======================================================
        if (!isSosBroadcasting) {
            // Calm, reassuring message on Standby
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.cardSecondaryBg)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_shield,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tap SOS to alert every rescue node in a 250 m mesh radius",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Emergency active alert banner + Cancel button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Your distress beacon is broadcasting.\nNearby rescuers' phones are vibrating to locate you.",
                    fontSize = 13.sp,
                    color = colors.sosContainerText,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Button(
                    onClick = { viewModel.stopSos() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.sosContainer,
                        contentColor = colors.sosContainerText
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .height(44.dp)
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_close,
                        contentDescription = null,
                        tint = colors.sosContainerText,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Stop distress SOS",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // =======================================================
        // 4. VOICE ENGINE CARD & 1-TAP DIALECT BAR
        // =======================================================
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
                // Top Row: Selected Language summary & Change trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showLanguageSheet = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        // Native Glyph Avatar Squircle
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .shadow(elevation = 4.dp, shape = RoundedCornerShape(14.dp), spotColor = colors.accent.copy(alpha = 0.25f))
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(colors.accent, colors.accentDeep))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = selectedLanguage.nativeInitial,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onAccent
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Voice engine",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp,
                                    color = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                SoftBadge(
                                    text = "Offline AI",
                                    containerColor = colors.cardSecondaryBg,
                                    contentColor = colors.textSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${selectedLanguage.englishName} (${selectedLanguage.nativeName})",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = when (selectedPackState) {
                                    is ModelDownloadState.Installed -> "Installed · On-device STT + TTS"
                                    is ModelDownloadState.Downloading ->
                                        "Downloading · ${(selectedPackState.progress * 100).toInt()}%"
                                    is ModelDownloadState.Paused ->
                                        "Paused · ${(selectedPackState.progress * 100).toInt()}%"
                                    is ModelDownloadState.Verifying -> "Verifying · On-device STT + TTS"
                                    is ModelDownloadState.Extracting -> "Extracting · On-device STT + TTS"
                                    is ModelDownloadState.Error -> "Download failed · Tap to retry"
                                    else -> "Not downloaded · ${formatSizeMb(selectedPack?.sizeMb ?: selectedLanguage.downloadSizeMb.toDouble())} MB"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    // Soft pill dropdown trigger
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.accentContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Change",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accentContainerText
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            SoftIcon(
                                resId = R.drawable.ic_soft_chevron_down,
                                contentDescription = "Change Language",
                                tint = colors.accentContainerText,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // Quick 1-Tap Dialect Switcher Bar (Instant zero-friction switching)
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
                            Text(
                                text = lang.nativeName,
                                fontSize = 11.sp,
                                fontWeight = if (isLangActive) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isLangActive) colors.onAccent else colors.textSecondary
                            )
                        }
                    }

                    // "+More" Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.cardSecondaryBg)
                            .clickable { showLanguageSheet = true }
                            .padding(horizontal = 9.dp, vertical = 8.dp),
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

                // Soft Audio Visualizer (Live Microphone Activity)
                if (isSosBroadcasting) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DigitalAudioVisualizer(
                        audioLevel = audioLevel,
                        isActive = isSosBroadcasting,
                        label = when {
                            isReceivingOneWayBroadcast -> "Receive-only rescuer broadcast"
                            connectedRescuer != null -> "Live 2-way intercom"
                            else -> "Hands-free emergency mic"
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // =======================================================
        // 5. RESCUER RADAR & LIVE INTERCOM (EXPANDS ON SOS)
        // =======================================================
        AnimatedVisibility(
            visible = isSosBroadcasting,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Model Download Warning Banner (if model pack is missing for voice transcription)
                if (modelWarning != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.rescueContainer)
                            .border(1.dp, colors.rescue.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
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

                // =====================================================
                // LIVE RESCUER CALL / 1-WAY BROADCAST CONSOLE CARD
                // =====================================================
                if (connectedRescuer != null || isReceivingOneWayBroadcast) {
                    val rescuer = connectedRescuer
                    val isBroadcast = isReceivingOneWayBroadcast

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 4.dp,
                                shape = RoundedCornerShape(22.dp),
                                spotColor = if (isBroadcast) colors.rescue.copy(alpha = 0.25f) else colors.mesh.copy(alpha = 0.25f)
                            )
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (isBroadcast) colors.rescueContainer.copy(alpha = 0.45f)
                                else colors.meshContainer.copy(alpha = 0.45f)
                            )
                            .border(
                                1.dp,
                                if (isBroadcast) colors.rescue.copy(alpha = 0.5f) else colors.mesh.copy(alpha = 0.5f),
                                RoundedCornerShape(22.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Header Row: Rescuer Info / Broadcast Title + Status Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(if (isBroadcast) colors.rescue else colors.mesh),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SoftIcon(
                                            resId = if (isBroadcast) R.drawable.ic_soft_megaphone else R.drawable.ic_soft_headset,
                                            contentDescription = null,
                                            tint = colors.onAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = if (isBroadcast) "Emergency broadcast" else "Rescuer connected",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isBroadcast) colors.rescueContainerText else colors.meshContainerText,
                                            letterSpacing = 0.3.sp
                                        )
                                        Text(
                                            text = rescuer?.callsign ?: "Rescuer megaphone",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = if (isBroadcast) {
                                                "1-way announcement · Listen only"
                                            } else {
                                                val identity = rescuer?.identityLabel
                                                "${rescuer?.role ?: "iTantra rescuer"} · ~${rescuer?.distanceMeters ?: 1} m away" +
                                                    (identity?.let { " · $it" } ?: "")
                                            },
                                            fontSize = 12.sp,
                                            color = colors.textSecondary
                                        )
                                    }
                                }

                                // Status Badge
                                if (isBroadcast) {
                                    SoftBadge(
                                        text = "On air",
                                        containerColor = colors.rescue,
                                        contentColor = colors.onAccent
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.mesh)
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Box(modifier = Modifier.width(3.dp).height(waveBar1.dp).background(colors.onAccent, RoundedCornerShape(2.dp)))
                                        Box(modifier = Modifier.width(3.dp).height(waveBar2.dp).background(colors.onAccent, RoundedCornerShape(2.dp)))
                                        Box(modifier = Modifier.width(3.dp).height(waveBar3.dp).background(colors.onAccent, RoundedCornerShape(2.dp)))
                                        Box(modifier = Modifier.width(3.dp).height(waveBar4.dp).background(colors.onAccent, RoundedCornerShape(2.dp)))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Live",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.onAccent
                                        )
                                    }
                                }
                            }

                            // 1-Way Broadcast Information Banner (if receiving broadcast)
                            if (isBroadcast) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.rescueContainer)
                                        .border(1.dp, colors.rescue.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_mic_off,
                                            contentDescription = null,
                                            tint = colors.rescueContainerText,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "1-way rescuer broadcast — replies disabled",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.rescueContainerText
                                            )
                                            Text(
                                                text = "The rescuer is broadcasting to everyone in range. Your mic is locked and muted.",
                                                fontSize = 11.sp,
                                                color = colors.rescueContainerText,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // Soft Audio Visualizer
                            DigitalAudioVisualizer(
                                audioLevel = audioLevel,
                                isActive = true,
                                label = if (isBroadcast) {
                                    "Rescuer 1-way broadcast (receive only)"
                                } else {
                                    "Rescuer 2-way audio link"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Divider
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(
                                        if (isBroadcast) colors.rescue.copy(alpha = 0.25f)
                                        else colors.mesh.copy(alpha = 0.25f)
                                    )
                            )

                            // Hands-Free Audio Controls Row (Big Mic, Big Speaker, Big Disconnect)
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
                                        onClick = {
                                            if (!isBroadcast) {
                                                viewModel.toggleMicMute()
                                            }
                                        },
                                        enabled = !isBroadcast,
                                        shape = CircleShape,
                                        color = when {
                                            isBroadcast -> colors.cardSecondaryBg
                                            isMicMuted -> colors.sosContainer
                                            else -> colors.surface
                                        },
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            when {
                                                isBroadcast -> colors.outline
                                                isMicMuted -> colors.error.copy(alpha = 0.35f)
                                                else -> colors.outline
                                            }
                                        ),
                                        shadowElevation = 0.dp,
                                        modifier = Modifier.size(58.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            SoftIcon(
                                                resId = if (isBroadcast || isMicMuted) R.drawable.ic_soft_mic_off else R.drawable.ic_soft_mic,
                                                contentDescription = "Mute Mic",
                                                tint = when {
                                                    isBroadcast -> colors.textTertiary
                                                    isMicMuted -> colors.sosContainerText
                                                    else -> colors.textPrimary
                                                },
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = when {
                                            isBroadcast -> "Mic locked"
                                            isMicMuted -> "Unmute"
                                            else -> "Mute mic"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = when {
                                            isBroadcast -> colors.textTertiary
                                            isMicMuted -> colors.sosContainerText
                                            else -> colors.textSecondary
                                        }
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
                                            viewModel.disconnectConnectedRescuer()
                                        },
                                        enabled = true,
                                        shape = CircleShape,
                                        color = if (isBroadcast) colors.rescue else colors.error,
                                        shadowElevation = 2.dp,
                                        modifier = Modifier.size(58.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            SoftIcon(
                                                resId = if (isBroadcast) R.drawable.ic_soft_close else R.drawable.ic_soft_call_end,
                                                contentDescription = if (isBroadcast) "Dismiss Broadcast" else "Disconnect",
                                                tint = colors.onAccent,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isBroadcast) "Dismiss" else "End call",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isBroadcast) colors.rescueContainerText else colors.sosContainerText
                                    )
                                }
                            }
                        }
                    }
                }

                // =====================================================
                // LIVE TRANSCRIPTION CARD (Shows sent + received text)
                // =====================================================
                if (isSosBroadcasting || connectedRescuer != null || messageLogs.isNotEmpty()) {
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
                                        text = if (selectedPack?.isInstalled == true) "STT active" else "Pack required",
                                        containerColor = if (selectedPack?.isInstalled == true) colors.badgeMintContainer else colors.rescueContainer,
                                        contentColor = if (selectedPack?.isInstalled == true) colors.badgeMintText else colors.rescueContainerText
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

                            // Voice Controls Row: Receive-only status when rescuer broadcasts
                            if (isReceivingOneWayBroadcast) {
                                // Receive-only participant of a 1-way rescuer broadcast:
                                // no talk/mic affordance is shown, because nothing sent
                                // from here is heard on the other side.
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.cardSecondaryBg)
                                        .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                                        .padding(vertical = 12.dp, horizontal = 14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_hearing,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "Receive-only emergency broadcast",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.textPrimary
                                            )
                                            Text(
                                                text = "The rescuer is transmitting one-way. Your mic is disabled.",
                                                fontSize = 11.sp,
                                                color = colors.textSecondary,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // Active speaking recording pulse banner
                            if (!isReceivingOneWayBroadcast && (isVadSpeaking || isPttActive)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.error.copy(alpha = 0.10f))
                                        .border(0.5.dp, colors.error.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SoftStatusDot(color = colors.error, dotSize = 8.dp)
                                        Text(
                                            text = "Recording… release or pause to send",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.sosContainerText
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
                                                isListening -> colors.error.copy(alpha = 0.10f)
                                                isTranscribing -> colors.meshContainer.copy(alpha = 0.55f)
                                                isWarning -> colors.rescueContainer
                                                else -> colors.accentContainer.copy(alpha = 0.6f)
                                            }
                                        )
                                        .border(
                                            1.dp,
                                            when {
                                                isListening -> colors.error.copy(alpha = 0.4f)
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
                                                isListening -> colors.sosContainerText
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
                                            text = "Voice transceiver standby",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = if (selectedPack?.isInstalled == true)
                                                "Hands-free voice is active. Speech is transcribed and broadcast as text over the mesh."
                                            else
                                                "The neural STT pack isn't downloaded yet. Voice-to-text needs the offline language model.",
                                            fontSize = 11.sp,
                                            color = colors.textSecondary,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            // Localized Quick Emergency Transmit Chips
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Quick emergency phrases (${selectedLanguage.englishName})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp,
                                    color = colors.textSecondary
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    selectedLanguage.quickSosPhrases.forEach { phrase ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(colors.cardSecondaryBg)
                                                .border(0.5.dp, colors.outline, RoundedCornerShape(12.dp))
                                                .clickable { viewModel.sendBroadcastTextMessage(phrase) }
                                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                        ) {
                                            Text(
                                                text = phrase,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.textPrimary
                                            )
                                        }
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
                                                else colors.meshContainer
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (msg.isLocal) "↑" else "↓",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (msg.isLocal) colors.accentContainerText else colors.meshContainerText
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
                                            text = if (msg.isLocal) "You · ${msg.senderCallsign}" else "Rescuer · ${msg.senderCallsign}",
                                            fontSize = 10.sp,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Nearby Rescuers Detected Card
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
                            Text(
                                text = "Rescuers in range (${nearbyRescuers.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftStatusDot(color = colors.mesh, dotSize = 7.dp)
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Scanning 250 m",
                                    fontSize = 11.sp,
                                    color = colors.badgeMintText,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        nearbyRescuers.forEach { rescuer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.cardSecondaryBg)
                                    .border(0.5.dp, colors.outline, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.badgeBlueContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_headset,
                                            contentDescription = null,
                                            tint = colors.badgeBlueText,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = rescuer.callsign,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "${rescuer.role} · ${rescuer.distanceMeters} m away" +
                                                (rescuer.identityLabel?.let { " · $it" } ?: ""),
                                            fontSize = 11.sp,
                                            color = colors.textSecondary
                                        )
                                    }
                                }

                                SoftBadge(
                                    text = if (rescuer.isConnected) "Linked" else "Ready",
                                    containerColor = if (rescuer.isConnected) colors.badgeMintContainer else colors.badgeBlueContainer,
                                    contentColor = if (rescuer.isConnected) colors.badgeMintText else colors.badgeBlueText
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }

        // =======================================================
        // 6. UNIFIED "OFF-GRID RADIOS" HARDWARE CARD (STANDBY vs ACTIVE)
        // =======================================================
        val radioAlpha by animateFloatAsState(
            targetValue = if (isSosBroadcasting) 1f else 0.88f,
            animationSpec = tween(300),
            label = "radioAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(radioAlpha)
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Section Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Off-grid radios",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    SoftBadge(
                        text = if (isSosBroadcasting) "Broadcasting at full power" else "Auto-starts on SOS",
                        containerColor = if (isSosBroadcasting) colors.badgeMintContainer else colors.cardSecondaryBg,
                        contentColor = if (isSosBroadcasting) colors.badgeMintText else colors.textSecondary
                    )
                }

                // Row 1: Wi-Fi Direct P2P Mesh
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (isSosBroadcasting) colors.accentContainer else colors.cardSecondaryBg),
                            contentAlignment = Alignment.Center
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_wifi,
                                contentDescription = "Wi-Fi Direct",
                                tint = if (isSosBroadcasting) colors.accent else colors.textTertiary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Wi-Fi Direct P2P",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                SoftBadge(
                                    text = if (isSosBroadcasting) "P2P active" else "Locked",
                                    containerColor = if (isSosBroadcasting) colors.badgeMintContainer else colors.cardSecondaryBg,
                                    contentColor = if (isSosBroadcasting) colors.badgeMintText else colors.textTertiary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isSosBroadcasting) {
                                    "Broadcasting on UDP port 8889 · direct device link"
                                } else {
                                    "High-speed local audio & mesh network"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Switch(
                        checked = wifiDirectEnabled,
                        onCheckedChange = { viewModel.toggleWifiDirect(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = colors.outlineStrong
                        )
                    )
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // Row 2: Bluetooth BLE Beacon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (isSosBroadcasting) colors.badgeBlueContainer else colors.cardSecondaryBg),
                            contentAlignment = Alignment.Center
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_bluetooth,
                                contentDescription = "Bluetooth",
                                tint = if (isSosBroadcasting) colors.badgeBlueText else colors.textTertiary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Bluetooth BLE mesh",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                SoftBadge(
                                    text = if (isSosBroadcasting) "BLE active" else "Locked",
                                    containerColor = if (isSosBroadcasting) colors.badgeBlueContainer else colors.cardSecondaryBg,
                                    contentColor = if (isSosBroadcasting) colors.badgeBlueText else colors.textTertiary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isSosBroadcasting) "Broadcasting emergency beacon" else "Continuous low-power emergency beacon",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Switch(
                        checked = bluetoothEnabled,
                        onCheckedChange = { viewModel.toggleBluetooth(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = colors.outlineStrong
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }

    // =======================================================
    // 7. 10-LANGUAGE MODAL BOTTOM SHEET WITH SEARCH
    // =======================================================
    if (showLanguageSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLanguageSheet = false },
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colors.outlineStrong)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sheet Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Voice language",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "10 Indic neural packs, 100% on-device",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                showLanguageSheet = false
                            }
                        }
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_close,
                            contentDescription = "Close",
                            tint = colors.textSecondary
                        )
                    }
                }

                // Instant Search Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cardSecondaryBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_search,
                            contentDescription = "Search",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = languageSearchQuery,
                            onValueChange = { languageSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Medium
                            ),
                            decorationBox = { innerTextField ->
                                if (languageSearchQuery.isEmpty()) {
                                    Text(
                                        text = "Search language or dialect…",
                                        fontSize = 14.sp,
                                        color = colors.textTertiary
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // Filtered List of Supported Languages
                val filteredLanguages = remember(languageSearchQuery) {
                    if (languageSearchQuery.isBlank()) {
                        SupportedLanguage.entries
                    } else {
                        val q = languageSearchQuery.trim().lowercase()
                        SupportedLanguage.entries.filter {
                            it.englishName.lowercase().contains(q) ||
                                it.nativeName.lowercase().contains(q)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLanguages) { lang ->
                        val isSelected = selectedLanguage == lang
                        val pack = modelPacks.firstOrNull { it.iso == lang.code }
                        val state = if (pack?.isInstalled == true) ModelDownloadState.Installed
                            else (pack?.downloadState ?: ModelDownloadState.Idle)
                        val sizeMb = pack?.sizeMb ?: lang.downloadSizeMb.toDouble()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) colors.accentContainer else colors.cardSecondaryBg)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.outline,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    // Row main click always selects the language + dismisses.
                                    viewModel.setSelectedLanguage(lang)
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        showLanguageSheet = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        // Native Glyph Avatar
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(
                                                    when (state) {
                                                        is ModelDownloadState.Installed -> colors.accent
                                                        is ModelDownloadState.Downloading,
                                                        is ModelDownloadState.Paused,
                                                        is ModelDownloadState.Verifying,
                                                        is ModelDownloadState.Extracting -> colors.accentContainer
                                                        else -> colors.surface
                                                    }
                                                )
                                                .border(
                                                    1.dp,
                                                    if (state is ModelDownloadState.Installed) colors.accent else colors.outline,
                                                    RoundedCornerShape(13.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = lang.nativeInitial,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (state is ModelDownloadState.Installed) colors.onAccent else colors.textSecondary
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = lang.englishName,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSelected) colors.accentContainerText else colors.textPrimary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "(${lang.nativeName})",
                                                    fontSize = 14.sp,
                                                    color = colors.textSecondary
                                                )
                                            }

                                            Text(
                                                text = "On-device size · ${formatSizeMb(sizeMb)} MB (STT + TTS)",
                                                fontSize = 12.sp,
                                                color = colors.textSecondary
                                            )
                                        }
                                    }

                                    // Trailing action, driven by the live download state.
                                    when {
                                        state is ModelDownloadState.Installed && isSelected -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(colors.accent)
                                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                            ) {
                                                SoftIcon(
                                                    resId = R.drawable.ic_soft_check,
                                                    contentDescription = null,
                                                    tint = colors.onAccent,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Active",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.onAccent
                                                )
                                            }
                                        }

                                        state is ModelDownloadState.Installed -> {
                                            SoftBadge(
                                                text = "Installed",
                                                containerColor = colors.badgeMintContainer,
                                                contentColor = colors.badgeMintText
                                            )
                                        }

                                        state is ModelDownloadState.Downloading -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                IconButton(
                                                    onClick = { viewModel.pauseModelDownload(lang.languageTag) },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    SoftIcon(
                                                        resId = R.drawable.ic_soft_pause,
                                                        contentDescription = "Pause ${lang.englishName}",
                                                        tint = colors.textSecondary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { viewModel.cancelModelDownload(lang.languageTag) },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    SoftIcon(
                                                        resId = R.drawable.ic_soft_close,
                                                        contentDescription = "Cancel ${lang.englishName}",
                                                        tint = colors.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        state is ModelDownloadState.Paused -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                IconButton(
                                                    onClick = { viewModel.downloadModel(lang.languageTag) },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    SoftIcon(
                                                        resId = R.drawable.ic_soft_play,
                                                        contentDescription = "Resume ${lang.englishName}",
                                                        tint = colors.accent,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { viewModel.cancelModelDownload(lang.languageTag) },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    SoftIcon(
                                                        resId = R.drawable.ic_soft_close,
                                                        contentDescription = "Cancel ${lang.englishName}",
                                                        tint = colors.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        state is ModelDownloadState.Verifying || state is ModelDownloadState.Extracting -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = if (state is ModelDownloadState.Verifying) "Verifying…" else "Extracting…",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.accent
                                                )
                                                IconButton(
                                                    onClick = { viewModel.cancelModelDownload(lang.languageTag) },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    SoftIcon(
                                                        resId = R.drawable.ic_soft_close,
                                                        contentDescription = "Cancel ${lang.englishName}",
                                                        tint = colors.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        state is ModelDownloadState.Error -> {
                                            Text(
                                                text = "Retry",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.error,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(colors.sosContainer)
                                                    .clickable { viewModel.downloadModel(lang.languageTag) }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }

                                        else -> {
                                            // Idle or Cancelled — offer the download.
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(colors.accent)
                                                    .clickable { viewModel.downloadModel(lang.languageTag) }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                SoftIcon(
                                                    resId = R.drawable.ic_soft_download,
                                                    contentDescription = null,
                                                    tint = colors.onAccent,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Download · ${formatSizeMb(sizeMb)} MB",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.onAccent
                                                )
                                            }
                                        }
                                    }
                                }

                                // Progress / status line below the row.
                                when (state) {
                                    is ModelDownloadState.Downloading -> {
                                        LinearProgressIndicator(
                                            progress = { state.progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(CircleShape),
                                            color = colors.accent,
                                            trackColor = colors.outline
                                        )
                                        Text(
                                            text = "${(state.progress * 100).toInt()}% · ${formatSizeMb(state.progressBytes / (1024.0 * 1024.0))} / ${formatSizeMb(state.totalBytes / (1024.0 * 1024.0))} MB",
                                            fontSize = 11.sp,
                                            color = colors.textSecondary
                                        )
                                    }
                                    is ModelDownloadState.Paused -> {
                                        LinearProgressIndicator(
                                            progress = { state.progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(CircleShape),
                                            color = colors.badgePurpleText,
                                            trackColor = colors.outline
                                        )
                                        Text(
                                            text = "Paused · ${(state.progress * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.badgePurpleText
                                        )
                                    }
                                    is ModelDownloadState.Error -> {
                                        Text(
                                            text = state.message,
                                            fontSize = 11.sp,
                                            color = colors.error
                                        )
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** Formats a size in MiB to one decimal place for compact UI labels. */
private fun formatSizeMb(sizeMb: Double): String =
    String.format(java.util.Locale.US, "%.1f", sizeMb)
